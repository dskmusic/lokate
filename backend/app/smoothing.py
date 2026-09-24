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

# El pico típico de wifi/antena no es imposible por velocidad: la posición se va dos o tres
# kilómetros y vuelve, y en dos minutos eso son 90 km/h, que en coche es normal. Lo que sí es
# imposible es hacer ese viaje de IDA Y VUELTA a esa media: para eso hay que frenar, dar la
# vuelta y volver. Por eso el segundo listón es mucho más bajo que MAX_SPEED_MPS.
# ponytail: este es EL botón de calibración de todo el filtro. Subirlo deja pasar más picos;
# bajarlo se come alguna ida y vuelta de verdad muy rápida. Solo afecta a lo que se DIBUJA: el
# punto sigue guardado. Con 8 m/s se va lo que se veía en el historial de Waterford (saltos de
# kilómetro y medio cruzando el río con pings de 2 min = 47 km/h de media ida y vuelta); el
# precio es que un coche que se aleje más de 500 m y vuelva dentro de dos pings tampoco se
# dibuja. Para decidirlo con datos y no a ojo: python -m app.inspect_spikes <nombre> <día>.
ROUND_TRIP_SPEED_MPS = 8.0  # 29 km/h de media contando la vuelta

# Cuánto tiene que acercarse el punto siguiente al anterior para decir que "ha vuelto por donde
# vino". La mitad del salto: si vuelve más lejos que eso, es que se fue de verdad.
RETURN_RATIO = 0.5


def _speed_mps(a, b) -> float:
    seconds = abs((b.timestamp - a.timestamp).total_seconds())
    if seconds <= 0:
        return 0.0
    return distance_m(a.lat, a.lng, b.lat, b.lng) / seconds


def _is_round_trip(previous, current, following, out_m: float) -> bool:
    """El punto se va lejos y el siguiente vuelve prácticamente al sitio del que salió, y todo
    eso a una media que solo se consigue sin frenar ni dar la vuelta: no fue nadie, fue el wifi.
    """
    back_m = distance_m(current.lat, current.lng, following.lat, following.lng)
    origin_m = distance_m(previous.lat, previous.lng, following.lat, following.lng)
    if origin_m > out_m * RETURN_RATIO:
        return False
    seconds = abs((following.timestamp - previous.timestamp).total_seconds())
    if seconds <= 0:
        return True
    return (out_m + back_m) / seconds > ROUND_TRIP_SPEED_MPS


def drop_outliers(points: list) -> list:
    """Quita los picos: puntos a los que se va y de los que se vuelve sin que nadie se moviera.

    Dos formas de detectarlos, y basta con una: que llegar y salir del punto exija una velocidad
    físicamente imposible, o que el trazo salga y vuelva al mismo sitio a una media que no se
    tiene dando la vuelta por el camino (ver [_is_round_trip]). Lo que ninguna de las dos toca es
    un cambio real de sitio: irse y SEGUIR allí. Espera los puntos ordenados por hora.

    ponytail: el primero y el último no se tocan — no tienen vecino por un lado con el que
    comparar. Si alguna vez molesta un pico justo al final del recorrido, ahí está el arreglo.
    """
    if len(points) < 3:
        return points
    kept = [points[0]]
    for i in range(1, len(points) - 1):
        previous, current, following = kept[-1], points[i], points[i + 1]
        out_m = distance_m(previous.lat, previous.lng, current.lat, current.lng)
        if out_m < MIN_SPIKE_M:
            kept.append(current)
            continue
        impossible = (
            _speed_mps(previous, current) > MAX_SPEED_MPS
            and _speed_mps(current, following) > MAX_SPEED_MPS
        )
        if impossible or _is_round_trip(previous, current, following, out_m):
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

    # El caso real de las capturas: en casa, un fix de wifi a 4 km, y otra vez en casa. Ninguna
    # velocidad es imposible (4 km en 2 min son 120 km/h), pero la ida y vuelta sí lo es.
    home = (28.1235, -15.4363)
    at_home = [point(i * 2, *home) for i in range(6)]
    wifi_spike = at_home[:2] + [point(4, 28.1000, -15.4000)] + at_home[3:]
    assert len(drop_outliers(wifi_spike)) == len(at_home) - 1, "el pico de wifi sigue ahí"

    # Mismo desvío, pero con tiempo de sobra para haber ido y vuelto de verdad (40 min): eso es
    # ir a un recado, no un error de medición, y se respeta.
    errand = [point(0, *home), point(20, 28.1000, -15.4000), point(40, *home)]
    assert drop_outliers(errand) == errand, "un recado de ida y vuelta no es un pico"

    # Y dar la vuelta por ciudad (600 m de ida, 600 de vuelta, pings cada 2 min) tampoco se
    # toca: la media contando el frenazo y el giro se queda por debajo del listón.
    turnaround = [point(0, 28.1000, -15.4300), point(2, 28.1054, -15.4300), point(4, 28.1000, -15.4305)]
    assert drop_outliers(turnaround) == turnaround, "dar la vuelta en coche no es un pico"

    # El del historial de Waterford: 1,5 km cruzando el río y vuelta, con pings de 2 minutos.
    # Es el caso que con el listón anterior (14 m/s) se escapaba por los pelos.
    river = [point(0, 52.2593, -7.1101), point(2, 52.2700, -7.0950), point(4, 52.2593, -7.1101)]
    assert len(drop_outliers(river)) == 2, "el salto del río sigue ahí"

    print("ok")
