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

## ✨ Funcionalidades

**Ubicación en vivo y mapa**
- Mapa familiar en tiempo real (OpenStreetMap vía osmdroid — sin clave de API, sin coste), con los
  marcadores separándose automáticamente cuando dos personas están en el mismo sitio.
- Pestaña **Gente**: foto, batería, estado de carga, red WiFi conectada y última actualización de
  cada miembro del grupo — toca la fila para centrar el mapa, la foto para verla en grande, o el
  icono de flecha para ir directamente a su ficha de detalle.
- Historial de ubicaciones por día, con la ruta pintada en el mapa y una lista de puntos sincronizada.

**Zonas y avisos**
- Zonas guardadas (geovallas) con vista previa en el mapa que se ajusta sola al radio mientras lo
  cambias.
- Geofencing calculado en el servidor — la entrada/salida se detecta de forma centralizada a partir
  de cada ubicación enviada, sin depender de los límites de geovallas del sistema operativo ni de que
  algún gestor de batería agresivo mate el servicio en segundo plano (un problema real en
  Xiaomi/Samsung/Huawei).
- Preferencias de aviso de entrada/salida por zona y por miembro.

**Funciones de seguridad**
- **Hacer sonar el dispositivo** y **mensajes prioritarios (de emergencia)** — texto más una foto,
  vídeo o archivo opcional — siempre suenan con el volumen de alarma del sistema y vibración fuerte,
  ignorando el modo silencio o no molestar del destinatario. Son los dos únicos tipos de notificación
  que se comportan así; el resto respeta los ajustes de sonido propios del usuario.
- Visor de medios integrado para los mensajes prioritarios recibidos, con opción de guardar (siempre
  en Descargas) o compartir.

**Acabado**
- Tema claro / oscuro / AMOLED (negro puro) / automático, con color de acento personalizable.
- Español / inglés, automático o elegido a mano en Ajustes.
- Actualizador integrado — busca e instala el último APK directamente desde tu propio servidor.
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
| **Android** | Kotlin, Jetpack Compose, Material 3, Room, DataStore, Retrofit + OkHttp, Coil, osmdroid, WorkManager, EncryptedSharedPreferences |
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
hiciera falta — nada de esto está implementado hoy:

- **Capa de tráfico** — hace falta un proveedor de teselas con tráfico en vivo (las teselas OSM por
  defecto de osmdroid no lo traen); servicios como TomTom o Mapbox lo ofrecen, con coste/clave de API.
- **Vista satélite/híbrida** — una fuente de teselas alternativa (p. ej. Esri World Imagery, gratis
  con atribución) como `TileSourceFactory` alterno junto al mapa de calles actual.
- **Teselas de mapa sin conexión** — osmdroid soporta paquetes `.mbtiles` predescargados para zonas
  con mala cobertura.
- **Teselas de mapa oscuro personalizadas** — una fuente de teselas con estilo oscuro propio (p. ej.
  CartoDB Dark Matter) en vez de solo oscurecer la interfaz de la app alrededor de un mapa claro.
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
