# Lokate — guía de despliegue

Cómo poner Lokate en marcha en cualquier servidor o VPS con Docker, desde cero. Para cómo
funciona el backend por dentro ver [`BACKEND.md`](BACKEND.md); para el manual de uso de la app,
[`ANDROID.md`](ANDROID.md).

Todo el despliegue son dos piezas: **un contenedor Docker** con la API, y **el APK** que se
sirve desde ese mismo contenedor para que la familia lo instale y se actualice sola.

## 0. Lo que necesitas antes de empezar

| Cosa | Para qué | Notas |
|---|---|---|
| Servidor con Docker + Docker Compose v2 | Ejecutar la API | Cualquier Linux; con 512 MB de RAM libres basta |
| Un puerto libre (por defecto `8082`) | Escuchar la API | Compruébalo con `ss -tlnp` antes de arrancar |
| Un dominio o subdominio con HTTPS | Acceso desde fuera | Android exige HTTPS para el tráfico normal |
| Proyecto de Firebase | Notificaciones push (FCM) | Gratis, plan Spark |
| Android Studio | Compilar el APK | Solo en tu PC, no en el servidor |

## 1. Firebase (notificaciones push)

Sin esto todo funciona **menos** las notificaciones (zonas, hacer sonar, mensajes prioritarios).

1. Crea un proyecto en [console.firebase.google.com](https://console.firebase.google.com).
2. **Añade una app Android** con el package `com.dskmusic.lokate` (o el tuyo, si lo cambias en
   `android/app/build.gradle.kts`) y descarga `google-services.json` →
   `android/app/google-services.json`.
3. **Configuración del proyecto → Cuentas de servicio → Generar nueva clave privada**: descarga
   el JSON — lo copiarás al servidor como `firebase-credentials.json` (paso 3).

Los dos archivos están en `.gitignore` a propósito: son credenciales, no van al repositorio.

## 2. Compilar el APK

En Android Studio, en tu PC:

1. Edita `android/app/build.gradle.kts` y pon tu URL pública en `API_BASE_URL` (**con la barra
   final**), por ejemplo `"https://familia.tudominio.com/"`.
2. **Build → Generate Signed App Bundle / APK → APK**, crea (o reutiliza) tu keystore y compila
   la variante `release`.
3. El resultado sale en `android/app/release/app-release.apk` → renómbralo a `lokate.apk`.

**Guarda el keystore y su contraseña.** Android solo deja actualizar una app instalada con un
APK firmado con la misma clave; si se pierde, la única salida es desinstalar y reinstalar en
todos los móviles.

Para cada versión nueva sube `versionCode` (entero, +1) y `versionName` (lo que ve el usuario)
en ese mismo archivo.

## 3. Subir el backend al servidor

Copia la carpeta `backend/` del repositorio a, por ejemplo, `/opt/lokate` (scp, rsync o WinSCP)
y deja esta estructura:

```
/opt/lokate/
├── app/                        # código de la API
├── web_inicial/                # landing + PWA (se sirve directo del disco)
├── apk/
│   └── lokate.apk              # el APK del paso 2
├── backups/                    # vacía; aquí caerán las copias de seguridad
├── .env                        # copiado de .env.example y rellenado (paso 4)
├── firebase-credentials.json   # la clave de servicio del paso 1
├── docker-compose.yml
├── Dockerfile
└── requirements.txt
```

`apk/`, `backups/` y `firebase-credentials.json` **tienen que existir antes** del primer
`docker compose up`: están declarados como bind mounts y Docker crearía carpetas vacías en su
lugar (y un directorio donde se espera el JSON de Firebase hace fallar el arranque).

## 4. Configurar `.env`

```bash
cd /opt/lokate
cp .env.example .env
nano .env
```

Mínimo imprescindible antes de arrancar:

- `JWT_SECRET` — genéralo con `openssl rand -hex 32`. Cambiarlo más adelante cierra la sesión de
  todo el mundo.
- `ADMIN_USERNAME` / `ADMIN_PASSWORD` — acceso al panel web `/admin`.
- `MASTER_ADMIN_USERNAME` / `MASTER_ADMIN_PASSWORD` — primer usuario administrador **de la app**
  (se crea solo al arrancar si no existe). Con él creas el grupo familiar y das permisos a los
  demás.

El resto (`ACCESS_TOKEN_EXPIRE_DAYS`, `LOCATION_RETENTION_DAYS`, `FIREBASE_CREDENTIALS_PATH`,
`DATABASE_URL`) tiene valores razonables por defecto; la tabla completa está en
[`BACKEND.md`](BACKEND.md).

## 5. Arrancar

```bash
cd /opt/lokate
docker compose up -d --build
docker compose logs --tail 50 lokate-api    # que no haya errores de arranque
curl http://localhost:8082/healthz          # {"status":"ok"}
```

Con `restart: unless-stopped` el contenedor vuelve solo tras un reinicio del servidor o una
caída, salvo que lo pares tú a mano.

## 6. Publicarlo con HTTPS

El contenedor habla HTTP plano en el `8082`; el HTTPS lo termina tu proxy inverso (nginx, Plesk,
Traefik, Caddy...). Lo único que hace falta es un `proxy_pass` al puerto del contenedor
reenviando las cabeceras estándar:

```nginx
location / {
    proxy_pass http://127.0.0.1:8082;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    client_max_body_size 50M;   # adjuntos de los mensajes prioritarios
}
```

Detalles que importan:

- **`X-Forwarded-Proto` no es opcional**: uvicorn arranca con `--proxy-headers`, y sin esa
  cabecera genera enlaces `http://` dentro de `/admin` que el navegador bloquea en una página
  HTTPS.
- **`client_max_body_size`**: el valor por defecto de nginx (1 MB) rechaza avatares y adjuntos.
- Certificado: Let's Encrypt (`certbot`, o el botón de SSL de tu panel) — Android no acepta
  certificados autofirmados sin instalarlos a mano en cada móvil.
- Si el servidor está detrás de otro (LAN, túnel VPN), el `proxy_pass` apunta a su IP interna en
  vez de a `127.0.0.1`.

## 7. Instalar la app en los móviles

1. Abre `https://tudominio.com/` en el móvil: la landing tiene el botón de descarga del APK
   (también sirve el enlace directo `https://tudominio.com/lokate.apk`).
2. Android pedirá permitir "instalar apps desconocidas" para el navegador — es normal al
   instalar fuera de Play Store.
3. Entra con el usuario maestro del `.env`, crea el grupo familiar y reparte el código de
   invitación al resto.
4. **Acepta todos los permisos que pide la app**, especialmente ubicación "Permitir todo el
   tiempo" y desactivar la optimización de batería: sin ellos Android mata el servicio en
   segundo plano y las ubicaciones dejan de llegar con la app cerrada.

Para publicar versiones nuevas basta con sobrescribir `apk/lokate.apk` en el servidor — sin
rebuild ni reinicio, esa carpeta se sirve directa del disco. El interruptor de "hay
actualización" está en Ajustes de la app (solo admins) y crea/borra el archivo `apk/update_si`.

## 8. Copias de seguridad

Desde `/admin` → Backups (o desde el panel nativo de la app) puedes crear, descargar, restaurar
y borrar copias. Cada copia es un `.tar.gz` de todo `/data` (base de datos + avatares +
adjuntos) que queda en `/opt/lokate/backups/`, accesible también por SSH/WinSCP. Conviene
llevarse una copia fuera del servidor de vez en cuando — si se pierde el disco, se pierde todo.

---

## ✅ Checklist final de pruebas

Recórrelo entero tras el primer despliegue (y al menos las dos primeras secciones tras cada
actualización). Necesitas **dos móviles** con cuentas distintas en el mismo grupo para probar
todo lo que implica a más de una persona.

### Servidor

- [ ] `docker ps` muestra `lokate-api` como `Up` (y `healthy` tras ~30 s).
- [ ] `curl http://localhost:8082/healthz` → `{"status":"ok"}`.
- [ ] `https://tudominio.com/healthz` responde lo mismo **desde el móvil con la WiFi apagada**
      (datos móviles). Probarlo desde tu propia red puede dar un 502 falso si el router no hace
      NAT loopback: eso no es un fallo del despliegue.
- [ ] `https://tudominio.com/` carga la landing, con candado de HTTPS válido.
- [ ] `https://tudominio.com/admin` deja entrar con `ADMIN_USERNAME`/`ADMIN_PASSWORD`, y el CSS
      se ve bien (si se ve "en crudo", falta `X-Forwarded-Proto` en el proxy).
- [ ] `docker compose logs --tail 100 lokate-api` no muestra trazas de error repitiéndose.

### Cuenta y grupo

- [ ] Inicias sesión con el usuario maestro del `.env`.
- [ ] Creas el grupo familiar y el segundo móvil entra con el código de invitación.
- [ ] Ambos os veis en la pestaña **Gente**, con avatar, batería y hora de última actualización.

### Ubicación

- [ ] Los dos aparecéis en el mapa con vuestra posición real.
- [ ] Cierras la app del todo en un móvil, te desplazas unos minutos y su posición se actualiza
      igualmente (si no: permisos de segundo plano / optimización de batería del fabricante).
- [ ] **Detalle de un miembro → Actualizar** trae una posición fresca en menos de ~20 s.
- [ ] **Historial** dibuja el recorrido del día sobre el mapa.

### Notificaciones (necesitan Firebase bien configurado)

- [ ] **Ajustes → Enviar notificación de prueba** → eliges a **una persona**: le llega solo a
      ella.
- [ ] La misma opción → **Enviar a todo el grupo**: pide confirmación, y al aceptar llega a
      todos los móviles del grupo (incluido el tuyo).
- [ ] Creas una zona, activas sus avisos de entrada y salida, y al cruzar el borde llega el
      aviso (recuerda: los avisos de una zona nueva vienen **desactivados** a propósito).
- [ ] **Hacer sonar el dispositivo** suena a volumen de alarma en el otro móvil **con el modo
      silencio activado**.
- [ ] **Mensaje prioritario** con foto adjunta llega, suena igual, y la imagen se ve y se puede
      guardar.

Si no llega ninguna notificación: revisa que `firebase-credentials.json` sea la clave de
servicio del **mismo** proyecto Firebase que el `google-services.json` con el que compilaste el
APK, y que esté montado como archivo (no como carpeta vacía).

### Administración y actualizaciones

- [ ] El candado 🔒 del mapa (solo admins) abre el panel nativo y el **Resumen** muestra datos.
- [ ] Creas una copia de seguridad, la descargas y aparece también en `/opt/lokate/backups/`.
- [ ] Sobrescribes `apk/lokate.apk` con una versión de `versionCode` mayor, activas el aviso de
      actualización en Ajustes y el otro móvil ofrece actualizar e instala correctamente.

### Resistencia

- [ ] `docker compose restart lokate-api` y la app sigue funcionando sin volver a iniciar sesión.
- [ ] Reinicias el servidor entero (`reboot`) y el contenedor vuelve solo.
- [ ] Reinicias un móvil y la ubicación se sigue compartiendo sin abrir la app a mano.
