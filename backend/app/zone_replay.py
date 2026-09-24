"""Por que a fulanito no le llego el aviso de una zona.

Vuelve a pasar los pings YA guardados de un usuario por la misma regla que decide entradas y
salidas (app.geofence), y ensena ping a ping la distancia, la precision, el umbral que le tocaba
y que decidio. No hay historial de transiciones en la base de datos: esto lo reconstruye a partir
de los pings, que se guardan 30 dias.

Uso (dentro del contenedor, que es donde esta la base de datos):

    docker compose exec lokate-api python -m app.zone_replay Luci "Instituto" --hours 24
    docker compose exec lokate-api python -m app.zone_replay Luci --list

Sin argumentos de zona lista las zonas del grupo. --all ensena todos los pings, no solo los que
cambian algo.
"""
import argparse
from datetime import datetime, timedelta, timezone

from . import models
from .database import SessionLocal
from .geofence import ZONE_EXIT_MARGIN_M, _accuracy_margin, distance_m


def _find_user(db, needle: str) -> models.User:
    needle = needle.lower()
    users = db.query(models.User).all()
    match = [u for u in users if needle in (u.username or "").lower() or needle in (u.display_name or "").lower()]
    if not match:
        raise SystemExit("No hay ningun usuario que contenga '%s'. Hay: %s" % (
            needle, ", ".join(sorted(u.display_name for u in users))))
    return match[0]


def _find_zone(db, user: models.User, needle: str | None) -> models.Zone:
    zones = db.query(models.Zone).filter(models.Zone.group_id == user.group_id).all()
    if not zones:
        raise SystemExit("Ese grupo no tiene zonas.")
    match = [z for z in zones if needle and needle.lower() in z.name.lower()]
    if not match:
        raise SystemExit("Zonas del grupo: " + ", ".join("%s (%.0f m)" % (z.name, z.radius_m) for z in zones))
    return match[0]


def replay(db, user: models.User, zone: models.Zone, hours: int, show_all: bool) -> list[str]:
    """Una linea por ping interesante. El estado de partida sale del primer ping con el radio
    pelado: no hay forma de saber que creia el servidor hace X horas, y a partir del segundo
    ping la histeresis ya manda igual que en produccion."""
    since = datetime.now(timezone.utc) - timedelta(hours=hours)
    pings = (
        db.query(models.LocationPing)
        .filter(models.LocationPing.user_id == user.id, models.LocationPing.timestamp >= since)
        .order_by(models.LocationPing.timestamp)
        .all()
    )
    if not pings:
        return ["Sin pings de %s en las ultimas %d h (se guardan 30 dias)." % (user.display_name, hours)]

    lines = [
        "%s vs zona '%s' (radio %.0f m) - %d pings en %d h" % (
            user.display_name, zone.name, zone.radius_m, len(pings), hours),
        "entrar: <= radio - margen   salir: > radio + %d + margen   margen = min(precision, 150, radio/2)" % ZONE_EXIT_MARGIN_M,
        "",
    ]
    inside = distance_m(pings[0].lat, pings[0].lng, zone.lat, zone.lng) <= zone.radius_m
    lines.append("estado de partida: %s" % ("DENTRO" if inside else "FUERA"))

    blocked = 0
    for ping in pings:
        distance = distance_m(ping.lat, ping.lng, zone.lat, zone.lng)
        margin = _accuracy_margin(ping.accuracy, zone.radius_m)
        threshold = zone.radius_m + ZONE_EXIT_MARGIN_M + margin if inside else zone.radius_m - margin
        now_inside = distance <= threshold

        # "Banda": con precision perfecta el ping habria cambiado el estado, y el margen lo ha
        # frenado. Es justo el caso de "no me llego el aviso".
        plain = distance <= (zone.radius_m + ZONE_EXIT_MARGIN_M if inside else zone.radius_m)
        in_band = now_inside == inside and plain != inside
        if in_band:
            blocked += 1

        if now_inside != inside:
            verdict = "*** %s -> AVISO" % ("ENTRADA" if now_inside else "SALIDA")
        elif in_band:
            verdict = "BANDA (no decide: sin el margen habria %s)" % ("salido" if inside else "entrado")
        elif show_all:
            verdict = "dentro" if now_inside else "fuera"
        else:
            inside = now_inside
            continue

        lines.append("%s  d=%4.0f m  +-%s  umbral %4.0f m  %s" % (
            ping.timestamp.strftime("%d/%m %H:%M:%S"),
            distance,
            ("%3.0f m" % ping.accuracy) if ping.accuracy else " s/d ",
            threshold,
            verdict,
        ))
        inside = now_inside

    lines.append("")
    lines.append("estado final del repaso: %s | pings frenados por el margen: %d" % (
        "DENTRO" if inside else "FUERA", blocked))
    state = db.get(models.ZoneState, (user.id, zone.id))
    lines.append("estado que tiene guardado el servidor: %s" % (
        ("DENTRO" if state.is_inside else "FUERA") if state else "todavia ninguno"))
    return lines


def main() -> None:
    parser = argparse.ArgumentParser(description="Por que no llego el aviso de zona")
    parser.add_argument("user", help="parte del nombre o del usuario")
    parser.add_argument("zone", nargs="?", help="parte del nombre de la zona (sin esto, las lista)")
    parser.add_argument("--hours", type=int, default=24)
    parser.add_argument("--all", action="store_true", help="ensena tambien los pings que no cambian nada")
    args = parser.parse_args()

    db = SessionLocal()
    try:
        user = _find_user(db, args.user)
        zone = _find_zone(db, user, args.zone)
        print("\n".join(replay(db, user, zone, args.hours, args.all)))
    finally:
        db.close()


if __name__ == "__main__":
    main()
