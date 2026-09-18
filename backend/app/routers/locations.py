import os
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session

from .. import geofence, models, push, schemas
from ..auth import get_current_user_with_group
from ..database import get_db

router = APIRouter(prefix="/location", tags=["location"])

RETENTION_DAYS = int(os.getenv("LOCATION_RETENTION_DAYS", "30"))


@router.post("/ping", status_code=status.HTTP_204_NO_CONTENT)
def ping(
    body: schemas.LocationPingRequest,
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    db.add(models.LocationPing(user_id=user.id, lat=body.lat, lng=body.lng, accuracy=body.accuracy))

    user.battery_level = body.battery_level
    user.is_charging = body.is_charging
    user.wifi_connected = body.wifi_connected
    user.wifi_ssid = body.wifi_ssid

    cutoff = datetime.now(timezone.utc) - timedelta(days=RETENTION_DAYS)
    db.query(models.LocationPing).filter(
        models.LocationPing.user_id == user.id, models.LocationPing.timestamp < cutoff
    ).delete()
    db.commit()

    geofence.check_zone_transitions(db, user, body.lat, body.lng)


@router.get("/group/latest", response_model=list[schemas.LocationResponse])
def group_latest(
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    members = db.query(models.User).filter(models.User.group_id == user.group_id).all()
    results = []
    for member in members:
        latest = (
            db.query(models.LocationPing)
            .filter(models.LocationPing.user_id == member.id)
            .order_by(models.LocationPing.timestamp.desc())
            .first()
        )
        if latest:
            results.append(
                schemas.LocationResponse(
                    user_id=member.id,
                    display_name=member.display_name,
                    avatar_url=member.avatar_url,
                    lat=latest.lat,
                    lng=latest.lng,
                    accuracy=latest.accuracy,
                    timestamp=latest.timestamp,
                    battery_level=member.battery_level,
                    is_charging=member.is_charging,
                    wifi_connected=member.wifi_connected,
                    wifi_ssid=member.wifi_ssid,
                )
            )
    return results


@router.get("/history", response_model=list[schemas.LocationHistoryPoint])
def history(
    hours: int = Query(default=24, ge=1, le=24 * RETENTION_DAYS),
    from_ts: datetime | None = None,
    to_ts: datetime | None = None,
    user_id: str | None = None,
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    """[from_ts, to_ts): rango exacto (para "ayer"/"hoy"/fecha elegida en la app, calculado
    en el cliente con su huso horario). Sin ellos: ventana relativa de [hours]."""
    target_id = user_id or user.id
    if target_id != user.id:
        member = db.query(models.User).filter(
            models.User.id == target_id, models.User.group_id == user.group_id
        ).first()
        if not member:
            return []

    query = db.query(models.LocationPing).filter(models.LocationPing.user_id == target_id)
    if from_ts is not None and to_ts is not None:
        query = query.filter(models.LocationPing.timestamp >= from_ts, models.LocationPing.timestamp < to_ts)
    else:
        cutoff = datetime.now(timezone.utc) - timedelta(hours=hours)
        query = query.filter(models.LocationPing.timestamp >= cutoff)
    return query.order_by(models.LocationPing.timestamp.asc()).all()


@router.post("/ring/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
def ring_device(
    user_id: str,
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    target = db.query(models.User).filter(
        models.User.id == user_id, models.User.group_id == user.group_id
    ).first()
    if not target:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Member not found in your group")

    push.send_to_user(
        db,
        user_id=target.id,
        title="Lokate",
        body=f"{user.display_name} quiere localizar tu dispositivo",
        data={"type": "ring"},
    )


@router.post("/request-location/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
def request_location(
    user_id: str,
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    """Push silencioso (sin sonido/notificación) que pide a ese dispositivo una ubicación
    puntual fresca — a diferencia de /ring, que solo hace sonar la alarma."""
    target = db.query(models.User).filter(
        models.User.id == user_id, models.User.group_id == user.group_id
    ).first()
    if not target:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Member not found in your group")

    push.send_to_user(
        db,
        user_id=target.id,
        title="Lokate",
        body="Solicitud de ubicación",
        data={"type": "request_location"},
    )
