from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from sqlalchemy import or_

from .. import models, schemas
from ..auth import get_current_user_with_group
from ..database import get_db

router = APIRouter(prefix="/zones", tags=["zones"])


def _visible_zones(db: Session, user: models.User):
    """Las de su grupo, menos las privadas de otros. Una privada no existe para los demas: ni
    se lista, ni se edita, ni se borra, ni avisa (ver geofence)."""
    return db.query(models.Zone).filter(
        models.Zone.group_id == user.group_id,
        or_(models.Zone.is_public.is_(True), models.Zone.created_by == user.id),
    )


@router.get("", response_model=list[schemas.ZoneResponse])
def list_zones(
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    return _visible_zones(db, user).all()


@router.post("", response_model=schemas.ZoneResponse, status_code=status.HTTP_201_CREATED)
def create_zone(
    body: schemas.ZoneCreateRequest,
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    zone = models.Zone(
        group_id=user.group_id,
        name=body.name,
        lat=body.lat,
        lng=body.lng,
        radius_m=body.radius_m,
        is_public=body.is_public,
        created_by=user.id,
    )
    db.add(zone)
    db.commit()
    db.refresh(zone)
    return zone


def _get_owned_zone(zone_id: str, user: models.User, db: Session) -> models.Zone:
    zone = _visible_zones(db, user).filter(models.Zone.id == zone_id).first()
    if not zone:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Zone not found")
    return zone


@router.put("/{zone_id}", response_model=schemas.ZoneResponse)
def update_zone(
    zone_id: str,
    body: schemas.ZoneCreateRequest,
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    zone = _get_owned_zone(zone_id, user, db)
    zone.name, zone.lat, zone.lng, zone.radius_m = body.name, body.lat, body.lng, body.radius_m
    zone.is_public = body.is_public
    db.commit()
    db.refresh(zone)
    return zone


@router.delete("/{zone_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_zone(
    zone_id: str,
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    zone = _get_owned_zone(zone_id, user, db)
    db.query(models.ZoneState).filter(models.ZoneState.zone_id == zone_id).delete()
    db.query(models.ZoneNotificationPref).filter(models.ZoneNotificationPref.zone_id == zone_id).delete()
    db.delete(zone)
    db.commit()


@router.get("/notification-prefs", response_model=list[schemas.ZoneNotificationPrefResponse])
def get_notification_prefs(
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    """Preferencia del usuario actual para cada zona del grupo (por defecto: sin avisos hasta que
    el usuario los active). Es suya y de este móvil: no la comparte con el resto del grupo."""
    zones = _visible_zones(db, user).all()
    prefs = {
        p.zone_id: p
        for p in db.query(models.ZoneNotificationPref).filter(models.ZoneNotificationPref.user_id == user.id).all()
    }
    return [
        schemas.ZoneNotificationPrefResponse(
            zone_id=zone.id,
            notify_on_enter=prefs[zone.id].notify_on_enter if zone.id in prefs else False,
            notify_on_exit=prefs[zone.id].notify_on_exit if zone.id in prefs else False,
            watched_user_ids=prefs[zone.id].watched_user_ids if zone.id in prefs else [],
        )
        for zone in zones
    ]


@router.put("/{zone_id}/notification-prefs", response_model=schemas.ZoneNotificationPrefResponse)
def update_notification_pref(
    zone_id: str,
    body: schemas.ZoneNotificationPrefUpdateRequest,
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    _get_owned_zone(zone_id, user, db)  # 404 si la zona no es de tu grupo

    pref = db.get(models.ZoneNotificationPref, (user.id, zone_id))
    if pref is None:
        pref = models.ZoneNotificationPref(user_id=user.id, zone_id=zone_id)
        db.add(pref)

    pref.notify_on_enter = body.notify_on_enter
    pref.notify_on_exit = body.notify_on_exit
    pref.watched_ids = ",".join(body.watched_user_ids)
    db.commit()

    return schemas.ZoneNotificationPrefResponse(
        zone_id=zone_id,
        notify_on_enter=pref.notify_on_enter,
        notify_on_exit=pref.notify_on_exit,
        watched_user_ids=pref.watched_user_ids,
    )
