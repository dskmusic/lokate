from math import atan2, cos, radians, sin, sqrt

from sqlalchemy.orm import Session

from . import models, push

EARTH_RADIUS_M = 6_371_000

# Margen de histéresis: una vez dentro, hay que alejarse radius_m + este margen para contar
# como salida. Evita el parpadeo entrada/salida (y el bombardeo de notificaciones) cuando el
# GPS oscila cerca del borde o una carretera roza el límite de la zona.
ZONE_EXIT_MARGIN_M = 30


def distance_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    """Haversine distance in meters."""
    phi1, phi2 = radians(lat1), radians(lat2)
    d_phi = radians(lat2 - lat1)
    d_lambda = radians(lng2 - lng1)
    a = sin(d_phi / 2) ** 2 + cos(phi1) * cos(phi2) * sin(d_lambda / 2) ** 2
    return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))


def _notification_recipients(db: Session, group_id: str, exclude_user_id: str, zone_id: str, is_inside: bool) -> list[str]:
    """Miembros del grupo (menos quien se ha movido) que quieren avisos de esta zona en esta dirección.
    Sin fila de preferencia guardada para un usuario+zona, se asume que NO quiere avisos — el
    usuario tiene que activarlos explícitamente, no vienen activados por defecto."""
    members = db.query(models.User).filter(models.User.group_id == group_id, models.User.id != exclude_user_id).all()
    if not members:
        return []

    prefs = {
        p.user_id: p
        for p in db.query(models.ZoneNotificationPref).filter(
            models.ZoneNotificationPref.zone_id == zone_id,
            models.ZoneNotificationPref.user_id.in_([m.id for m in members]),
        ).all()
    }

    recipients = []
    for member in members:
        pref = prefs.get(member.id)
        wants_enter = pref.notify_on_enter if pref else False
        wants_exit = pref.notify_on_exit if pref else False
        if (is_inside and wants_enter) or (not is_inside and wants_exit):
            recipients.append(member.id)
    return recipients


def check_zone_transitions(db: Session, user: models.User, lat: float, lng: float) -> None:
    """Compare current position against every zone in the user's group and push on enter/exit."""
    zones = db.query(models.Zone).filter(models.Zone.group_id == user.group_id).all()
    for zone in zones:
        state = db.get(models.ZoneState, (user.id, zone.id))
        was_inside = state.is_inside if state else False

        # Umbral asimétrico: para entrar basta el radio normal, pero para salir hay que superar
        # radio + margen — así una vez dentro, la histéresis absorbe el ruido del GPS en el borde.
        threshold = zone.radius_m + ZONE_EXIT_MARGIN_M if was_inside else zone.radius_m
        is_inside = distance_m(lat, lng, zone.lat, zone.lng) <= threshold

        if state is None:
            state = models.ZoneState(user_id=user.id, zone_id=zone.id, is_inside=is_inside)
            db.add(state)
        else:
            state.is_inside = is_inside

        if is_inside != was_inside:
            verb = "ha entrado en" if is_inside else "ha salido de"
            recipients = _notification_recipients(db, user.group_id, user.id, zone.id, is_inside)
            push.send_to_users(
                db,
                user_ids=recipients,
                title=zone.name,
                body=f"{user.display_name} {verb} {zone.name}",
                data={"type": "zone_transition", "zone_id": zone.id, "user_id": user.id, "entered": is_inside},
            )

    db.commit()
