from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, UploadFile, status
from sqlalchemy.orm import Session

from .. import models, schemas
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
def login(body: schemas.LoginRequest, db: Session = Depends(get_db)):
    user = db.query(models.User).filter(models.User.username == body.username.lower()).first()
    if not user or not verify_password(body.password, user.password_hash):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Usuario o contraseña incorrectos")

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
    user.fcm_token = body.fcm_token
    db.commit()


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
