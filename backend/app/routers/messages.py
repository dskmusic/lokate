import uuid
from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from sqlalchemy.orm import Session

from .. import models, push
from ..auth import get_current_user_with_group
from ..database import get_db

router = APIRouter(prefix="/messages", tags=["messages"])

ATTACHMENT_DIR = Path("/data/attachments")
MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024


def _attachment_kind(content_type: str) -> str:
    if content_type.startswith("image/"):
        return "image"
    if content_type.startswith("video/"):
        return "video"
    return "file"


@router.post("/emergency/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
async def send_emergency_message(
    user_id: str,
    text: str = Form(default=""),
    file: UploadFile | None = File(default=None),
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    """Mensaje de emergencia: siempre suena y vibra en el destinatario, sin tener en cuenta
    ninguna preferencia previa (a diferencia de las notificaciones normales)."""
    if not text.strip() and file is None:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "El mensaje necesita texto o un archivo adjunto")

    target = db.query(models.User).filter(
        models.User.id == user_id, models.User.group_id == user.group_id
    ).first()
    if not target:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Member not found in your group")

    attachment_url = ""
    attachment_kind = ""
    if file is not None:
        contents = await file.read(MAX_ATTACHMENT_BYTES + 1)
        if len(contents) > MAX_ATTACHMENT_BYTES:
            raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "El archivo no puede superar 25 MB")

        ATTACHMENT_DIR.mkdir(parents=True, exist_ok=True)
        ext = Path(file.filename or "").suffix
        filename = f"{uuid.uuid4()}{ext}"
        (ATTACHMENT_DIR / filename).write_bytes(contents)

        attachment_url = f"/attachments/{filename}"
        attachment_kind = _attachment_kind(file.content_type or "")

    push.send_to_user(
        db,
        user_id=target.id,
        title=f"⚠️ {user.display_name}",
        body=text.strip() or "Mensaje prioritario",
        data={
            "type": "emergency_message",
            "from_user_id": user.id,
            "from_display_name": user.display_name,
            "text": text.strip(),
            "attachment_url": attachment_url,
            "attachment_kind": attachment_kind,
        },
    )
