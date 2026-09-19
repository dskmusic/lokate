import secrets
import string

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from .. import models, push, schemas
from ..auth import get_current_user, get_current_user_with_group
from ..database import get_db

router = APIRouter(prefix="/groups", tags=["groups"])


def _generate_invite_code() -> str:
    alphabet = string.ascii_uppercase + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(6))


def _resolve_test_targets(member_ids: list[str], requested: list[str] | None) -> list[str]:
    """Sin destinatarios pedidos = todo el grupo. Con ellos, solo los que además son del grupo:
    filtrado a propósito para que un admin no pueda mandar push a usuarios de otros grupos
    pasando ids a mano."""
    if not requested:
        return member_ids
    wanted = set(requested)
    return [i for i in member_ids if i in wanted]


@router.post("", response_model=schemas.GroupResponse, status_code=status.HTTP_201_CREATED)
def create_group(
    body: schemas.GroupCreateRequest,
    user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    if not user.is_admin:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Solo los usuarios administradores pueden crear grupos")
    if user.group_id is not None:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "You already belong to a group")

    code = _generate_invite_code()
    while db.query(models.Group).filter(models.Group.invite_code == code).first():
        code = _generate_invite_code()

    group = models.Group(name=body.name, invite_code=code)
    db.add(group)
    db.flush()
    user.group_id = group.id
    db.commit()
    db.refresh(group)
    return group


@router.post("/join", response_model=schemas.GroupResponse)
def join_group(
    body: schemas.GroupJoinRequest,
    user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    if user.group_id is not None:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "You already belong to a group")

    group = db.query(models.Group).filter(models.Group.invite_code == body.invite_code.upper()).first()
    if not group:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Invalid invite code")

    user.group_id = group.id
    db.commit()
    return group


@router.get("/me", response_model=schemas.GroupResponse)
def my_group(user: models.User = Depends(get_current_user_with_group)):
    return user.group


@router.get("/me/members", response_model=list[schemas.GroupMemberResponse])
def my_group_members(
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    return db.query(models.User).filter(models.User.group_id == user.group_id).all()


@router.post("/leave", status_code=status.HTTP_204_NO_CONTENT)
def leave_group(
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    user.group_id = None
    db.commit()


@router.post("/test-notification", status_code=status.HTTP_204_NO_CONTENT)
def send_test_notification(
    body: schemas.TestNotificationRequest | None = None,
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    """Solo administradores: prueba el envío de push a miembros concretos, o a todo el grupo si no se indican."""
    if not user.is_admin:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Solo los usuarios administradores pueden hacer esto")

    group_ids = [m.id for m in db.query(models.User).filter(models.User.group_id == user.group_id).all()]
    member_ids = _resolve_test_targets(group_ids, body.user_ids if body else None)
    if not member_ids:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Ningún destinatario válido en tu grupo")

    push.send_to_users(
        db,
        user_ids=member_ids,
        title="Lokate — Prueba",
        body=f"Notificación de prueba enviada por {user.display_name}",
        data={"type": "test"},
    )
