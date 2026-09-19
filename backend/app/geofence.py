from math import atan2, cos, radians, sin, sqrt
from time import monotonic

from sqlalchemy.orm import Session

from . import models, push

EARTH_RADIUS_M = 6_371_000

# Margen de histéresis: una vez dentro, hay que alejarse radius_m + este margen para contar
# como salida. Evita el parpadeo entrada/salida (y el bombardeo de notificaciones) cuando el
# GPS oscila cerca del borde o una carretera roza el límite de la zona.
ZONE_EXIT_MARGIN_M = 30

# ---- Modo prueba de los administradores ----
# Mientras un admin arrastra a alguien por el mapa para probar los avisos, la posición REAL de
# ese alguien se sigue guardando pero deja de evaluar zonas: si no, su siguiente ping (cada
# pocos segundos si comparte en tiempo real) desharía la simulación al instante y dispararía el
# aviso contrario. Vive en memoria a propósito: si el servidor se reinicia, nadie se queda
# congelado. El TTL es la red de seguridad para cuando la app del admin muere sin salir del modo.
SIMULATION_TTL_S = 15 * 60
_simulated_until: dict[str, float] = {}


def start_simulation(user_id: str) -> None:
    _simulated_until[user_id] = monotonic() + SIMULATION_TTL_S


def simulation_status(user_id: str) -> str | None:
    """None = posición real. "active" = simulada ahora mismo. "expired" = lo estuvo y ha
    caducado; el primer ping real que llegue debe recolocar su estado de zonas EN SILENCIO,
    porque la "transición" de volver a su sitio nunca ocurrió de verdad."""
    until = _simulated_until.get(user_id)
    if until is None:
        return None
    if until > monotonic():
        return "active"
    del _simulated_until[user_id]
    return "expired"


def stop_simulation(user_ids: list[str]) -> None:
    for user_id in user_ids:
        _simulated_until.pop(user_id, None)


def resync_zone_states(db: Session, user_ids: list[str]) -> None:
    """Devuelve el estado de zonas de cada usuario a lo que dice su última posición REAL, sin
    avisar a nadie: al salir del modo prueba hay que deshacer las transiciones simuladas, pero
    sin disparar los avisos contrarios (nadie ha entrado ni salido de nada)."""
    for user_id in user_ids:
        user = db.get(models.User, user_id)
        if user is None:
            continue
        last = (
            db.query(models.LocationPing)
            .filter(models.LocationPing.user_id == user_id)
            .order_by(models.LocationPing.timestamp.desc())
            .first()
        )
        if last is not None:
            check_zone_transitions(db, user, last.lat, last.lng, notify=False)


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


def check_zone_transitions(
    db: Session,
    user: models.User,
    lat: float,
    lng: float,
    *,
    notify: bool = True,
    recipients_override: list[str] | None = None,
) -> list[tuple[str, int]]:
    """Compara la posición contra cada zona del grupo y avisa al entrar/salir.

    notify=False actualiza el estado sin mandar nada (resincronizar tras el modo prueba).
    recipients_override sustituye a las preferencias por zona de cada uno: en el modo prueba el
    admin elige a mano a quién le llega el aviso, en vez de que lo decida quién lo tenga activado.
    Devuelve (texto del aviso, nº de destinatarios) por cada transición detectada.
    """
    report: list[tuple[str, int]] = []
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

        if is_inside == was_inside:
            continue

        verb = "ha entrado en" if is_inside else "ha salido de"
        body = f"{user.display_name} {verb} {zone.name}"
        if recipients_override is not None:
            recipients = [uid for uid in recipients_override if uid != user.id]
        else:
            recipients = _notification_recipients(db, user.group_id, user.id, zone.id, is_inside)
        report.append((body, len(recipients)))

        if notify:
            push.send_to_users(
                db,
                user_ids=recipients,
                title=zone.name,
                body=body,
                data={"type": "zone_transition", "zone_id": zone.id, "user_id": user.id, "entered": is_inside},
                # Lo pinta el sistema del móvil que lo recibe, sin arrancar su app: es el único
                # push que puede llegar tarde y seguir importando (el resto —hacer sonar,
                # emergencia— necesita ejecutar código nuestro sí o sí).
                system_notification=True,
            )

    db.commit()
    return report
