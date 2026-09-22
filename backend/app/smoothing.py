"""Limpieza del historial ANTES de dibujarlo.

Un fix de wifi mal resuelto mete un pico en la ruta: el trazo salta tres kilómetros y vuelve.
Los puntos no se borran de la base (son lo que el móvil dijo y el historial es un registro), se
quitan solo al servirlos para pintar.

Se ejecuta solo con: python -m app.smoothing
"""

from .geofence import distance_m

# Por encima de esto el tramo es imposible: 83 m/s = 300 km/h. Mismo listón que el filtro del
# móvil (LocationForegroundService.MAX_PLAUSIBLE_SPEED_MPS), que pilla estos saltos en origen
# — esto es la red para los que ya están guardados y para los que llegaron de una versión
# anterior de la app.
MAX_SPEED_MPS = 83.0

# Saltos cortos no se miran: dos fixes seguidos separados 200 m en dos segundos es ruido normal
# del GPS, no un pico, y quitarlos dejaría el trazo lleno de agujeros.
MIN_SPIKE_M = 500.0


def _speed_mps(a, b) -> float:
    seconds = abs((b.timestamp - a.timestamp).total_seconds())
    if seconds <= 0:
        return 0.0
    return distance_m(a.lat, a.lng, b.lat, b.lng) / seconds


def drop_outliers(points: list) -> list:
    """Quita los puntos a los que se llega y de los que se vuelve a velocidad imposible.

    Que las DOS cosas se cumplan a la vez es lo que distingue un pico (ida y vuelta) de un
    cambio real de sitio (se va y se sigue desde allí). Espera los puntos ordenados por hora.

    ponytail: el primero y el último no se tocan — no tienen vecino por un lado con el que
    comparar. Si alguna vez molesta un pico justo al final del recorrido, ahí está el arreglo.
    """
    if len(points) < 3:
        return points
    kept = [points[0]]
    for i in range(1, len(points) - 1):
        previous, current, following = kept[-1], points[i], points[i + 1]
        if distance_m(previous.lat, previous.lng, current.lat, current.lng) < MIN_SPIKE_M:
            kept.append(current)
            continue
        if _speed_mps(previous, current) > MAX_SPEED_MPS and _speed_mps(current, following) > MAX_SPEED_MPS:
            continue
        kept.append(current)
    kept.append(points[-1])
    return kept


if __name__ == "__main__":
    from datetime import datetime, timedelta, timezone
    from types import SimpleNamespace

    start = datetime(2026, 9, 22, 10, 0, tzinfo=timezone.utc)

    def point(minutes, lat, lng):
        return SimpleNamespace(lat=lat, lng=lng, timestamp=start + timedelta(minutes=minutes))

    # Paseo por Madrid: un punto cada minuto, unos 100 m entre uno y otro.
    walk = [point(i, 40.4168 + i * 0.001, -3.7038) for i in range(5)]
    assert drop_outliers(walk) == walk, "un paseo normal no se toca"

    # Mismo paseo con un pico en medio: a Lisboa y de vuelta en un minuto.
    spike = walk[:2] + [point(2, 38.7223, -9.1393)] + walk[3:]
    cleaned = drop_outliers(spike)
    assert len(cleaned) == len(walk) - 1, cleaned
    assert all(p.lng == -3.7038 for p in cleaned), "el pico de Lisboa sigue ahí"

    # Mudanza de verdad: se va lejos y SIGUE allí. No es un pico, no se quita.
    trip = walk[:2] + [point(2, 38.7223, -9.1393), point(3, 38.7230, -9.1400), point(4, 38.7240, -9.1410)]
    assert drop_outliers(trip) == trip, "irse a otra ciudad y quedarse no es un pico"

    # Sin vecinos con los que comparar no se decide nada.
    assert drop_outliers(walk[:2]) == walk[:2]

    print("ok")
