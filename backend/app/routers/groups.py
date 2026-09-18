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
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    """Solo administradores: prueba el envío de push a todo el grupo (incluido quien la manda)."""
    if not user.is_admin:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Solo los usuarios administradores pueden hacer esto")

    member_ids = [m.id for m in db.query(models.User).filter(models.User.group_id == user.group_id).all()]
    push.send_to_users(
        db,
        user_ids=member_ids,
        title="Lokate — Prueba",
        body=f"Notificación de prueba enviada por {user.display_name}",
        data={"type": "test"},
    )
