from fastapi import APIRouter, Depends, status
from sqlalchemy.orm import Session

from .. import models, schemas
from ..auth import get_current_user
from ..database import get_db

router = APIRouter(prefix="/backup", tags=["backup"])


@router.get("", response_model=schemas.BackupResponse)
def get_backup(
    user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """La copia de este usuario, si la hay. La app la pide al entrar para ofrecer restaurarla en
    un movil nuevo o recien reinstalado, y desde Ajustes para ensenar la fecha."""
    backup = db.get(models.UserBackup, user.id)
    if backup is None:
        return schemas.BackupResponse(exists=False)
    return schemas.BackupResponse(
        exists=True,
        updated_at=backup.updated_at,
        app_version=backup.app_version,
        payload=backup.payload,
    )


@router.put("", response_model=schemas.BackupResponse)
def put_backup(
    body: schemas.BackupUploadRequest,
    user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Guarda (pisando la anterior) la copia de este usuario. Una sola copia a proposito: lo que
    se quiere es "lo ultimo que tenia", no un historial que haya que elegir."""
    backup = db.get(models.UserBackup, user.id)
    if backup is None:
        backup = models.UserBackup(user_id=user.id)
        db.add(backup)
    backup.payload = body.payload
    backup.app_version = body.app_version
    # onupdate no salta si el contenido es identico (SQLAlchemy no ve cambios), y entonces la
    # fecha se quedaria vieja aunque la copia automatica haya ido bien.
    backup.updated_at = models.utcnow()
    db.commit()
    db.refresh(backup)
    return schemas.BackupResponse(
        exists=True,
        updated_at=backup.updated_at,
        app_version=backup.app_version,
        payload=None,  # el que sube ya tiene el contenido; devolverlo solo gasta datos
    )


@router.delete("", status_code=status.HTTP_204_NO_CONTENT)
def delete_backup(
    user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    backup = db.get(models.UserBackup, user.id)
    if backup is not None:
        db.delete(backup)
        db.commit()
