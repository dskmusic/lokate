"""Radiografía de un día de historial: qué saltos tiene y cuáles se come el filtro de picos.

No toca nada: solo lee y cuenta. Está para poder afinar los listones de smoothing.py con datos
reales en vez de a ojo (los picos de wifi/antena no se parecen en todos los sitios ni con todos
los ritmos de ping).

    docker compose exec lokate-api python -m app.inspect_spikes Luci 2026-09-22

ponytail: script suelto y no endpoint — esto lo mira una persona con acceso al servidor cuando
algo se ve raro, no la app.
"""

import statistics
import sys
from datetime import datetime, timedelta

from . import models, smoothing
from .database import SessionLocal
from .geofence import distance_m

# Por debajo de esto no es un salto, es el temblor normal del GPS.
JUMP_M = 300.0
TOP = 15


def route_meters(points) -> float:
    return sum(
        distance_m(a.lat, a.lng, b.lat, b.lng)
        for a, b in zip(points, points[1:])
    )


def main(name: str, day: str) -> None:
    start = datetime.strptime(day, "%Y-%m-%d")
    db = SessionLocal()
    try:
        user = (
            db.query(models.User)
            .filter(models.User.display_name.ilike(f"%{name}%"))
            .first()
        )
        if user is None:
            print(f"No hay nadie que se llame como '{name}'")
            return
        points = (
            db.query(models.LocationPing)
            .filter(
                models.LocationPing.user_id == user.id,
                models.LocationPing.timestamp >= start,
                models.LocationPing.timestamp < start + timedelta(days=1),
            )
            .order_by(models.LocationPing.timestamp.asc())
            .all()
        )
    finally:
        db.close()

    print(f"{user.display_name} - {day} - ritmo elegido: {user.location_frequency}")
    if len(points) < 3:
        print(f"Solo {len(points)} puntos: no hay nada que mirar")
        return

    gaps = [
        (b.timestamp - a.timestamp).total_seconds()
        for a, b in zip(points, points[1:])
    ]
    cleaned = smoothing.drop_outliers(points)
    print(f"puntos: {len(points)}  -> tras el filtro: {len(cleaned)} ({len(points) - len(cleaned)} quitados)")
    print(f"km: {route_meters(points) / 1000:.1f} -> tras el filtro: {route_meters(cleaned) / 1000:.1f}")
    print(f"segundos entre pings: mediana {statistics.median(gaps):.0f}, minimo {min(gaps):.0f}, maximo {max(gaps):.0f}")

    jumps = []
    for a, b in zip(points, points[1:]):
        metros = distance_m(a.lat, a.lng, b.lat, b.lng)
        if metros < JUMP_M:
            continue
        seconds = max((b.timestamp - a.timestamp).total_seconds(), 1.0)
        jumps.append((metros, seconds, a, b))
    jumps.sort(reverse=True, key=lambda j: j[0])
    print(f"saltos de mas de {JUMP_M:.0f} m: {len(jumps)} (los {TOP} mayores)")
    print("hora      metros  seg   km/h   precision(m)")
    for metros, seconds, a, b in jumps[:TOP]:
        precision = f"{a.accuracy or -1:.0f} -> {b.accuracy or -1:.0f}"
        print(
            f"{b.timestamp:%H:%M:%S}  {metros:6.0f}  {seconds:4.0f}  {metros / seconds * 3.6:5.0f}   {precision}"
        )


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1], sys.argv[2])
