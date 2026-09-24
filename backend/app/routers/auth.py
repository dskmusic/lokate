from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Request, UploadFile, status
from sqlalchemy.orm import Session

from .. import login_guard, models, push, schemas
from ..auth import create_access_token, get_current_user, hash_password, verify_password
from ..database import get_db

router = APIRouter(prefix="/auth", tags=["auth"])

AVATAR_DIR = Path("/data/avatars")
ALLOWED_AVATAR_TYPES = {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp"}
MAX_AVATAR_BYTES = 5 * 1024 * 1024


@router.post("/register", response_model=schemas.TokenResponse, status_code=status.HTTP_201_CREATED)
def register(body: schemas.RegisterRequest, db: Session = Depends(get_db)):
    if db.query(models.User).filter(models.User.username == body.username).first():
        raise HTTPException(status.HTTP_409_CONFLICT, "Ese nombre de usuario ya existe")

    user = models.User(
        username=body.username,
        password_hash=hash_password(body.password),
        display_name=body.display_name,
    )
    db.add(user)
    db.commit()
    db.refresh(user)

    return schemas.TokenResponse(access_token=create_access_token(user.id), user=user)


@router.post("/login", response_model=schemas.TokenResponse)
def login(body: schemas.LoginRequest, request: Request, db: Session = Depends(get_db)):
    # Freno de fuerza bruta antes de tocar la base: ver app/login_guard.py. Se desbloquea solo
    # a los 15 min, o a mano desde el panel (Admin -> Intentos de acceso).
    remaining = login_guard.blocked_seconds(body.username)
    if remaining:
        raise HTTPException(
            status.HTTP_429_TOO_MANY_REQUESTS,
            f"Demasiados intentos fallidos. Vuelve a probar en {remaining // 60 + 1} min",
        )

    user = db.query(models.User).filter(models.User.username == body.username.lower()).first()
    if not user or not verify_password(body.password, user.password_hash):
        login_guard.record_failure(body.username, request.client.host if request.client else "")
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Usuario o contraseña incorrectos")

    login_guard.record_success(body.username)
    return schemas.TokenResponse(access_token=create_access_token(user.id), user=user)


@router.get("/me", response_model=schemas.UserResponse)
def me(user: models.User = Depends(get_current_user)):
    return user


@router.put("/me", response_model=schemas.UserResponse)
def update_me(
    body: schemas.UpdateProfileRequest,
    user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Solo el nombre visible es editable — el username (login) no cambia."""
    user.display_name = body.display_name
    db.commit()
    db.refresh(user)
    return user


@router.post("/device", status_code=status.HTTP_204_NO_CONTENT)
def register_device(
    body: schemas.RegisterDeviceRequest,
    user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    # Un token FCM identifica a un DISPOSITIVO, no a una cuenta: si en este móvil hubo antes
    # otra sesión, esa otra fila se quedó con el mismo token y FCM le seguía entregando aquí
    # sus avisos — el destinatario real no veía nada y el de este móvil recibía avisos ajenos.
    # Al registrarlo, se lo quitamos a cualquier otro usuario que lo tuviera.
    db.query(models.User).filter(
        models.User.fcm_token == body.fcm_token, models.User.id != user.id
    ).update({"fcm_token": None}, synchronize_session=False)

    user.fcm_token = body.fcm_token
    if body.location_frequency is not None:
        user.location_frequency = body.location_frequency
    if body.config_issues is not None:
        user.config_issues = body.config_issues
    if body.zone_channel_id is not None:
        user.zone_channel_id = body.zone_channel_id
    # Latido: quien tiene el envío de ubicación desactivado no manda pings nunca, así que este
    # es el único sitio donde el grupo se entera de su batería y su WiFi. Campo a campo y solo
    # si viene, para no borrar lo que ya había cuando llama una app anterior.
    if body.battery_level is not None:
        user.battery_level = body.battery_level
    if body.is_charging is not None:
        user.is_charging = body.is_charging
    if body.wifi_connected is not None:
        user.wifi_connected = body.wifi_connected
        user.wifi_ssid = body.wifi_ssid

    # La version se guarda y ya: quien avisa al admin es la propia app (ver mas abajo). Asi el
    # aviso llega aunque se reinstale la MISMA version encima, que es lo normal cuando se publica
    # un APK arreglado sin tocar el numero.
    if body.app_version:
        user.app_version = body.app_version
    db.commit()


# Que cuenta cada aviso al admin. El texto va con el nombre del grupo porque un admin los ve de
# todos los grupos a la vez.
_UPDATE_STAGES = {
    "started": "%s (%s) ha pulsado actualizar (tenia la %s)",
    "installed": "%s (%s) ha vuelto a abrir la app tras actualizar (ahora la %s)",
    "dismissed": "%s (%s) ha descartado el aviso de actualizacion (sigue en la %s)",
}


@router.post("/update-notice/status", status_code=status.HTTP_204_NO_CONTENT)
def update_notice_status(
    body: schemas.UpdateNoticeStatusRequest,
    user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Que ha hecho el usuario con el aviso de "actualiza la app": lo cuenta la propia app en
    los tres momentos, sin mirar numeros de version. A proposito — el admin quiere saber si la
    persona hizo caso, y reinstalar la misma version encima es una actualizacion igual de valida
    (Android la acepta mientras el versionCode no baje).

    ponytail: "installed" es "ha vuelto a la app despues de lanzar el instalador", no una
    confirmacion del sistema; si le da a actualizar y luego cancela el instalador, tambien
    llega. Por eso el aviso lleva la version, que es la unica prueba real. El descarte solo se
    entera cuando el aviso lo pinto la app (Android no avisa de los que pinta el sistema)."""
    template = _UPDATE_STAGES.get(body.stage)
    if template is None:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Fase desconocida: %s" % body.stage)
    if body.app_version:
        user.app_version = body.app_version
    # Lo mismo que se le manda al admin por push queda guardado, para la lista de "ultimo envio"
    # de la app (admin_api.update_notice_states). Si no le habian mandado ningun aviso, esto no
    # rellena update_notice_at: la fila solo sale en la lista si hubo envio.
    user.update_notice_status = body.stage
    user.update_notice_status_at = models.utcnow()
    db.commit()
    group = user.group.name if user.group else "sin grupo"
    _notify_admins(
        db,
        user,
        template % (user.display_name, group, body.app_version or user.app_version or "?"),
        "update_" + body.stage,
    )


def _notify_admins(db: Session, about: models.User, body: str, kind: str) -> None:
    """Avisa a los administradores de algo que ha hecho [about] (menos a el mismo, si lo es)."""
    admins = [
        u.id for u in db.query(models.User).filter(models.User.is_admin.is_(True)).all()
        if u.id != about.id
    ]
    if not admins:
        return
    push.send_to_users(db, user_ids=admins, title="Lokate", body=body, data={"type": kind})


@router.post("/avatar", response_model=schemas.AvatarResponse)
async def upload_avatar(
    file: UploadFile,
    user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    ext = ALLOWED_AVATAR_TYPES.get(file.content_type)
    if ext is None:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Formato de imagen no soportado (usa JPEG, PNG o WEBP)")

    contents = await file.read(MAX_AVATAR_BYTES + 1)
    if len(contents) > MAX_AVATAR_BYTES:
        raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "La imagen no puede superar 5 MB")

    AVATAR_DIR.mkdir(parents=True, exist_ok=True)
    filename = f"{user.id}.{ext}"
    (AVATAR_DIR / filename).write_bytes(contents)

    user.avatar_url = f"/avatars/{filename}"
    db.commit()

    return schemas.AvatarResponse(avatar_url=user.avatar_url)
