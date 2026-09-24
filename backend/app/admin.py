import json
import math
import os
import sqlite3
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi import FastAPI, Request
from markupsafe import Markup
from sqladmin import Admin, BaseView, ModelView, action, expose
from sqladmin.authentication import AuthenticationBackend
from starlette.responses import FileResponse, JSONResponse, RedirectResponse

from . import backups as backups_module
from . import login_guard, managed_files, models, push
from .auth import create_access_token
from .database import SessionLocal, engine
from .disk_usage import compute_disk_usage
from .i18n import DEFAULT_LOCALE, LOCALES, LOCALE_COOKIE, UI_DICT_ES, t
from .models import utcnow
from .routers.auth import ALLOWED_AVATAR_TYPES, AVATAR_DIR, MAX_AVATAR_BYTES

ADMIN_USERNAME = os.environ["ADMIN_USERNAME"]
ADMIN_PASSWORD = os.environ["ADMIN_PASSWORD"]

TEMPLATES_DIR = str(Path(__file__).parent / "admin_templates")


class AdminAuth(AuthenticationBackend):
    """Login con usuario/contraseña de admin, sesión guardada como cookie firmada (JWT)."""

    async def login(self, request: Request) -> bool:
        form = await request.form()
        if form.get("username") == ADMIN_USERNAME and form.get("password") == ADMIN_PASSWORD:
            request.session["token"] = create_access_token("admin")
            return True
        return False

    async def logout(self, request: Request) -> bool:
        request.session.clear()
        return True

    async def authenticate(self, request: Request) -> bool:
        return bool(request.session.get("token"))


def _zone_map_html(model: models.Zone, _attribute) -> Markup:
    """Mapa de solo lectura (mismo estilo que la app) para ver la zona en la ficha de detalle."""
    lat, lng, radius = model.lat, model.lng, model.radius_m
    delta_lat = max(radius * 3 / 111_320, 0.002)
    delta_lng = delta_lat / max(math.cos(math.radians(lat)), 0.2)
    bbox = f"{lng - delta_lng},{lat - delta_lat},{lng + delta_lng},{lat + delta_lat}"
    src = f"https://www.openstreetmap.org/export/embed.html?bbox={bbox}&marker={lat},{lng}"
    return Markup(
        f'{lat}<br/><iframe src="{src}" '
        'style="width:100%;max-width:600px;height:300px;border:1px solid #ccc;margin-top:8px;" loading="lazy"></iframe>',
    )


class DashboardView(BaseView):
    name = "Dashboard"
    icon = "fa-solid fa-gauge-high"

    @expose("/dashboard", identity="dashboard", methods=["GET"])
    async def index(self, request: Request):
        db = SessionLocal()
        try:
            now = utcnow()
            since_15m = now - timedelta(minutes=15)
            since_24h = now - timedelta(hours=24)
            since_7d = now - timedelta(days=7)

            stats = {
                "groups": db.query(models.Group).count(),
                "users": db.query(models.User).count(),
                "admins": db.query(models.User).filter(models.User.is_admin.is_(True)).count(),
                "online_now": (
                    db.query(models.LocationPing.user_id)
                    .filter(models.LocationPing.timestamp >= since_15m)
                    .distinct()
                    .count()
                ),
                "pings_24h": db.query(models.LocationPing).filter(models.LocationPing.timestamp >= since_24h).count(),
                "active_users_24h": (
                    db.query(models.LocationPing.user_id)
                    .filter(models.LocationPing.timestamp >= since_24h)
                    .distinct()
                    .count()
                ),
                "devices_with_push": db.query(models.User).filter(models.User.fcm_token.isnot(None)).count(),
            }

            # Serie de 7 días para el gráfico — se agrupa en Python (no en SQL) para no
            # depender de cómo formatea fechas el dialecto de turno (SQLite/Postgres/...).
            recent_pings = (
                db.query(models.LocationPing.timestamp)
                .filter(models.LocationPing.timestamp >= since_7d)
                .all()
            )
            day_counts = Counter(p.timestamp.date().isoformat() for p in recent_pings)
            max_day_count = max(day_counts.values(), default=0)
            activity_series = []
            for i in range(6, -1, -1):
                day = (now - timedelta(days=i)).date()
                count = day_counts.get(day.isoformat(), 0)
                activity_series.append({
                    "label": day.strftime("%d/%m"),
                    "count": count,
                    "pct": round(count / max_day_count * 100) if max_day_count else 0,
                })

            recent_users = db.query(models.User).order_by(models.User.created_at.desc()).limit(5).all()

            recent_activity = (
                db.query(models.LocationPing, models.User)
                .join(models.User, models.LocationPing.user_id == models.User.id)
                .order_by(models.LocationPing.timestamp.desc())
                .limit(8)
                .all()
            )
        finally:
            db.close()
        return await self.templates.TemplateResponse(
            request,
            "dashboard.html",
            {
                "title": t("dashboard"),
                "stats": stats,
                "recent_users": recent_users,
                "activity_series": activity_series,
                "recent_activity": recent_activity,
                "disk_usage": compute_disk_usage(),
            },
        )


class HistoryMapView(BaseView):
    """Historial de ubicaciones por usuario y por día, con la ruta pintada en el mapa
    (mismo estilo que la pantalla de Historial de la app)."""

    name = "Historial"
    icon = "fa-solid fa-route"

    @expose("/history-map", identity="history-map", methods=["GET"])
    async def index(self, request: Request):
        db = SessionLocal()
        try:
            users = db.query(models.User).order_by(models.User.display_name).all()
            user_id = request.query_params.get("user_id") or (users[0].id if users else None)
            date_str = request.query_params.get("date") or utcnow().strftime("%Y-%m-%d")
            points = []
            if user_id:
                try:
                    day = datetime.strptime(date_str, "%Y-%m-%d").replace(tzinfo=timezone.utc)
                except ValueError:
                    day = utcnow().replace(hour=0, minute=0, second=0, microsecond=0)
                    date_str = day.strftime("%Y-%m-%d")
                next_day = day + timedelta(days=1)
                rows = (
                    db.query(models.LocationPing)
                    .filter(
                        models.LocationPing.user_id == user_id,
                        models.LocationPing.timestamp >= day,
                        models.LocationPing.timestamp < next_day,
                    )
                    .order_by(models.LocationPing.timestamp.asc())
                    .all()
                )
                points = [{"lat": r.lat, "lng": r.lng, "ts": r.timestamp.isoformat()} for r in rows]
        finally:
            db.close()
        return await self.templates.TemplateResponse(
            request,
            "history_map.html",
            {
                "users": users,
                "selected_user_id": user_id,
                "selected_date": date_str,
                "points": points,
                "points_json": json.dumps(points),
            },
        )


# Quien manda desde el panel no es nadie del grupo: el aviso llega firmado así y no con el
# nombre de un usuario, para que quien lo recibe sepa de dónde sale.
ADMIN_SENDER_NAME = "Administración"


def _live_map_members(db) -> list[dict]:
    """Última posición conocida de cada usuario, en el formato que pinta el mapa.

    ponytail: una consulta por usuario, igual que /locations/group/latest — son un puñado de
    filas y el índice (user_id, timestamp) las devuelve directas. Con cientos de usuarios
    tocaría una sola consulta con window function; aquí sería complicarlo por nada.
    """
    members = []
    for user in db.query(models.User).order_by(models.User.display_name).all():
        latest = (
            db.query(models.LocationPing)
            .filter(models.LocationPing.user_id == user.id)
            .order_by(models.LocationPing.timestamp.desc())
            .first()
        )
        ts = latest.timestamp if latest else None
        if ts is not None and ts.tzinfo is None:
            # Guardadas sin huso (SQLite): son UTC, y el navegador necesita que se lo digan
            # para poder escribir "hace 5 minutos" en la hora de quien mira.
            ts = ts.replace(tzinfo=timezone.utc)
        members.append({
            "id": user.id,
            "name": user.display_name or user.username,
            "avatar": user.avatar_url or "",
            "group": user.group.name if user.group else "",
            "battery": user.battery_level,
            "charging": bool(user.is_charging),
            "lat": latest.lat if latest else None,
            "lng": latest.lng if latest else None,
            "accuracy": latest.accuracy if latest else None,
            "ts": ts.isoformat() if ts else None,
        })
    return members


class LiveMapView(BaseView):
    """Versión "light" de la app dentro del panel: dónde está cada uno y las dos acciones que
    de verdad se usan con prisa (hacer sonar y mensaje prioritario).

    La misma ruta devuelve JSON con ?json=1 para refrescar las posiciones sin recargar la
    página: es la misma consulta y evita un router aparte solo para eso.
    """

    name = "Mapa"
    icon = "fa-solid fa-map-location-dot"

    @expose("/live-map", identity="live-map", methods=["GET"])
    async def index(self, request: Request):
        db = SessionLocal()
        try:
            members = _live_map_members(db)
        finally:
            db.close()
        if request.query_params.get("json"):
            return JSONResponse(members)
        return await self.templates.TemplateResponse(
            request,
            "live_map.html",
            {"title": t("live_map_title"), "members": members},
        )


class LiveMapActionView(BaseView):
    """Ruta propia (oculta) por la misma razón que BackupsView explica arriba. Las tres acciones
    van por la misma ruta con un campo "action": una sola @expose por vista."""

    name = "Acción del mapa"

    def is_visible(self, request: Request) -> bool:
        return False

    @expose("/live-map/{user_id}/action", identity="live-map-action", methods=["POST"])
    async def act(self, request: Request):
        form = await request.form()
        action = str(form.get("action") or "")
        text = str(form.get("text") or "").strip()
        db = SessionLocal()
        try:
            target = db.query(models.User).filter(models.User.id == request.path_params["user_id"]).first()
            if target is None:
                return JSONResponse({"ok": False, "error": "usuario no encontrado"}, status_code=404)
            if action == "ring":
                push.send_to_users(
                    db, [target.id], "Lokate",
                    f"{ADMIN_SENDER_NAME} quiere localizar tu dispositivo",
                    {"type": "ring"},
                )
            elif action == "stop_ring":
                push.send_to_users(db, [target.id], "Lokate", "Parar alarma", {"type": "stop_ring"})
            elif action == "message":
                if not text:
                    return JSONResponse({"ok": False, "error": "mensaje vacío"}, status_code=400)
                # Mismo payload que /messages/emergency: la app ya sabe pintarlo (suena y vibra
                # pase lo que pase). Sin adjunto — para eso está la app.
                push.send_to_users(
                    db, [target.id], f"⚠️ {ADMIN_SENDER_NAME}", text,
                    {
                        "type": "emergency_message",
                        "from_user_id": "",
                        "from_display_name": ADMIN_SENDER_NAME,
                        "text": text,
                        "attachment_url": "",
                        "attachment_kind": "",
                    },
                )
            else:
                return JSONResponse({"ok": False, "error": "acción desconocida"}, status_code=400)
        finally:
            db.close()
        return JSONResponse({"ok": True})


class BackupsView(BaseView):
    """Copias de seguridad de los datos (BD + avatares + adjuntos): listar y crear. Descargar/
    restaurar/borrar van en vistas propias más abajo — sqladmin solo lleva bien UNA ruta
    @expose por BaseView para el enlace del menú; con varias en la misma clase, el identity
    que usa el menú se pisa entre sí y apunta a la ruta equivocada (visto en directo: acababa
    señalando a "backups-delete", que exige un backup_id que el enlace del menú no tiene)."""

    name = "Copias de seguridad"
    icon = "fa-solid fa-box-archive"

    @expose("/backups", identity="backups", methods=["GET", "POST"])
    async def index(self, request: Request):
        if request.method == "POST":
            form = await request.form()
            backups_module.create_backup(str(form.get("description") or ""))
            return RedirectResponse(request.url_for("admin:backups"), status_code=303)
        return await self.templates.TemplateResponse(
            request, "backups.html", {"backups": backups_module.list_backups()},
        )


class BackupDownloadView(BaseView):
    """Ruta propia (oculta) por la misma razón que BackupsView explica arriba."""

    name = "Descargar copia"

    def is_visible(self, request: Request) -> bool:
        return False

    @expose("/backups/{backup_id}/download", identity="backups-download", methods=["GET"])
    async def download(self, request: Request):
        tar_path = backups_module.get_tar_path(request.path_params["backup_id"])
        if not tar_path:
            return RedirectResponse(request.url_for("admin:backups"))
        return FileResponse(
            tar_path, media_type="application/gzip",
            filename=f"lokate-backup-{request.path_params['backup_id']}.tar.gz",
        )


class BackupRestoreView(BaseView):
    """Ruta propia (oculta) por la misma razón que BackupsView explica arriba."""

    name = "Restaurar copia"

    def is_visible(self, request: Request) -> bool:
        return False

    @expose("/backups/{backup_id}/restore", identity="backups-restore", methods=["POST"])
    async def restore(self, request: Request):
        backups_module.restore_backup(request.path_params["backup_id"])
        return RedirectResponse(request.url_for("admin:backups"), status_code=303)


class BackupDeleteView(BaseView):
    """Ruta propia (oculta) por la misma razón que BackupsView explica arriba."""

    name = "Borrar copia"

    def is_visible(self, request: Request) -> bool:
        return False

    @expose("/backups/{backup_id}/delete", identity="backups-delete", methods=["POST"])
    async def delete(self, request: Request):
        backups_module.delete_backup(request.path_params["backup_id"])
        return RedirectResponse(request.url_for("admin:backups"), status_code=303)


class FilesView(BaseView):
    """Explorador de los archivos borrables del servidor (avatares y adjuntos). El borrado, en
    vistas propias y ocultas, por la misma razón que explica BackupsView arriba."""

    name = "Archivos"
    icon = "fa-solid fa-folder-open"

    @expose("/files", identity="files", methods=["GET"])
    async def index(self, request: Request):
        folder = request.query_params.get("folder", "attachments")
        if folder not in managed_files.MANAGED_DIRS:
            folder = "attachments"
        db = SessionLocal()
        try:
            files = managed_files.list_files(db, folder)
        finally:
            db.close()
        return await self.templates.TemplateResponse(
            request, "files.html",
            {"folder": folder, "folders": list(managed_files.MANAGED_DIRS), "files": files},
        )


class FileDeleteView(BaseView):
    """Ruta propia (oculta) por la misma razón que BackupsView explica arriba."""

    name = "Borrar archivo"

    def is_visible(self, request: Request) -> bool:
        return False

    @expose("/files/{folder}/delete", identity="files-delete", methods=["POST"])
    async def delete(self, request: Request):
        folder = request.path_params["folder"]
        form = await request.form()
        name = str(form.get("name") or "")
        db = SessionLocal()
        try:
            # Nombre y carpeta se validan dentro; un formulario manipulado acaba en la misma
            # redirección que un borrado normal, sin tocar nada.
            try:
                managed_files.delete_file(db, folder, name)
            except (KeyError, managed_files.InvalidName):
                pass
        finally:
            db.close()
        return RedirectResponse(
            str(request.url_for("admin:files")) + f"?folder={folder}", status_code=303,
        )


class FileDeleteAllView(BaseView):
    """Ruta propia (oculta) por la misma razón que BackupsView explica arriba."""

    name = "Vaciar carpeta"

    def is_visible(self, request: Request) -> bool:
        return False

    @expose("/files/{folder}/delete-all", identity="files-delete-all", methods=["POST"])
    async def delete_all(self, request: Request):
        folder = request.path_params["folder"]
        db = SessionLocal()
        try:
            try:
                managed_files.delete_folder(db, folder)
            except KeyError:
                pass
        finally:
            db.close()
        return RedirectResponse(
            str(request.url_for("admin:files")) + f"?folder={folder}", status_code=303,
        )


def _vacuum_sqlite() -> None:
    """Devuelve al disco el espacio de las filas borradas. Dos vueltas de tuerca: SQLite no lo
    suelta por su cuenta, y en modo WAL ni siquiera encoge el fichero mientras alguien lo tenga
    abierto — de ahi el dispose(), que cierra el pool de conexiones de SQLAlchemy para quedarse
    a solas con la base. Si justo hay otra peticion escribiendo, se queda sin compactar y ya
    esta: las filas siguen borradas igual, que es lo que importa."""
    path = engine.url.database
    if not engine.url.drivername.startswith("sqlite") or not path:
        return
    engine.dispose()
    try:
        con = sqlite3.connect(path, timeout=15)
        try:
            con.execute("PRAGMA journal_mode=DELETE")
            con.execute("VACUUM")
            con.execute("PRAGMA journal_mode=WAL")
        finally:
            con.close()
    except sqlite3.Error:
        pass


class LocationsPurgeView(BaseView):
    """Ruta propia (oculta) por la misma razón que BackupsView explica arriba."""

    name = "Vaciar ubicaciones"

    def is_visible(self, request: Request) -> bool:
        return False

    @expose("/locations/purge", identity="locations-purge", methods=["POST"])
    async def purge(self, request: Request):
        """Borra TODAS las posiciones guardadas, para empezar a contar de cero. Usuarios,
        grupos y zonas se quedan intactos; el estado de zonas sí se borra porque se deduce de
        las posiciones (si no, nadie "entraría" en una zona en la que el servidor cree que ya
        está). El historial que cada móvil tenga cacheado en local no se toca."""
        db = SessionLocal()
        try:
            db.query(models.ZoneState).delete()
            db.query(models.LocationPing).delete()
            db.commit()
        finally:
            db.close()
        _vacuum_sqlite()
        return RedirectResponse(request.url_for("admin:dashboard"), status_code=303)


class UserAvatarUploadView(BaseView):
    """Subir avatar a un usuario desde el propio panel, sin tener que escribir la URL a mano.
    Solo se llega desde el icono 📷 de la lista de usuarios — no aparece como opción del menú."""

    name = "Subir avatar"
    icon = "fa-solid fa-camera"

    def is_visible(self, request: Request) -> bool:
        return False

    @expose("/user/avatar-upload/{pk}", identity="user-avatar-upload", methods=["GET", "POST"])
    async def upload(self, request: Request):
        pk = request.path_params["pk"]
        db = SessionLocal()
        try:
            target_user = db.get(models.User, pk)
            if target_user is None:
                return RedirectResponse(request.url_for("admin:list", identity="user"))

            error = None
            if request.method == "POST":
                form = await request.form()
                file = form.get("file")
                ext = ALLOWED_AVATAR_TYPES.get(getattr(file, "content_type", None)) if file else None
                if file is None or not getattr(file, "filename", ""):
                    error = t("choose_image_file")
                elif ext is None:
                    error = t("unsupported_format")
                else:
                    contents = await file.read(MAX_AVATAR_BYTES + 1)
                    if len(contents) > MAX_AVATAR_BYTES:
                        error = t("image_too_large")
                    else:
                        AVATAR_DIR.mkdir(parents=True, exist_ok=True)
                        filename = f"{target_user.id}.{ext}"
                        (AVATAR_DIR / filename).write_bytes(contents)
                        target_user.avatar_url = f"/avatars/{filename}"
                        db.commit()
                        return RedirectResponse(
                            request.url_for("admin:details", identity="user", pk=target_user.id),
                            status_code=303,
                        )

            return await self.templates.TemplateResponse(
                request, "user/avatar_upload.html", {"target_user": target_user, "error": error},
            )
        finally:
            db.close()


class SetLocaleView(BaseView):
    """Cambia el idioma del panel (cookie de un año). No aparece en el menú — se llega desde
    el selector de idioma del encabezado."""

    name = "Idioma"

    def is_visible(self, request: Request) -> bool:
        return False

    @expose("/set-locale", identity="set-locale", methods=["GET"])
    async def set_locale(self, request: Request) -> RedirectResponse:
        lang = request.query_params.get("lang", DEFAULT_LOCALE)
        if lang not in LOCALES:
            lang = DEFAULT_LOCALE
        next_url = request.query_params.get("next") or str(request.url_for("admin:dashboard"))
        response = RedirectResponse(next_url)
        response.set_cookie(LOCALE_COOKIE, lang, max_age=365 * 24 * 3600)
        return response


class GroupAdmin(ModelView, model=models.Group):
    # Sin el id (UUID crudo, no aporta nada de un vistazo) — recortado a lo justo para que
    # quepa en una pantalla de móvil sin scroll horizontal.
    column_list = [models.Group.name, models.Group.invite_code, models.Group.created_at]
    column_labels = {
        models.Group.name: "Nombre",
        models.Group.invite_code: "Código",
        models.Group.created_at: "Creado",
    }
    name_plural = "Grupos familiares"


def _avatar_html(model: models.User, _attribute) -> Markup:
    avatar = f'<img src="{model.avatar_url}" style="width:36px;height:36px;border-radius:50%;object-fit:cover;">' if model.avatar_url else "—"
    notify_url = f"/admin/user/action/send-test-notification?pks={model.id}"
    locate_url = f"/admin/user/action/locate-device?pks={model.id}"
    photo_url = f"/admin/user/avatar-upload/{model.id}"
    return Markup(
        f'{avatar} '
        f'<a href="{notify_url}" title="Enviar notificación" style="margin-left:8px;text-decoration:none;">🔔</a>'
        f'<a href="{locate_url}" title="Localizar (hacer sonar)" style="margin-left:6px;text-decoration:none;">📍</a>'
        f'<a href="{photo_url}" title="Cambiar foto" style="margin-left:6px;text-decoration:none;">📷</a>',
    )


def _resolve_target_ids(db, pks: list[str]) -> list[str]:
    """Sin pks seleccionados: todos los usuarios con token de notificaciones registrado."""
    if pks:
        return pks
    return [u.id for u in db.query(models.User).filter(models.User.fcm_token.isnot(None)).all()]


class UserAdmin(ModelView, model=models.User):
    # Recortado a lo esencial para que la lista quepa en móvil sin scroll horizontal — el
    # id (UUID crudo), la batería y el wifi ya se ven en el detalle de cada usuario, no hacen
    # falta de un vistazo en la lista. La foto ya trae pegados los accesos directos de
    # notificar/localizar/cambiar foto (ver _avatar_html).
    column_list = [
        models.User.avatar_url,
        models.User.display_name,
        models.User.username,
        models.User.is_admin,
    ]
    column_labels = {
        models.User.avatar_url: "Foto",
        models.User.display_name: "Nombre",
        models.User.username: "Usuario",
        models.User.is_admin: "Admin",
    }
    column_formatters = {models.User.avatar_url: _avatar_html}
    column_formatters_detail = {models.User.avatar_url: _avatar_html}
    column_searchable_list = [models.User.username, models.User.display_name]
    form_excluded_columns = [models.User.password_hash]
    name_plural = "Usuarios"

    @action(
        name="send_test_notification",
        label="Enviar notificación de prueba",
        confirmation_message=(
            "¿Enviar una notificación de prueba? Si no seleccionas ninguna fila se envía a "
            "TODOS los usuarios con token de notificaciones registrado."
        ),
        add_in_list=True,
        add_in_detail=True,
    )
    async def send_test_notification(self, request: Request) -> RedirectResponse:
        pks = [p for p in request.query_params.get("pks", "").split(",") if p]

        db = SessionLocal()
        try:
            push.send_to_users(
                db,
                user_ids=_resolve_target_ids(db, pks),
                title="Lokate — Prueba",
                body="Notificación de prueba enviada desde el panel de administración",
                data={"type": "test"},
            )
        finally:
            db.close()

        referer = request.headers.get("Referer")
        if referer:
            return RedirectResponse(referer)
        return RedirectResponse(request.url_for("admin:list", identity=self.identity))

    @action(
        name="locate_device",
        label="Localizar (hacer sonar)",
        confirmation_message=(
            "¿Hacer sonar el dispositivo? Si no seleccionas ninguna fila se envía a TODOS los "
            "usuarios con token de notificaciones registrado."
        ),
        add_in_list=True,
        add_in_detail=True,
    )
    async def locate_device(self, request: Request) -> RedirectResponse:
        pks = [p for p in request.query_params.get("pks", "").split(",") if p]

        db = SessionLocal()
        try:
            push.send_to_users(
                db,
                user_ids=_resolve_target_ids(db, pks),
                title="Lokate",
                body="Un administrador quiere localizar tu dispositivo",
                data={"type": "ring"},
            )
        finally:
            db.close()

        referer = request.headers.get("Referer")
        if referer:
            return RedirectResponse(referer)
        return RedirectResponse(request.url_for("admin:list", identity=self.identity))


class ZoneAdmin(ModelView, model=models.Zone):
    # El grupo por la relación y no por group_id: en la lista salía el UUID crudo y no había
    # manera de saber de qué grupo era cada zona. Ordenar sigue siendo por columnas de verdad.
    column_list = [models.Zone.name, models.Zone.group, models.Zone.radius_m]
    column_sortable_list = [models.Zone.name, models.Zone.radius_m]
    column_labels = {
        models.Zone.name: "Nombre",
        models.Zone.group: "Grupo",
        models.Zone.group_id: "Grupo",
        models.Zone.radius_m: "Radio (m)",
    }
    column_formatters_detail = {models.Zone.lat: _zone_map_html}
    create_template = "zone/create.html"
    edit_template = "zone/edit.html"
    name_plural = "Zonas"


class LocationPingAdmin(ModelView, model=models.LocationPing):
    column_list = [models.LocationPing.user_id, models.LocationPing.lat, models.LocationPing.lng, models.LocationPing.timestamp]
    can_create = False
    can_edit = False
    name_plural = "Historial de ubicaciones"


class LoginBlocksView(BaseView):
    """Quién lleva contraseñas falladas y a quién le ha saltado el freno de fuerza bruta (ver
    app/login_guard.py), con el botón para soltarlo sin esperar los 15 minutos — que es lo que
    hace falta cuando la que se ha equivocado cinco veces es de la familia."""

    name = "Intentos de acceso"
    icon = "fa-solid fa-user-lock"

    @expose("/login-blocks", identity="login-blocks", methods=["GET"])
    async def index(self, request: Request):
        return await self.templates.TemplateResponse(
            request,
            "login_blocks.html",
            {
                "title": t("login_blocks_title"),
                "rows": login_guard.entries(),
                "max_fails": login_guard.MAX_FAILS,
                "block_minutes": login_guard.BLOCK_S // 60,
            },
        )


class LoginUnblockView(BaseView):
    """Ruta propia (oculta) por la misma razón que BackupsView explica arriba. Sin nombre en el
    formulario, suelta a todos."""

    name = "Desbloquear acceso"

    def is_visible(self, request: Request) -> bool:
        return False

    @expose("/login-blocks/unblock", identity="login-blocks-unblock", methods=["POST"])
    async def unblock(self, request: Request):
        form = await request.form()
        username = str(form.get("username") or "")
        if username:
            login_guard.unblock(username)
        else:
            login_guard.unblock_all()
        return RedirectResponse(str(request.url_for("admin:login-blocks")), status_code=303)


def register_admin(app: FastAPI) -> None:
    admin = Admin(
        app,
        engine,
        authentication_backend=AdminAuth(secret_key=os.environ["JWT_SECRET"]),
        templates_dir=TEMPLATES_DIR,
        title="Lokate by DSK — Admin",
        favicon_url="/favicon.svg",
    )
    admin.templates.env.globals["t"] = t
    admin.templates.env.globals["ui_dict_es"] = UI_DICT_ES

    admin.add_view(DashboardView)
    # Segundo del menú a propósito: es lo que se mira con prisa.
    admin.add_view(LiveMapView)
    admin.add_view(LiveMapActionView)
    admin.add_view(HistoryMapView)
    admin.add_view(BackupsView)
    admin.add_view(BackupDownloadView)
    admin.add_view(BackupRestoreView)
    admin.add_view(BackupDeleteView)
    admin.add_view(FilesView)
    admin.add_view(FileDeleteView)
    admin.add_view(FileDeleteAllView)
    admin.add_view(GroupAdmin)
    admin.add_view(UserAdmin)
    admin.add_view(LocationsPurgeView)
    admin.add_view(UserAvatarUploadView)
    admin.add_view(LoginBlocksView)
    admin.add_view(LoginUnblockView)
    admin.add_view(SetLocaleView)
    admin.add_view(ZoneAdmin)
    admin.add_view(LocationPingAdmin)
