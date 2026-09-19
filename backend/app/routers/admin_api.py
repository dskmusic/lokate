"""API JSON para el panel de administración NATIVO de la app (no la web /admin, que sigue
existiendo aparte) — mismas acciones, pero pensadas para Compose en vez de plantillas Jinja.
Toda la lógica de negocio (estadísticas, subida de avatar...) está deliberadamente duplicada de
admin.py/routers/auth.py en vez de compartida: son dos superficies distintas (sesión de cookie
del panel web vs. JWT de la app) y la duplicación aquí es más simple y clara que forzar una
capa compartida para dos consumidores con formas de autenticarse distintas."""

import secrets
import string
from collections import Counter
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Path, UploadFile, status
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session

from .. import backups, geofence, managed_files, models, push, schemas
from ..auth import get_current_admin_user, hash_password
from ..database import get_db
from ..disk_usage import compute_disk_usage
from ..models import utcnow
from .auth import ALLOWED_AVATAR_TYPES, AVATAR_DIR, MAX_AVATAR_BYTES

router = APIRouter(prefix="/admin-api", tags=["admin-api"])

# uuid4().hex generado por backups.py — valida el id antes de tocar el filesystem con él.
_BACKUP_ID = Path(pattern=r"^[0-9a-f]{32}$")


@router.get("/disk-usage", response_model=schemas.AdminDiskUsageResponse)
def disk_usage(admin: models.User = Depends(get_current_admin_user)):
    return compute_disk_usage()


# ---- Explorador de archivos borrables ----
# La lista blanca de carpetas y el guardia de rutas viven en app/managed_files.py, compartidos
# con el panel web.
def _check_folder(folder: str) -> None:
    if folder not in managed_files.MANAGED_DIRS:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown folder")


@router.get("/files/{folder}", response_model=list[schemas.AdminFileResponse])
def list_files(
    folder: str,
    admin: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db),
):
    _check_folder(folder)
    return managed_files.list_files(db, folder)


@router.delete("/files/{folder}", response_model=schemas.AdminDeleteAllResponse)
def delete_all_files(
    folder: str,
    admin: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db),
):
    _check_folder(folder)
    return schemas.AdminDeleteAllResponse(deleted=managed_files.delete_folder(db, folder))


@router.delete("/files/{folder}/{name}", status_code=status.HTTP_204_NO_CONTENT)
def delete_file(
    folder: str,
    name: str,
    admin: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db),
):
    _check_folder(folder)
    try:
        deleted = managed_files.delete_file(db, folder, name)
    except managed_files.InvalidName:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Invalid file name")
    if not deleted:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "File not found")


# ---- Copias de seguridad ----
@router.get("/backups", response_model=list[schemas.AdminBackupResponse])
def list_backups(admin: models.User = Depends(get_current_admin_user)):
    return backups.list_backups()


@router.post("/backups", response_model=schemas.AdminBackupResponse, status_code=status.HTTP_201_CREATED)
def create_backup(body: schemas.AdminBackupCreateRequest, admin: models.User = Depends(get_current_admin_user)):
    return backups.create_backup(body.description)


@router.get("/backups/{backup_id}/download")
def download_backup(backup_id: str = _BACKUP_ID, admin: models.User = Depends(get_current_admin_user)):
    tar_path = backups.get_tar_path(backup_id)
    if not tar_path:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Backup not found")
    return FileResponse(tar_path, media_type="application/gzip", filename=f"lokate-backup-{backup_id}.tar.gz")


@router.post("/backups/{backup_id}/restore", status_code=status.HTTP_204_NO_CONTENT)
def restore_backup(backup_id: str = _BACKUP_ID, admin: models.User = Depends(get_current_admin_user)):
    if not backups.restore_backup(backup_id):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Backup not found")


@router.delete("/backups/{backup_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_backup(backup_id: str = _BACKUP_ID, admin: models.User = Depends(get_current_admin_user)):
    if not backups.delete_backup(backup_id):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Backup not found")


@router.get("/dashboard", response_model=schemas.AdminDashboardResponse)
def dashboard(admin: models.User = Depends(get_current_admin_user), db: Session = Depends(get_db)):
    now = utcnow()
    since_15m = now - timedelta(minutes=15)
    since_24h = now - timedelta(hours=24)
    since_7d = now - timedelta(days=7)

    stats = schemas.AdminDashboardStats(
        groups=db.query(models.Group).count(),
        users=db.query(models.User).count(),
        admins=db.query(models.User).filter(models.User.is_admin.is_(True)).count(),
        online_now=(
            db.query(models.LocationPing.user_id).filter(models.LocationPing.timestamp >= since_15m).distinct().count()
        ),
        pings_24h=db.query(models.LocationPing).filter(models.LocationPing.timestamp >= since_24h).count(),
        active_users_24h=(
            db.query(models.LocationPing.user_id).filter(models.LocationPing.timestamp >= since_24h).distinct().count()
        ),
        devices_with_push=db.query(models.User).filter(models.User.fcm_token.isnot(None)).count(),
    )

    # Serie de 7 días agrupada en Python, no en SQL — no depende de cómo formatea fechas el
    # dialecto de turno (SQLite/Postgres/...), mismo motivo que en el panel web.
    recent_pings = db.query(models.LocationPing.timestamp).filter(models.LocationPing.timestamp >= since_7d).all()
    day_counts = Counter(p.timestamp.date().isoformat() for p in recent_pings)
    max_day_count = max(day_counts.values(), default=0)
    activity_series = []
    for i in range(6, -1, -1):
        day = (now - timedelta(days=i)).date()
        count = day_counts.get(day.isoformat(), 0)
        activity_series.append(
            schemas.AdminActivityDay(
                label=day.strftime("%d/%m"),
                count=count,
                pct=round(count / max_day_count * 100) if max_day_count else 0,
            ),
        )

    recent_users = db.query(models.User).order_by(models.User.created_at.desc()).limit(5).all()
    recent_activity_rows = (
        db.query(models.LocationPing, models.User)
        .join(models.User, models.LocationPing.user_id == models.User.id)
        .order_by(models.LocationPing.timestamp.desc())
        .limit(8)
        .all()
    )
    recent_activity = [
        schemas.AdminRecentActivityItem(
            user_id=u.id, display_name=u.display_name, avatar_url=u.avatar_url, timestamp=p.timestamp,
        )
        for p, u in recent_activity_rows
    ]

    return schemas.AdminDashboardResponse(
        stats=stats, activity_series=activity_series, recent_users=recent_users, recent_activity=recent_activity,
    )


# ---- Grupos ----
@router.get("/groups", response_model=list[schemas.AdminGroupResponse])
def list_groups(admin: models.User = Depends(get_current_admin_user), db: Session = Depends(get_db)):
    return db.query(models.Group).order_by(models.Group.created_at.desc()).all()


def _generate_invite_code(db: Session) -> str:
    alphabet = string.ascii_uppercase + string.digits
    code = "".join(secrets.choice(alphabet) for _ in range(6))
    while db.query(models.Group).filter(models.Group.invite_code == code).first():
        code = "".join(secrets.choice(alphabet) for _ in range(6))
    return code


@router.post("/groups", response_model=schemas.AdminGroupResponse, status_code=status.HTTP_201_CREATED)
def create_group(
    body: schemas.AdminGroupCreateRequest,
    admin: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db),
):
    group = models.Group(name=body.name, invite_code=_generate_invite_code(db))
    db.add(group)
    db.commit()
    db.refresh(group)
    return group


@router.delete("/groups/{group_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_group(group_id: str, admin: models.User = Depends(get_current_admin_user), db: Session = Depends(get_db)):
    group = db.get(models.Group, group_id)
    if not group:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Group not found")
    # Desvincula a sus miembros en vez de borrarlos (mismo efecto que "salir del grupo" cada
    # uno por su cuenta) antes de borrar sus zonas y el grupo en sí.
    db.query(models.User).filter(models.User.group_id == group_id).update({"group_id": None})
    zone_ids = [z.id for z in db.query(models.Zone.id).filter(models.Zone.group_id == group_id).all()]
    if zone_ids:
        db.query(models.ZoneState).filter(models.ZoneState.zone_id.in_(zone_ids)).delete(synchronize_session=False)
        db.query(models.ZoneNotificationPref).filter(models.ZoneNotificationPref.zone_id.in_(zone_ids)).delete(
            synchronize_session=False,
        )
    db.query(models.Zone).filter(models.Zone.group_id == group_id).delete(synchronize_session=False)
    db.delete(group)
    db.commit()


# ---- Usuarios ----
@router.get("/users", response_model=list[schemas.AdminUserResponse])
def list_users(admin: models.User = Depends(get_current_admin_user), db: Session = Depends(get_db)):
    return db.query(models.User).order_by(models.User.created_at.desc()).all()


@router.post("/users", response_model=schemas.AdminUserResponse, status_code=status.HTTP_201_CREATED)
def create_user(
    body: schemas.AdminUserCreateRequest,
    admin: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db),
):
    if db.query(models.User).filter(models.User.username == body.username).first():
        raise HTTPException(status.HTTP_409_CONFLICT, "Ese nombre de usuario ya existe")
    if body.group_id is not None and not db.get(models.Group, body.group_id):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Group not found")

    user = models.User(
        username=body.username,
        password_hash=hash_password(body.password),
        display_name=body.display_name,
        group_id=body.group_id,
        is_admin=body.is_admin,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


@router.get("/users/{user_id}", response_model=schemas.AdminUserResponse)
def get_user(user_id: str, admin: models.User = Depends(get_current_admin_user), db: Session = Depends(get_db)):
    user = db.get(models.User, user_id)
    if not user:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "User not found")
    return user


@router.put("/users/{user_id}", response_model=schemas.AdminUserResponse)
def update_user(
    user_id: str,
    body: schemas.AdminUserUpdateRequest,
    admin: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db),
):
    user = db.get(models.User, user_id)
    if not user:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "User not found")
    if body.display_name is not None:
        user.display_name = body.display_name
    if body.is_admin is not None:
        user.is_admin = body.is_admin
    db.commit()
    db.refresh(user)
    return user


@router.delete("/users/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_user(user_id: str, admin: models.User = Depends(get_current_admin_user), db: Session = Depends(get_db)):
    if user_id == admin.id:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "No puedes borrarte a ti mismo")
    user = db.get(models.User, user_id)
    if not user:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "User not found")
    db.query(models.LocationPing).filter(models.LocationPing.user_id == user_id).delete()
    db.query(models.ZoneState).filter(models.ZoneState.user_id == user_id).delete()
    db.query(models.ZoneNotificationPref).filter(models.ZoneNotificationPref.user_id == user_id).delete()
    db.delete(user)
    db.commit()


@router.post("/users/{user_id}/notify-test", status_code=status.HTTP_204_NO_CONTENT)
def notify_test(user_id: str, admin: models.User = Depends(get_current_admin_user), db: Session = Depends(get_db)):
    push.send_to_user(
        db, user_id, "Lokate — Prueba", "Notificación de prueba enviada desde el panel de administración",
        {"type": "test"},
    )


@router.post("/users/{user_id}/locate", status_code=status.HTTP_204_NO_CONTENT)
def locate_user(user_id: str, admin: models.User = Depends(get_current_admin_user), db: Session = Depends(get_db)):
    push.send_to_user(db, user_id, "Lokate", "Un administrador quiere localizar tu dispositivo", {"type": "ring"})


@router.post("/users/{user_id}/avatar", response_model=schemas.AvatarResponse)
async def admin_upload_avatar(
    user_id: str,
    file: UploadFile,
    admin: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db),
):
    target = db.get(models.User, user_id)
    if not target:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "User not found")
    ext = ALLOWED_AVATAR_TYPES.get(file.content_type)
    if ext is None:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Formato de imagen no soportado (usa JPEG, PNG o WEBP)")
    contents = await file.read(MAX_AVATAR_BYTES + 1)
    if len(contents) > MAX_AVATAR_BYTES:
        raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "La imagen no puede superar 5 MB")
    AVATAR_DIR.mkdir(parents=True, exist_ok=True)
    filename = f"{target.id}.{ext}"
    (AVATAR_DIR / filename).write_bytes(contents)
    target.avatar_url = f"/avatars/{filename}"
    db.commit()
    return schemas.AvatarResponse(avatar_url=target.avatar_url)


# ---- Zonas (todas, de cualquier grupo — a diferencia de /zones, que es solo del propio grupo) ----
@router.get("/zones", response_model=list[schemas.AdminZoneResponse])
def list_all_zones(admin: models.User = Depends(get_current_admin_user), db: Session = Depends(get_db)):
    return db.query(models.Zone).order_by(models.Zone.created_at.desc()).all()


@router.post("/zones", response_model=schemas.AdminZoneResponse, status_code=status.HTTP_201_CREATED)
def create_zone_admin(
    body: schemas.AdminZoneCreateRequest,
    admin: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db),
):
    if not db.get(models.Group, body.group_id):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Group not found")
    zone = models.Zone(
        group_id=body.group_id,
        name=body.name,
        lat=body.lat,
        lng=body.lng,
        radius_m=body.radius_m,
        created_by=admin.id,
    )
    db.add(zone)
    db.commit()
    db.refresh(zone)
    return zone


@router.put("/zones/{zone_id}", response_model=schemas.AdminZoneResponse)
def update_zone_admin(
    zone_id: str,
    body: schemas.AdminZoneUpdateRequest,
    admin: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db),
):
    zone = db.get(models.Zone, zone_id)
    if not zone:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Zone not found")
    zone.name, zone.lat, zone.lng, zone.radius_m = body.name, body.lat, body.lng, body.radius_m
    db.commit()
    db.refresh(zone)
    return zone


@router.delete("/zones/{zone_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_zone_admin(zone_id: str, admin: models.User = Depends(get_current_admin_user), db: Session = Depends(get_db)):
    zone = db.get(models.Zone, zone_id)
    if not zone:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Zone not found")
    db.query(models.ZoneState).filter(models.ZoneState.zone_id == zone_id).delete()
    db.query(models.ZoneNotificationPref).filter(models.ZoneNotificationPref.zone_id == zone_id).delete()
    db.delete(zone)
    db.commit()


# ---- Historial (de cualquier usuario, no solo el propio grupo) ----
@router.get("/history", response_model=list[schemas.AdminHistoryPoint])
def admin_history(
    user_id: str,
    date: str,
    admin: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db),
):
    try:
        day = datetime.strptime(date, "%Y-%m-%d").replace(tzinfo=timezone.utc)
    except ValueError:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Invalid date, expected YYYY-MM-DD")
    next_day = day + timedelta(days=1)
    return (
        db.query(models.LocationPing)
        .filter(
            models.LocationPing.user_id == user_id,
            models.LocationPing.timestamp >= day,
            models.LocationPing.timestamp < next_day,
        )
        .order_by(models.LocationPing.timestamp.asc())
        .all()
    )


# ---- Modo prueba: arrastrar a un miembro por el mapa para disparar avisos de zona de verdad ----
@router.post("/simulate/stop", status_code=status.HTTP_204_NO_CONTENT)
def stop_simulation(
    admin: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Salir del modo prueba: todo el grupo vuelve a su posición real y su estado de zonas se
    recalcula a partir de ella en silencio (deshacer un arrastre no debe avisar a nadie)."""
    if admin.group_id is None:
        return
    member_ids = [
        u.id for u in db.query(models.User).filter(models.User.group_id == admin.group_id).all()
    ]
    geofence.stop_simulation(member_ids)
    geofence.resync_zone_states(db, member_ids)


@router.post("/simulate/{user_id}", response_model=schemas.AdminSimulateResponse)
def simulate_position(
    user_id: str,
    body: schemas.AdminSimulateRequest,
    admin: models.User = Depends(get_current_admin_user),
    db: Session = Depends(get_db),
):
    """Evalúa las zonas como si ese miembro estuviera en (lat, lng), sin guardar la posición:
    no deja rastro en el historial. A partir de aquí sus pings reales dejan de evaluar zonas
    hasta salir del modo (ver geofence.SIMULATION_TTL_S)."""
    target = db.query(models.User).filter(
        models.User.id == user_id, models.User.group_id == admin.group_id
    ).first()
    if not target or admin.group_id is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Member not found in your group")
    if target.id == admin.id:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "No puedes simular tu propia posición")

    # Solo destinatarios del propio grupo: la lista viene del móvil del admin y podría traer
    # ids de alguien que ya no está.
    allowed = {
        u.id for u in db.query(models.User).filter(models.User.group_id == admin.group_id).all()
    }
    recipients = [uid for uid in body.recipient_ids if uid in allowed]

    geofence.start_simulation(target.id)
    report = geofence.check_zone_transitions(
        db, target, body.lat, body.lng, recipients_override=recipients
    )
    return schemas.AdminSimulateResponse(
        transitions=[body_text for body_text, _ in report],
        notified=sum(count for _, count in report),
    )
