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

La misma funcion replay() la usan la pagina "Diagnostico de zonas" del panel web
(app/admin.py) y el endpoint que pinta esa pantalla en la app (routers/admin_api.py): es solo
lectura, reconstruye el estado en un objeto aparte y no toca zone_states.
"""
import argparse
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

from . import models
from .database import SessionLocal
from .geofence import (
    CONFIRM_ACCURACY_M,
    MAX_TRUSTED_ACCURACY_M,
    MIN_CONFIRM_GAP_S,
    WIFI_ANCHOR_GRACE_MIN,
    ZONE_EXIT_MARGIN_M,
    _wifi_anchor,
    beyond_doubt,
    confirms,
    distance_m,
    evaluate,
)


# Tope de horas de un repaso. A 30 s por ping son ~2.900 filas al dia: 72 h es lo mas que
# merece la pena recorrer y pintar de una vez (y lo que mas atras se llega mirando "que paso
# ayer"). El CLI no lo aplica: ahi quien escribe el numero sabe lo que pide.
MAX_HOURS = 72


def clamp_hours(hours: int | None) -> int:
    """Horas que de verdad se van a repasar. Lo que llega de un formulario o de una URL."""
    return max(1, min(hours or 24, MAX_HOURS))


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
        "entrar: distancia + margen <= radio (margen = min(precision, 150, radio/2))",
        "salir: distancia - precision > radio + %d   estando fuera, precision > radio: el ping no decide" % ZONE_EXIT_MARGIN_M,
        "precision > %d m: el cambio espera a que otro ping lo repita, salvo si no hay ninguna duda" % CONFIRM_ACCURACY_M,
        "   ...y ese otro ping tiene que llegar %d s despues: la copia del mismo fix no confirma" % MIN_CONFIRM_GAP_S,
        "precision > %d m: el ping no decide nada (antenas: declaran el error de la antena, no el tuyo)" % MAX_TRUSTED_ACCURACY_M,
    ]
    if zone.wifi_ssid_list:
        lines.append(
            "wifi de la zona (%s): manda sobre el fix, y aguanta %d min desde el ultimo ping con ella"
            % (", ".join(zone.wifi_ssid_list), WIFI_ANCHOR_GRACE_MIN)
        )
    else:
        lines.append("esta zona no tiene wifi apuntada: va solo por posicion")
    lines.append("")
    inside = distance_m(pings[0].lat, pings[0].lng, zone.lat, zone.lng) <= zone.radius_m
    lines.append("estado de partida: %s" % ("DENTRO" if inside else "FUERA"))

    blocked = 0
    ignored = 0
    unconfirmed = 0
    pending = None
    pending_at = None
    # El reloj del margen del ancla, reconstruido ping a ping igual que lo lleva el servidor en
    # zone_states.wifi_seen_at. Los pings anteriores a esta version no guardaron el SSID: salen
    # como si no hubiera habido wifi, que es lo honesto (no se sabe).
    visto = SimpleNamespace(wifi_seen_at=None)
    for ping in pings:
        distance = distance_m(ping.lat, ping.lng, zone.lat, zone.lng)

        # Las mismas funciones que deciden en produccion, no una copia: asi el repaso no miente.
        anchored = _wifi_anchor(user, zone, visto, ping.wifi_ssid, distance, now=ping.timestamp)
        if ping.wifi_ssid and ping.wifi_ssid in zone.wifi_ssid_list:
            visto.wifi_seen_at = ping.timestamp
        verdict, threshold = (
            (True, zone.radius_m) if anchored else evaluate(distance, ping.accuracy, zone.radius_m, inside)
        )
        if verdict is None:
            ignored += 1
            if show_all:
                motivo = (
                    "precision > techo %.0f m" % MAX_TRUSTED_ACCURACY_M
                    if ping.accuracy and ping.accuracy > MAX_TRUSTED_ACCURACY_M
                    else "estando fuera, precision > radio %.0f m" % zone.radius_m
                )
                lines.append("%s  d=%4.0f m  +-%3.0f m  IGNORADO (%s)" % (
                    ping.timestamp.strftime("%d/%m %H:%M:%S"), distance, ping.accuracy, motivo))
            continue
        now_inside = verdict

        # "Banda": con precision perfecta el ping habria cambiado el estado, y el margen lo ha
        # frenado. Es justo el caso de "no me llego el aviso".
        plain = distance <= (threshold if inside else zone.radius_m)
        in_band = now_inside == inside and plain != inside
        if in_band:
            blocked += 1

        if now_inside == inside:
            pending = None
            pending_at = None
            verdict = "BANDA (no decide: sin el margen habria %s)" % ("salido" if inside else "entrado") if in_band \
                else ("dentro" if now_inside else "fuera")
        elif (
            not anchored
            and ping.accuracy is not None
            and ping.accuracy > CONFIRM_ACCURACY_M
            and not confirms(pending, pending_at, now_inside, ping.timestamp)
            and not beyond_doubt(distance, threshold, ping.accuracy, now_inside)
        ):
            # Cambio a medio confirmar: apuntado, sin aviso, esperando al siguiente ping.
            repetido = pending == now_inside
            if not repetido:
                pending, pending_at = now_inside, ping.timestamp
            unconfirmed += 1
            verdict = "PENDIENTE de confirmar (%s con precision +-%.0f m)%s" % (
                "entrada" if now_inside else "salida", ping.accuracy,
                "  [copia del mismo fix: no confirma]" if repetido else "")
        else:
            pending = None
            pending_at = None
            verdict = "*** %s -> AVISO" % ("ENTRADA" if now_inside else "SALIDA")
            inside = now_inside

        if "AVISO" not in verdict and "PENDIENTE" not in verdict and not in_band and not show_all:
            continue

        lines.append("%s  d=%4.0f m  +-%s  umbral %4.0f m  %s%s" % (
            ping.timestamp.strftime("%d/%m %H:%M:%S"),
            distance,
            ("%3.0f m" % ping.accuracy) if ping.accuracy else " s/d ",
            threshold,
            verdict,
            ("  [ANCLA wifi '%s']" % ping.wifi_ssid if ping.wifi_ssid in zone.wifi_ssid_list
             else "  [wifi '%s']" % ping.wifi_ssid) if ping.wifi_ssid else "",
        ))

    lines.append("")
    lines.append(
        "estado final del repaso: %s | pings frenados por el margen: %d"
        " | ignorados por precision: %d | cambios sin confirmar: %d" % (
            "DENTRO" if inside else "FUERA", blocked, ignored, unconfirmed))
    state = db.get(models.ZoneState, (user.id, zone.id))
    lines.append("estado que tiene guardado el servidor: %s" % (
        ("DENTRO" if state.is_inside else "FUERA") if state else "todavia ninguno"))
    if state is not None and state.pending_inside is not None:
        lines.append("   (con un cambio a %s esperando que otro ping lo confirme)" % (
            "DENTRO" if state.pending_inside else "FUERA"))
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
