# Lokate — manual del backend

Cómo funciona el servidor por dentro: qué hay, para qué sirve cada pieza y qué hace cada
endpoint. Para cómo desplegarlo, ver `DEPLOYMENT.md`. Para el manual de uso de la app, ver
`ANDROID.md`.

## Arquitectura

- **FastAPI** + **SQLAlchemy** + **SQLite** (`/data/lokate.db` dentro del contenedor).
- Un único contenedor Docker (`lokate-api`), sin colas ni caché externa — a propósito, es una
  app de uso familiar, no necesita más.
- Todo lo que persiste vive bajo `/data` (montado como volumen Docker con nombre
  `lokate_lokate-data`): la base de datos, `avatars/` y `attachments/`.
- Autenticación por **JWT** (`Authorization: Bearer <token>`) para la API de la app — el panel
  web `/admin` usa su propia sesión de cookie firmada, independiente.
- Notificaciones push vía **Firebase Cloud Messaging**, siempre como mensajes "solo datos" (sin
  bloque `notification`) para que el cliente decida él mismo qué hacer con cada tipo (sonido,
  vibración, si mostrar algo visible o no) en vez de dejar que Android muestre su notificación
  por defecto.

## Autenticación (`/auth`)

| Método | Ruta | Qué hace |
|---|---|---|
| POST | `/auth/register` | Crea cuenta (usuario, contraseña, nombre) → token |
| POST | `/auth/login` | → token |
| GET | `/auth/me` | Perfil del usuario autenticado |
| PUT | `/auth/me` | Cambiar nombre mostrado |
| POST | `/auth/device` | Registrar/actualizar el token FCM del dispositivo y la frecuencia de actualización elegida en él |
| POST | `/auth/avatar` | Subir/cambiar avatar propio (JPEG/PNG/WEBP, máx. 5 MB) |

## Grupos (`/groups`)

Crear (solo admins, y solo si no perteneces ya a uno), unirse con código de invitación, ver el
grupo propio y sus miembros, salir del grupo, y enviar notificación de prueba
(`POST /groups/test-notification`, solo admins) a miembros concretos —`{"user_ids": [...]}`— o a
todo el grupo si no se indica ninguno. Los ids pedidos se filtran contra el propio grupo: un
admin no puede usarlo para mandar push a usuarios de otros grupos.

## Ubicación (`/location`)

| Método | Ruta | Qué hace |
|---|---|---|
| POST | `/location/ping` | Sube una posición (lat/lng/precisión/batería/wifi y la frecuencia
de actualización que ese usuario tiene elegida en su app) — dispara la comprobación de
entrada/salida de zonas (`app/geofence.py`) |
| GET | `/location/group/latest` | Última posición conocida de cada miembro del grupo |
| GET | `/location/history` | Historial de un usuario, por rango de fechas o últimas N horas |
| POST | `/location/ring/{user_id}` | Hace sonar el dispositivo de ese usuario (alarma+vibración
fuerte, salta el silencio) |
| POST | `/location/stop-ring/{user_id}` | Push **silencioso** que para la alarma lanzada por `/ring` en ese dispositivo (botón "Detener" del diálogo de progreso) |
| POST | `/location/request-location/{user_id}` | Push **silencioso** que pide una ubicación
puntual fresca — el dispositivo la lee y la sube como un ping normal, sin sonido ni notificación
visible |

## Zonas (`/zones`)

CRUD de zonas del propio grupo, más preferencias de notificación por usuario+zona
(`/zones/notification-prefs`, `/zones/{id}/notification-prefs`). **Sin preferencia guardada, se
asume que el usuario NO quiere avisos** — hay que activarlos a mano, no vienen activados por
defecto. La histéresis de 30 m (`ZONE_EXIT_MARGIN_M` en `app/geofence.py`) evita avisos
repetidos por ruido de GPS cerca del borde.

### Modo prueba de zonas (solo admins)

`POST /admin-api/simulate/{user_id}` evalúa las zonas como si ese miembro estuviera en la
posición del cuerpo (`lat`, `lng`, `recipient_ids`) y manda los avisos de entrada/salida que
correspondan, **sin guardar nada en su historial**. Devuelve los textos disparados y a cuántos
llegaron, para que la app pueda decir qué ha pasado.

Los destinatarios los elige el admin a mano (se recuerdan en su móvil) en vez de respetar las
preferencias por zona de cada uno: probar es querer verlo sonar en un móvil concreto.

Mientras dura, la posición real de ese usuario se sigue guardando pero deja de evaluar zonas
(`geofence._simulated_until`, en memoria) — si no, su siguiente ping desharía la simulación al
instante y dispararía el aviso contrario. `POST /admin-api/simulate/stop` lo desactiva para todo
el grupo y recalcula el estado de zonas desde la última posición real **en silencio**: deshacer
un arrastre no debe avisar a nadie. `SIMULATION_TTL_S` (15 min) es la red de seguridad por si la
app del admin muere sin salir del modo; al caducar, el primer ping real recoloca el estado
también en silencio.

## Mensajes (`/messages`)

`POST /messages/emergency/{user_id}` — mensaje prioritario con adjunto opcional (foto/vídeo/
archivo), entregado forzando alarma+vibración en el destino sin mirar sus preferencias.

## Panel de administración — dos superficies, mismos datos

Todo lo de administración existe **dos veces**, deliberadamente no compartido entre sí (son dos
formas de autenticarse distintas: cookie de sesión vs. JWT, y compartir código entre ambas
complicaba más de lo que simplificaba):

- **Web** (`/admin`, montado con [sqladmin](https://aminalaee.dev/sqladmin/)): login con
  `ADMIN_USERNAME`/`ADMIN_PASSWORD` del `.env`. Instalable como PWA. Traducido al español por
  encima de la interfaz por defecto de sqladmin (`app/i18n.py`).
- **Nativa** (`/admin-api/*`, prefijo de todas las rutas): consumida por el panel dentro de la
  propia app Android — requiere JWT de un usuario con `is_admin=true`
  (`get_current_admin_user` en `app/auth.py`).

Ambas exponen las mismas acciones: dashboard con estadísticas y actividad, gestión de usuarios/
grupos/zonas (crear/editar/eliminar, cualquiera del sistema, no solo el propio grupo), historial
de cualquier usuario, explorador de archivos (avatares y adjuntos, con vista previa y borrado
individual o de la carpeta entera) y copias de seguridad.

### Explorador de archivos (`app/managed_files.py`)

Listar y borrar lo que ocupa espacio **fuera** de la base de datos. La lógica vive en un módulo
propio, como `backups.py`, porque la usan los dos paneles: el guardia de rutas es lo último que
conviene tener duplicado, una copia que se queda atrás es un borrado arbitrario en el servidor.

`MANAGED_DIRS` es la lista blanca: solo `avatars` y `attachments`. La BD, el APK, la web estática
y las copias de seguridad no se tocan desde aquí (las copias tienen su propia pantalla). El nombre
del archivo no puede contener `/` ni `\` (en Linux la barra invertida es un carácter normal, y sin
prohibirla el comportamiento cambiaría según el host) y además se comprueba que la ruta ya resuelta
siga colgando de la carpeta. Self-check: `python -m tests.test_managed_files` desde `backend/`.

Cada archivo lleva `kind` (`image`/`video`/`audio`/`other`, deducido por `mimetypes`) para que el
panel sepa si puede previsualizarlo, e `in_use`, que solo tiene sentido en `avatars`: los adjuntos
viajan dentro del push y no quedan referenciados en ninguna tabla — por eso se acumulan y nada los
borra solo. Al borrar un avatar en uso se pone a `NULL` el `users.avatar_url` que lo apuntaba, para
no dejar perfiles enlazando a un 404.

| Panel | Ruta |
|---|---|
| Nativo | `GET /admin-api/files/{folder}`, `DELETE /admin-api/files/{folder}/{name}`, `DELETE /admin-api/files/{folder}` (vacía la carpeta, devuelve `{"deleted": n}`) |
| Web | `GET /admin/files?folder=…`, `POST /admin/files/{folder}/delete` (nombre en el formulario), `POST /admin/files/{folder}/delete-all` |

### Copias de seguridad (`app/backups.py`)

Cada copia es un `.tar.gz` de todo `/data` (BD + avatares + adjuntos) guardado en `/app/backups`
(carpeta bind-mounted en el host, no un volumen con nombre — así también se puede acceder a
ellas directamente por WinSCP/SSH sin pasar por la API), más un `.json` al lado con su
descripción y fecha exacta. El id de cada copia es un UUID (no un timestamp) precisamente para
que dos copias creadas en el mismo segundo no puedan pisarse entre sí.

- **Crear**: comprime `/data` tal cual está en ese momento, sin parar el servicio.
- **Restaurar**: descomprime la copia elegida **sobre** `/data`, sobrescribiendo lo que hay.
  Riesgo aceptado y documentado: si justo en ese instante hay una escritura a medias en SQLite,
  podría quedar en un estado raro — para una app de tráfico bajo es un margen mínimo, y no se ha
  añadido parada de servicio para eliminarlo del todo (complicaría mucho la implementación para
  un caso muy poco probable).
- **Eliminar**: borra el `.tar.gz` y su `.json`.
- **Descargar**: sirve el `.tar.gz` tal cual (`Content-Disposition: attachment`).

El tamaño total de `/app/backups` también se refleja en el desglose de almacenamiento del
dashboard (`app/disk_usage.py`).

## Sistema de actualización de la app

- `GET /update-check` (público): `{"update_available": bool}` — mira si existe el archivo
  `apk/update_si` en el servidor.
- `POST /update-flag` (solo admins): crea o borra ese archivo — es el interruptor de "avisar de
  actualización a todos" que hay en Ajustes de la app.
- `GET /lokate.apk`: sirve el APK actual desde `apk/lokate.apk`, con `Cache-Control: no-cache` y
  el nombre de archivo correcto (evita que el navegador lo renombre a `.zip` por error).
- Subir una versión nueva es solo sobrescribir `apk/lokate.apk` en el servidor — sin rebuild ni
  reinicio del contenedor, esa carpeta está montada como carpeta real, no como archivo suelto
  (ver el comentario en `docker-compose.yml`).

## Landing / PWA (`web_inicial/`)

Página estática servida en `/`, más `manifest.json`, `sw.js` (service worker) y los iconos que
hacen instalable como PWA tanto la landing como el propio `/admin`. Se sirve directo desde
disco (bind mount), sin pasar por la imagen Docker — actualizar estos archivos no necesita
rebuild ni reinicio.

## Variables de entorno (`.env`)

| Variable | Para qué |
|---|---|
| `JWT_SECRET` | Firma los tokens de sesión — cambiarlo desloguea a todo el mundo |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | Login del panel web `/admin` |
| `MASTER_ADMIN_USERNAME` / `MASTER_ADMIN_PASSWORD` | Primer usuario admin, creado solo si no
existe ya al arrancar |
| `ACCESS_TOKEN_EXPIRE_DAYS` | Validez de los tokens de sesión de la app |
| `LOCATION_RETENTION_DAYS` | Cuánto se conserva el historial de ubicaciones por usuario |
| `FIREBASE_CREDENTIALS_PATH` | Ruta a la clave de servicio de Firebase (notificaciones push) |
| `DATABASE_URL` | No tocar salvo que cambies a otro motor de BD |

## Estructura de archivos relevante

```
backend/
  app/
    main.py            # arranque, montajes estáticos, /healthz, /lokate.apk, update-check/-flag
    models.py           # tablas SQLAlchemy
    schemas.py           # modelos Pydantic (requests/responses)
    auth.py              # hashing, JWT, dependencias get_current_user/get_current_admin_user
    geofence.py           # entrada/salida de zonas + histéresis
    push.py                # envío de notificaciones FCM
    disk_usage.py           # tamaño en disco por componente (dashboard)
    backups.py                # copias de seguridad
    managed_files.py          # archivos borrables (avatares/adjuntos) de los dos paneles
    admin.py                   # panel web (sqladmin) + vistas propias (backups, historial...)
    i18n.py                     # traducción del panel web
    routers/
      auth.py, groups.py, locations.py, zones.py, messages.py, admin_api.py
  docker-compose.yml
  Dockerfile
```
