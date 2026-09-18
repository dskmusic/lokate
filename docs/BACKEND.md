# Lokate — manual del backend

Cómo funciona el servidor por dentro: qué hay, para qué sirve cada pieza y qué hace cada
endpoint. Para cómo desplegarlo, ver `MIGRACION_VPS.md`/`OPERACION_VPS.md` (fuera de este
directorio — llevan datos reales de infraestructura, no van en el repo). Para el manual de uso
de la app, ver `APP.md`.

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
| POST | `/auth/device` | Registrar/actualizar el token FCM del dispositivo |
| POST | `/auth/avatar` | Subir/cambiar avatar propio (JPEG/PNG/WEBP, máx. 5 MB) |

## Grupos (`/groups`)

Crear (solo admins, y solo si no perteneces ya a uno), unirse con código de invitación, ver el
grupo propio y sus miembros, salir del grupo, enviar notificación de prueba a todo el grupo.

## Ubicación (`/location`)

| Método | Ruta | Qué hace |
|---|---|---|
| POST | `/location/ping` | Sube una posición (lat/lng/precisión/batería/wifi) — dispara la
comprobación de entrada/salida de zonas (`app/geofence.py`) |
| GET | `/location/group/latest` | Última posición conocida de cada miembro del grupo |
| GET | `/location/history` | Historial de un usuario, por rango de fechas o últimas N horas |
| POST | `/location/ring/{user_id}` | Hace sonar el dispositivo de ese usuario (alarma+vibración
fuerte, salta el silencio) |
| POST | `/location/request-location/{user_id}` | Push **silencioso** que pide una ubicación
puntual fresca — el dispositivo la lee y la sube como un ping normal, sin sonido ni notificación
visible |

## Zonas (`/zones`)

CRUD de zonas del propio grupo, más preferencias de notificación por usuario+zona
(`/zones/notification-prefs`, `/zones/{id}/notification-prefs`). **Sin preferencia guardada, se
asume que el usuario NO quiere avisos** — hay que activarlos a mano, no vienen activados por
defecto. La histéresis de 30 m (`ZONE_EXIT_MARGIN_M` en `app/geofence.py`) evita avisos
repetidos por ruido de GPS cerca del borde.

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
de cualquier usuario, y copias de seguridad.

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
    admin.py                   # panel web (sqladmin) + vistas propias (backups, historial...)
    i18n.py                     # traducción del panel web
    routers/
      auth.py, groups.py, locations.py, zones.py, messages.py, admin_api.py
  docker-compose.yml
  Dockerfile
```
