<div align="center">

# 📍 Lokate by DSK

**Una app de localización familiar autoalojada, al estilo Life360 — tus datos, tu servidor.**

[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=flat&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Python](https://img.shields.io/badge/Python-3.11+-3776AB?style=flat&logo=python&logoColor=white)](https://www.python.org)
[![FastAPI](https://img.shields.io/badge/FastAPI-009688?style=flat&logo=fastapi&logoColor=white)](https://fastapi.tiangolo.com)
[![SQLite](https://img.shields.io/badge/SQLite-07405E?style=flat&logo=sqlite&logoColor=white)](https://www.sqlite.org)
[![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat&logo=docker&logoColor=white)](https://www.docker.com)
[![Firebase](https://img.shields.io/badge/Firebase%20Cloud%20Messaging-FFCA28?style=flat&logo=firebase&logoColor=black)](https://firebase.google.com)

[English ↓](README.md) · [Funcionalidades](#-funcionalidades) · [Arquitectura](#-arquitectura) · [Primeros pasos](#-primeros-pasos) · [Documentación](#-documentación)

</div>

---

Lokate es una alternativa autoalojada, en dos partes, a las apps comerciales de localización
familiar: una **app Android** nativa y un **backend FastAPI** que gestionas tú mismo, en tu propio
servidor. Ninguna empresa externa ve el historial de ubicaciones de tu familia.

<img width="1174" height="659" alt="collage" src="https://github.com/user-attachments/assets/6bb9ca0c-4784-4ed0-b021-83fc4420726f" />

## ✨ Funcionalidades

**Ubicación en vivo y mapa**
- Mapa familiar en tiempo real (OpenStreetMap vía osmdroid — sin clave de API, sin coste), con los
  marcadores separándose automáticamente cuando dos personas están en el mismo sitio.
- Pestaña **Gente**: foto, batería, estado de carga, red WiFi conectada y última actualización de
  cada miembro del grupo — toca la fila para centrar el mapa, la foto para verla en grande, o el
  icono de flecha para ir directamente a su ficha de detalle.
- Historial de ubicaciones **por día y por horas** (el día entero por defecto, o el tramo horario
  que elijas), con la ruta a pantalla completa, distancia total y distancia de lo que se ve en
  pantalla, y copiar coordenadas o abrir cualquier punto en Google Maps.
- **Guardar o compartir la imagen del recorrido** desde el propio historial — a Descargas o por el
  diálogo del sistema — con el nombre, la fecha (y las horas, si las acotaste) y la distancia
  recorrida al pie; y un botón para alternar entre el mapa y la lista de puntos, de la más reciente
  a la más antigua.
- Estilos de mapa en el propio mapa (botón de capas): estándar, satélite, oscuro, sin conexión y
  sin conexión oscuro.
- **Seguir en vivo** a cualquier miembro del grupo: el mapa salta a esa persona con el zoom por
  defecto de Ajustes y se queda centrado en ella en cada actualización.
- Zoom inicial configurable, y el mapa recuerda dónde lo dejaste al cambiar de pestaña.
- Buscador de direcciones y coordenadas al crear o editar zonas.
- **Modo prueba** para administradores: arrastra un marcador dentro o fuera de una zona para
  comprobar los avisos de entrada/salida sin moverte de casa.

**Mapas sin conexión**
- Mapas vectoriales de **Mapsforge** dibujados en el propio móvil: el mapa sigue funcionando sin
  cobertura ni datos, con etiquetas, calles y zoom de verdad (no capturas de pantalla).
- Catálogo oficial completo con más de 400 zonas — continentes, países y subdivisiones de los
  países grandes — más las comunidades autónomas españolas y Canarias por separado.
- Descarga bajo demanda desde Ajustes, con buscador (sin tildes, por nombre en tu idioma, en
  inglés o por continente), **tamaño exacto consultado al servidor antes de confirmar**, barra de
  progreso y cancelación. La primera vez te sugiere la zona donde estás.
- Gestión de lo descargado: qué zonas hay, cuánto ocupa cada una y borrado con confirmación.
- **Cambio automático**: si miras una zona que no tienes descargada (un familiar en otro país), el
  mapa pasa solo al mapa de internet y avisa; al volver a una zona descargada, vuelve al mapa sin
  conexión y avisa igual.
- Los archivos se bajan directamente del catálogo público de Mapsforge: tu servidor de Lokate no
  interviene ni almacena nada.

**Zonas y avisos**
- Zonas guardadas (geovallas) con vista previa en el mapa que se ajusta sola al radio mientras lo
  cambias.
- Geofencing calculado en el servidor — la entrada/salida se detecta de forma centralizada a partir
  de cada ubicación enviada, sin depender de los límites de geovallas del sistema operativo ni de que
  algún gestor de batería agresivo mate el servicio en segundo plano (un problema real en
  Xiaomi/Samsung/Huawei).
- Preferencias de aviso de entrada/salida por zona y por miembro.
- **Miembros vigilados por zona**: al crear o editar una zona eliges de quién quieres que te avise
  (por defecto, de todo el grupo) — así una zona puede avisarte solo de una persona y otra de otra.
  En la lista de zonas, los avatares del borde inferior enseñan de un vistazo a quién vigila cada una.

**Funciones de seguridad**
- **Hacer sonar el dispositivo** y **mensajes prioritarios (de emergencia)** — texto más una foto,
  vídeo o archivo opcional — siempre suenan con el volumen de alarma del sistema y vibración fuerte,
  ignorando el modo silencio o no molestar del destinatario. Son los dos únicos tipos de notificación
  que se comportan así; el resto respeta los ajustes de sonido propios del usuario.
- Visor de medios integrado para los mensajes prioritarios recibidos, con opción de guardar (siempre
  en Descargas) o compartir.

**Acabado**
- Tema claro / oscuro / AMOLED (negro puro) / automático, con color de acento personalizable; las
  barras de estado y de navegación de Android se tiñen con el tema elegido.
- Español / inglés, automático o elegido a mano en Ajustes.
- Comprobador de permisos en Ajustes: dice qué le falta a ese móvil (ubicación en segundo plano,
  notificaciones, acceso a No molestar para poder sonar en silencio…) y lo vuelve a pedir aunque lo
  hubieras omitido al instalar.
- Actualizador integrado — busca e instala el último APK directamente desde tu propio servidor.
- **Cambio de grupo activo (admin)**: los administradores pueden saltar a cualquier grupo del
  servidor desde un selector, sin código de invitación; solo llegan los avisos del grupo activo.
  Cada grupo lleva además una casilla **Visible**: desmarcada, el administrador entra sin aparecer
  en la lista de miembros ni en el mapa de los demás, y sus entradas y salidas de zona no les avisan.
- Panel de administración en la app: usuarios, grupos, dashboard y un **gestor de almacenamiento
  del servidor** (base de datos, avatares, adjuntos, APK y copias de seguridad) con vista previa y
  borrado de archivos.
- Panel de administración web completo (usuarios, grupos, zonas, historial de ubicaciones por día, y
  un dashboard visual) con su propio tema claro/oscuro e interfaz en español/inglés, detectado
  automáticamente y cambiable a mano.

## 🏗 Arquitectura

```
Lokate by DSK/
├── android/   App nativa Kotlin + Jetpack Compose (MVVM, sin framework de DI)
├── backend/   API REST FastAPI + SQLite, notificaciones vía Firebase Cloud Messaging, en Docker
└── docs/      Documentación detallada de cada parte y la guía de despliegue
```

| | |
|---|---|
| **Android** | Kotlin, Jetpack Compose, Material 3, Room, DataStore, Retrofit + OkHttp, Coil, osmdroid + Mapsforge (mapas sin conexión), WorkManager, EncryptedSharedPreferences |
| **Backend** | Python, FastAPI, SQLAlchemy, Pydantic, SQLite, PyJWT, bcrypt, SQLAdmin, Firebase Admin SDK, Docker |

Las decisiones de diseño clave — y su porqué — están documentadas en [`docs/ANDROID.md`](docs/ANDROID.md)
y [`docs/BACKEND.md`](docs/BACKEND.md): geofencing en el servidor, autenticación JWT, login solo con
usuario, mensajes FCM solo de datos (para que la lógica propia de alarma/sonido de la app se ejecute
siempre en vez de la notificación por defecto del sistema), y más.

## 🚀 Primeros pasos

1. **Backend** — consulta [`docs/BACKEND.md`](docs/BACKEND.md) para desarrollo local, y
   [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) para desplegarlo en cualquier servidor o VPS con Docker.
2. **App Android** — consulta [`docs/ANDROID.md`](docs/ANDROID.md) para los requisitos de compilación
   (necesitarás tu propio proyecto de Firebase para las notificaciones push).

## 📖 Documentación

- [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) — guía de despliegue para cualquier servidor/VPS, con checklist final de pruebas
- [`docs/BACKEND.md`](docs/BACKEND.md) — arquitectura del backend, desarrollo local y referencia de la API
- [`docs/ANDROID.md`](docs/ANDROID.md) — arquitectura de la app Android y estructura del proyecto

## 🗺️ Posibles mejoras del mapa

Un listado breve de cosas a las que podría crecer el mapa OpenStreetMap/osmdroid actual, si algún día
hiciera falta — nada de esto está implementado hoy (la vista satélite, el mapa oscuro y los mapas sin
conexión sí lo están, ver [Funcionalidades](#-funcionalidades)):

- **Capa de tráfico** — hace falta un proveedor de teselas con tráfico en vivo (las teselas OSM por
  defecto de osmdroid no lo traen); servicios como TomTom o Mapbox lo ofrecen, con coste/clave de API.
- **Reanudar descargas de mapas** — ahora una descarga cortada empieza de cero; con peticiones por
  rangos HTTP podría continuar donde se quedó (solo merece la pena para países de varios GB).
- **Navegación paso a paso** — fuera del alcance de una app de localización familiar, pero osmdroid
  puede integrarse con motores de rutas como GraphHopper u OSRM si algún día se quisiera.

## Licencia

Todavía sin decidir — añade un fichero `LICENSE` con los términos que quieras antes de hacer público
el repositorio (p. ej. [MIT](https://choosealicense.com/licenses/mit/) es el permisivo habitual por
defecto para un proyecto así, pero es tu decisión).

---

<div align="center">

Hecho con ❤️ por [DSK](https://www.dskmusic.com/dsk_dev_redirect.php)

</div>
