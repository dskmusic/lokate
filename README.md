<div align="center">

# 📍 Lokate by DSK

**A self-hosted, Life360-style family location app — your data, your server.**

[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=flat&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Python](https://img.shields.io/badge/Python-3.11+-3776AB?style=flat&logo=python&logoColor=white)](https://www.python.org)
[![FastAPI](https://img.shields.io/badge/FastAPI-009688?style=flat&logo=fastapi&logoColor=white)](https://fastapi.tiangolo.com)
[![SQLite](https://img.shields.io/badge/SQLite-07405E?style=flat&logo=sqlite&logoColor=white)](https://www.sqlite.org)
[![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat&logo=docker&logoColor=white)](https://www.docker.com)
[![Firebase](https://img.shields.io/badge/Firebase%20Cloud%20Messaging-FFCA28?style=flat&logo=firebase&logoColor=black)](https://firebase.google.com)

[Español ↓](README.es.md) · [Features](#-features) · [Architecture](#-architecture) · [Getting started](#-getting-started) · [Docs](#-documentation)

</div>

---

Lokate is a two-part, self-hosted alternative to commercial family-location apps: a native
**Android app** and a **FastAPI backend** you run yourself, on your own server. No third-party
company sees your family's location history.

<img width="1174" height="659" alt="collage" src="https://github.com/user-attachments/assets/d6df191e-0db0-4667-948e-a17bbea0c583" />

## ✨ Features

**Live location & map**
- Real-time family map (OpenStreetMap via osmdroid — no API key, no cost), with overlapping
  markers automatically spread apart when two people are in the same spot.
- **People** tab: avatar, battery %, charging state, connected Wi-Fi network, and last-update time
  for every group member — tap to center the map, tap the avatar for a full-size preview, or jump
  straight to their detail screen.
- Location history per day, with the route drawn on the map, a synced list of points, the total
  distance covered, and copy-coordinates / open-in-Google-Maps on any point.
- Map styles right on the map (layers button): standard, satellite, dark, offline, and offline dark.
- **Follow live**: the map jumps to any group member at the default zoom from Settings and keeps
  re-centering on them with every update.
- Configurable initial zoom, and the map remembers where you left it when switching tabs.
- Address and coordinate search when creating or editing zones.
- **Test mode** for admins: drag a marker in or out of a zone to check enter/exit alerts without
  leaving the house.

**Offline maps**
- Vector maps rendered on the phone with **Mapsforge**: the map keeps working with no coverage and
  no data, with real labels, streets and zoom (not screenshots).
- The full official catalogue — 400+ areas covering continents, countries and subdivisions of the
  big ones — plus each Spanish region and the Canary Islands separately.
- On-demand downloads from Settings, with search (accent-insensitive, by localized name, English
  name or continent), the **exact size fetched from the server before you confirm**, a progress bar
  and cancel. The first time it suggests the area you are in.
- Manage what you downloaded: which areas, how much each one takes, and delete with confirmation.
- **Automatic switching**: look at an area you have not downloaded (a relative in another country)
  and the map falls back to the online map with a heads-up toast; come back to a downloaded area and
  it switches back, again with a toast.
- Files are downloaded straight from Mapsforge's public catalogue — your Lokate server is not
  involved and stores nothing.

**Zones & alerts**
- Saved zones (geofences) with a live map preview that auto-fits to the radius as you adjust it.
- Server-side geofencing — entry/exit is detected centrally from location pings, so it isn't at the
  mercy of Android's per-OS geofencing limits or aggressive battery managers killing background
  services (a real problem on Xiaomi/Samsung/Huawei).
- Per-zone, per-member enter/exit notification preferences.

**Safety features**
- **Ring device** and **priority (emergency) messages** — text plus an optional photo, video, or
  file — always ring at system-alarm volume with strong vibration, ignoring the recipient's silent
  mode or Do Not Disturb. These are the only two notification types that behave this way; everything
  else respects the user's own sound settings.
- Built-in media viewer for received priority messages, with save (always to Downloads) and share.

**Polish**
- Light / dark / AMOLED (true black) / automatic theming, with a custom accent color.
- Spanish / English, automatic or manually chosen in Settings.
- Permission checker in Settings: tells you what this phone is missing (background location,
  notifications, Do Not Disturb access so it can ring while silenced…) and asks again, even if you
  skipped it at install time.
- In-app updater — checks and installs the latest APK straight from your own server.
- In-app admin panel: users, groups, dashboard, and a **server storage manager** (database,
  avatars, attachments, APK and backups) with file preview and deletion.
- A full web **admin panel** (users, groups, zones, location history by day, and a visual dashboard)
  with its own light/dark theme and Spanish/English UI, auto-detected and switchable.

## 🏗 Architecture

```
Lokate by DSK/
├── android/   Native Kotlin + Jetpack Compose app (MVVM, no DI framework)
├── backend/   FastAPI + SQLite REST API, push via Firebase Cloud Messaging, Dockerized
└── docs/      Detailed per-part docs and the deployment guide
```

| | |
|---|---|
| **Android** | Kotlin, Jetpack Compose, Material 3, Room, DataStore, Retrofit + OkHttp, Coil, osmdroid + Mapsforge (offline maps), WorkManager, EncryptedSharedPreferences |
| **Backend** | Python, FastAPI, SQLAlchemy, Pydantic, SQLite, PyJWT, bcrypt, SQLAdmin, Firebase Admin SDK, Docker |

Key design calls — and why — are documented in [`docs/ANDROID.md`](docs/ANDROID.md) and
[`docs/BACKEND.md`](docs/BACKEND.md): server-side geofencing, JWT auth, username-only login,
data-only FCM messages (so the app's own alarm/ring logic always runs instead of the OS default),
and more.

## 🚀 Getting started

1. **Backend** — see [`docs/BACKEND.md`](docs/BACKEND.md) for local development, and
   [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) for deploying to any server or VPS with Docker.
2. **Android app** — see [`docs/ANDROID.md`](docs/ANDROID.md) for build requirements
   (you'll need your own Firebase project for push notifications).

## 📖 Documentation

- [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) — deployment guide for any server/VPS, plus a final testing checklist
- [`docs/BACKEND.md`](docs/BACKEND.md) — backend architecture, local dev, and API reference
- [`docs/ANDROID.md`](docs/ANDROID.md) — Android app architecture and project structure

## 🗺️ Possible map improvements

A short list of things the current OpenStreetMap/osmdroid setup *could* grow into, if ever needed —
none of these are implemented today (satellite view, the dark map and offline maps are, see
[Features](#-features)):

- **Traffic layer** — needs a tile provider that offers live traffic (osmdroid's default OSM tiles
  don't include it); services like TomTom or Mapbox offer this, at a cost/API-key.
- **Resumable map downloads** — an interrupted download currently restarts from scratch; HTTP range
  requests could pick up where it left off (only worth it for multi-GB countries).
- **Turn-by-turn navigation** — out of scope for a family-location app, but osmdroid can integrate
  with routing engines like GraphHopper or OSRM if ever wanted.

## License

Not yet decided — add a `LICENSE` file with the terms you want before making the repository public
(e.g. [MIT](https://choosealicense.com/licenses/mit/) is the common permissive default for a project
like this, but it's your call).

---

<div align="center">

Made with ❤️ by [DSK](https://www.dskmusic.com/dsk_dev_redirect.php)

</div>
