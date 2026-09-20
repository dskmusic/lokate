import logging
import os
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, status
from fastapi.responses import FileResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from . import models
from .admin import register_admin
from .auth import get_current_admin_user, hash_password
from .database import Base, SessionLocal, engine
from .i18n import LOCALE_COOKIE, detect_locale, set_current_locale
from .routers import admin_api, auth, groups, locations, messages, zones

# uvicorn solo configura SUS loggers ("uvicorn", "uvicorn.access"...): los nuestros quedan sin
# handler y caen en el de último recurso de Python, que descarta todo lo que no llegue a
# WARNING. Sin esta línea, los INFO de envío de push (quién recibe y quién no) no aparecerían
# nunca en "docker compose logs".
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

Base.metadata.create_all(bind=engine)


def _add_missing_columns() -> None:
    """create_all solo crea tablas nuevas: las columnas añadidas después a una tabla que ya
    existe hay que meterlas a mano, o la API revienta contra una BD de una versión anterior.
    ponytail: ALTER directo en vez de Alembic — con una sola BD SQLite no compensa."""
    if not engine.url.drivername.startswith("sqlite"):
        return
    with engine.begin() as conn:
        columns = {row[1] for row in conn.exec_driver_sql("PRAGMA table_info(users)")}
        for column in ("location_frequency", "config_issues", "zone_channel_id", "hidden_group_ids"):
            if column not in columns:
                conn.exec_driver_sql(f"ALTER TABLE users ADD COLUMN {column} VARCHAR")

        zone_columns = {row[1] for row in conn.exec_driver_sql("PRAGMA table_info(zones)")}
        if "watched_ids" not in zone_columns:
            conn.exec_driver_sql("ALTER TABLE zones ADD COLUMN watched_ids VARCHAR")


_add_missing_columns()

Path("/data/avatars").mkdir(parents=True, exist_ok=True)
Path("/data/attachments").mkdir(parents=True, exist_ok=True)


def _seed_master_admin() -> None:
    """Garantiza que siempre exista un admin principal para poder arrancar (crear el primer grupo, etc.)."""
    username = os.getenv("MASTER_ADMIN_USERNAME", "dskmusic")
    password = os.getenv("MASTER_ADMIN_PASSWORD", "orionM45@")

    db = SessionLocal()
    try:
        existing = db.query(models.User).filter(models.User.username == username).first()
        if existing is None:
            db.add(
                models.User(
                    username=username,
                    password_hash=hash_password(password),
                    display_name="Admin",
                    is_admin=True,
                ),
            )
            db.commit()
        elif not existing.is_admin:
            existing.is_admin = True
            db.commit()
    finally:
        db.close()


_seed_master_admin()

app = FastAPI(title="Lokate by DSK API", version="1.0.0")


@app.middleware("http")
async def locale_middleware(request, call_next):
    """Idioma del panel de admin: cookie del usuario -> Accept-Language del navegador -> español."""
    cookie_lang = request.cookies.get(LOCALE_COOKIE)
    locale = cookie_lang if cookie_lang else detect_locale(request.headers.get("accept-language"))
    set_current_locale(locale)
    request.state.locale = locale
    return await call_next(request)


@app.middleware("http")
async def admin_no_cache_middleware(request, call_next):
    """Las páginas del panel de admin no mandan Cache-Control por defecto, así que el
    navegador las cachea heurísticamente sin ni siquiera preguntar al servidor — cada vez que
    se despliega un cambio, quien ya había abierto el panel sigue viendo la versión vieja
    hasta que limpie caché a mano. "no-store" (más estricto que "no-cache": ni se guarda,
    ni se revalida) porque además es contenido con sesión/datos que cambian en cada request,
    no algo que tenga sentido cachear nunca."""
    response = await call_next(request)
    if request.url.path.startswith("/admin"):
        response.headers["Cache-Control"] = "no-store"
    return response


app.include_router(auth.router)
app.include_router(groups.router)
app.include_router(locations.router)
app.include_router(zones.router)
app.include_router(messages.router)
app.include_router(admin_api.router)

app.mount("/avatars", StaticFiles(directory="/data/avatars"), name="avatars")
app.mount("/attachments", StaticFiles(directory="/data/attachments"), name="attachments")


# sqladmin se monta como sub-aplicación en "/admin" — Starlette solo redirige automáticamente
# de sin-barra a con-barra para rutas normales, no para el propio punto de montaje: pedir
# "/admin" a secas (sin la barra) no coincide con nada y da 404, solo funciona "/admin/". Hace
# falta esta ruta explícita, registrada ANTES del mount para que gane la coincidencia exacta.
@app.get("/admin", include_in_schema=False)
def admin_redirect_trailing_slash():
    return RedirectResponse(url="/admin/")


register_admin(app)


@app.get("/healthz")
def healthz():
    return {"status": "ok"}


# Bandera simple para avisar a la app de que hay una versión nueva: no compara números de
# versión, solo mira si el archivo "apk/update_si" existe. Para publicar un aviso, basta con
# crear ese archivo (el contenido da igual) junto al .apk; para retirarlo, renombrarlo o
# borrarlo. Vive en la misma carpeta "apk/" ya montada como volumen — ni esto ni la propia
# actualización necesitan nunca rebuild ni reinicio del contenedor.
@app.get("/update-check")
def update_check():
    return {"update_available": Path("apk/update_si").exists()}


class UpdateFlagRequest(BaseModel):
    enabled: bool


# Mismo mecanismo que arriba pero para ENCENDER/APAGAR la bandera desde la propia app (Ajustes,
# solo admins) en vez de tener que entrar por SSH cada vez.
@app.post("/update-flag")
def set_update_flag(body: UpdateFlagRequest, admin: models.User = Depends(get_current_admin_user)):
    flag_path = Path("apk/update_si")
    try:
        if body.enabled:
            flag_path.touch()
        else:
            flag_path.unlink(missing_ok=True)
    except OSError as exc:
        # Lo más probable: la carpeta "apk/" está montada de solo lectura en docker-compose.yml.
        raise HTTPException(status.HTTP_500_INTERNAL_SERVER_ERROR, f"No se pudo escribir en apk/: {exc}")
    return {"update_available": flag_path.exists()}


# Ruta explícita para el APK (antes del catch-all): StaticFiles no manda Cache-Control por
# defecto, así que los navegadores lo cachean heurísticamente sin ni siquiera preguntar al
# servidor — cada vez que se sube una build nueva, quien lo vuelve a descargar sigue
# recibiendo en silencio la versión vieja ya cacheada. "no-cache" obliga a revalidar en cada
# descarga (sigue pudiendo devolver 304 si de verdad no cambió, pero nunca sirve algo stale).
# filename= es igual de importante: sin un Content-Disposition explícito, Chrome no se fía
# del Content-Type y "huele" el contenido — un APK es en el fondo un ZIP, así que lo detecta
# como tal y lo guarda como "lokate.apk.zip" en vez de "lokate.apk".
# Sirve desde "apk/" (carpeta montada entera en docker-compose.yml), no desde un archivo
# suelto montado directo — así reemplazar el .apk en el servidor se nota sin reiniciar nada.
@app.get("/lokate.apk")
def download_apk():
    return FileResponse(
        "apk/lokate.apk",
        media_type="application/vnd.android.package-archive",
        filename="lokate.apk",
        headers={"Cache-Control": "no-cache"},
    )


# Catch-all al final: sirve la web de aterrizaje (/) y robots.txt. Tiene que ir después de
# TODOS los routers/mounts/rutas anteriores para no taparlos (Starlette prueba las rutas en
# el orden en que se registran, y este mount coincide con cualquier ruta que empiece por "/").
app.mount("/", StaticFiles(directory="web_inicial", html=True), name="web")
