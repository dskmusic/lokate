# ANR "Lokate no responde" al encender el móvil — nota de cambio

**Fecha:** 2026-10-21
**Alcance:** app Android (`android/app/src/main/java/com/dskmusic/lokate/`)

---

## 1. Problema

En un **Xiaomi 14T** (HyperOS / Android 14+), al reiniciar o encender el móvil aparecía el diálogo
*"Lokate sigue sin responder"* (ANR). No ocurría en Xiaomi Redmi Note 13 ni en Redmi Note 14.

## 2. Causa raíz

En el arranque en frío, el receptor de `BOOT_COMPLETED` ejecutaba trabajo pesado **en el hilo
principal**, justo donde Android vigila con un presupuesto de tiempo estricto (~10 s):

- Primera lectura de **Preferences DataStore** (E/S de disco) en el `init` del `ServiceLocator`.
- Construcción de **`SessionManager`** (clave en el **Keystore** por hardware + `EncryptedSharedPreferences`).
- Construcción de la pila **OkHttp/Retrofit** (con reflection).
- Primera inicialización de **WorkManager** al programar el worker de respaldo.

En el 14T, con el móvil recién encendido (mucha actividad de disco y Keystore), la suma de todo
eso superaba el tope y saltaba el ANR. El código es el mismo en los tres móviles; el 14T era el que
más lento acababa la secuencia.

## 3. Qué se cambió

Tres cambios, todos para mover el trabajo pesado fuera del hilo principal **sin cambiar el
comportamiento** (la recuperación tras arranque sigue igual: el servicio de ubicación y el worker
de 15 min se arrancan exactamente como antes).

### 3.1 `location/BootReceiver.kt` — el arreglo principal

`onReceive` ahora devuelve enseguida con `goAsync()` y hace lo crítico en `Dispatchers.IO`, llamando
a `pendingResult.finish()` en un `finally`. La recarga de geocercas sigue siendo "dispara y olvida".

### 3.2 `di/ServiceLocator.kt` — sin `runBlocking` en el `init`

La lectura de la URL del servidor (`serverBaseUrlOverride`) pasó a un hilo de fondo con `runCatching`.
Es seguro porque `DynamicBaseUrlInterceptor` re-lee `ServerConfig.baseUrl` en **cada** petición.

### 3.3 `LokateApplication.kt` — pre-calentamiento

En `onCreate` se construye en un hilo de fondo `SessionManager` (Keystore) y la pila de red, para que
el primer `isLoggedIn()` en el hilo principal sea barato.

---

## 4. Cómo revertir

### 4.1 `BootReceiver.kt`

Devolver el cuerpo de `onReceive` al original (quitar el `goAsync()` y hacerlo todo síncrono):

```kotlin
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val locator = ServiceLocator.getInstance(context)
        if (!locator.authRepository.isLoggedIn()) return

        LocationServiceController.ensureStarted(context)
        LocationUpdateWorker.schedule(context)
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { ZoneGeofencing.refresh(app) }
        }
    }
```

### 4.2 `ServiceLocator.kt`

- En el `init`, volver a la versión bloqueante:

```kotlin
    init {
        // Igual que el arranque de MainActivity: lectura bloqueante única de DataStore para tener
        // la URL del servidor lista antes de la primera petición de red.
        ServerConfig.update(runBlocking { settings.serverBaseUrlOverride.first() })
    }
```

- Y en los imports: quitar `CoroutineScope`, `Dispatchers`, `SupervisorJob`, `launch` y volver a
  añadir `import kotlinx.coroutines.runBlocking`.

### 4.3 `LokateApplication.kt`

Borrar el bloque de "Pre-calienta fuera del hilo principal…" (el `CoroutineScope(Dispatchers.IO).launch`
que llama a `authRepository.isLoggedIn()`). No requiere tocar imports (los que usa ya estaban).

---

## 5. Verificación tras el cambio

1. Encender/reiniciar el móvil: **no** debe aparecer el "Lokate no responde".
2. Sin abrir la app, la ubicación debe seguir reportándose al grupo (el servicio arranca solo).
3. El aviso de "lleva X sin dar señal" (worker de 15 min) debe seguir funcionando.

Si en el futuro reaparece el ANR, comprobar primero `logcat` buscando `ANR in com.dskmusic.lokate`
y revisar si se añadió nuevo trabajo pesado en `onReceive` de algún receptor o en el `init` del
`ServiceLocator`.
