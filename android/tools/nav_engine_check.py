# -*- coding: utf-8 -*-
"""Self-check del motor de navegacion por voz: el mismo algoritmo que nav/NavGeometry.kt y
nav/NavGuide.kt, portado aqui porque el modulo Android no tiene dependencias de test.

Lo que se comprueba de verdad es la contabilidad de los avisos: que cada maniobra se anuncia una
sola vez por umbral y de lejos a cerca, que un salto de posicion (un tunel, el GPS perdido en un
semaforo) no hace decir el aviso lejano DESPUES del cercano, que la llegada se dice una vez y que
salirse de la ruta tarda en contar lo que tiene que tardar.

Si se tocan los umbrales de NavVerbosity o el enganche a la linea de NavGeometry, esto avisa.

Run: python tools/nav_engine_check.py
"""
import math

MPDLAT, MPDLNG = 110574.0, 111320.0
STEP_PASSED_M, ARRIVE_M = 8.0, 20.0
OFF_CAR, GRACE = 45.0, 8000.0


def project(lat, lng, a, b):
    sc = math.cos(math.radians(a[0]))
    bx = (b[1] - a[1]) * MPDLNG * sc
    by = (b[0] - a[0]) * MPDLAT
    px = (lng - a[1]) * MPDLNG * sc
    py = (lat - a[0]) * MPDLAT
    len2 = bx * bx + by * by
    t = 0.0 if len2 == 0 else min(1.0, max(0.0, (px * bx + py * by) / len2))
    dx, dy = px - t * bx, py - t * by
    return t * math.sqrt(len2), math.sqrt(dx * dx + dy * dy)


class Track(object):
    def __init__(self, points):
        self.points = points
        self.cum = [0.0] * len(points)
        for i in range(1, len(points)):
            sc = math.cos(math.radians(points[i - 1][0]))
            dx = (points[i][1] - points[i - 1][1]) * MPDLNG * sc
            dy = (points[i][0] - points[i - 1][0]) * MPDLAT
            self.cum[i] = self.cum[i - 1] + math.sqrt(dx * dx + dy * dy)
        self.total = self.cum[-1]

    def meters_to(self, i):
        return self.cum[max(0, min(i, len(self.cum) - 1))]

    def snap(self, lat, lng, from_index):
        start = max(0, min(from_index - 1, len(self.points) - 2))
        best = (start, self.cum[start], float("inf"))
        for i in range(start, len(self.points) - 1):
            along, away = project(lat, lng, self.points[i], self.points[i + 1])
            if away < best[2]:
                best = (i, self.cum[i] + along, away)
        return best

    def point_at(self, meters):
        if meters <= 0:
            return self.points[0]
        if meters >= self.total:
            return None
        i = 0
        while i < len(self.cum) - 2 and self.cum[i + 1] < meters:
            i += 1
        tramo = self.cum[i + 1] - self.cum[i]
        t = 0.0 if tramo <= 0 else (meters - self.cum[i]) / tramo
        a, b = self.points[i], self.points[i + 1]
        return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


SLOTS = 8


def redondo(m):
    if m >= 300:
        return int(round(m / 100.0)) * 100
    if m >= 50:
        return int(round(m / 10.0)) * 10
    return int(round(m))


class Guide(object):
    def __init__(self, track, steps, thresholds, now_meters):
        self.track, self.steps = track, steps
        self.thresholds, self.now_meters = thresholds, now_meters
        self.step_index, self.index = 0, 0
        self.spoken, self.off_since = set(), 0
        self.arrived, self.started = False, False
        self.luego = None
        if steps:
            self.silence(len(steps) - 1)

    def silence(self, step):
        for j in range(SLOTS):
            self.spoken.add(step * SLOTS + j)

    def aviso(self, to_maneuver):
        for k in range(len(self.thresholds) - 1, -1, -1):
            if self.thresholds[k] < to_maneuver:
                continue
            key = self.step_index * SLOTS + k
            if key in self.spoken:
                return None
            for j in range(0, k + 1):
                self.spoken.add(self.step_index * SLOTS + j)
            return ("maniobra", redondo(to_maneuver), self.step_index,
                    self.thresholds[k] <= self.now_meters)
        return None

    def update(self, lat, lng, now_ms):
        index, from_start, off = self.track.snap(lat, lng, self.index)
        self.index = index
        if off > OFF_CAR:
            if self.off_since == 0:
                self.off_since = now_ms
        else:
            self.off_since = 0
        lost = self.off_since != 0 and now_ms - self.off_since >= GRACE

        while (self.step_index < len(self.steps) - 1 and
               from_start > self.track.meters_to(self.steps[self.step_index]) + STEP_PASSED_M):
            self.step_index += 1

        to_maneuver = max(0.0, self.track.meters_to(self.steps[self.step_index]) - from_start)
        remaining = max(0.0, self.track.total - from_start)
        habla = []
        if not self.started:
            self.started = True
            self.silence(0)
            habla.append(("arranque",))
        elif not self.arrived and not lost:
            a = self.aviso(to_maneuver)
            if a:
                habla.append(a)
        # El "Despues: ..." del cartel: la maniobra siguiente, solo con la de ahora encima y
        # nunca el "ha llegado" final (que ese lo canta la llegada).
        self.luego = (self.step_index + 1
                      if to_maneuver <= self.now_meters and self.step_index + 1 < len(self.steps) - 1
                      else None)
        if not self.arrived and remaining <= ARRIVE_M:
            self.arrived = True
            habla.append(("llegada",))
        return habla, lost, self.step_index

# Una recta de 1 km hacia el este, un punto cada 50 m.
pts = [(40.0, -3.70 + i * (50.0 / (MPDLNG * math.cos(math.radians(40.0))))) for i in range(21)]
track = Track(pts)
assert abs(track.total - 1000.0) < 1.0, track.total
# Pasos: salir (0 m), girar (500 m) y llegar (1000 m) -> indices de punto 0, 10 y 20.
steps = [0, 10, 20]

def test_viaje_entero():
    """Un viaje entero en coche con verbosidad normal: [500, 100] y "ya" por debajo de 120"""
    g = Guide(track, steps, [500, 100], 120)
    dicho, t = [], 0
    m = 0.0
    while m <= 1000.0:
        p = track.point_at(m) or pts[-1]
        habla, lost, si = g.update(p[0], p[1], t)
        dicho += habla
        assert not lost, "una ruta recorrida por su linea no puede dar fuera de ruta"
        m += 25.0
        t += 1000

    tipos = [d[0] for d in dicho]
    assert tipos[0] == "arranque" and tipos.count("arranque") == 1, tipos
    assert tipos.count("llegada") == 1, tipos
    maniobras = [d for d in dicho if d[0] == "maniobra"]
    # Dos avisos para el giro (500 y 100) y ninguno para la llegada (ese lo dice "llegada").
    assert len(maniobras) == 2, maniobras
    assert [d[2] for d in maniobras] == [1, 1], maniobras
    assert maniobras[0][1] > maniobras[1][1], maniobras
    assert maniobras[0][3] is False and maniobras[1][3] is True, maniobras
    # Numeros redondos de los que se dicen en voz alta, no la distancia cruda del GPS.
    assert maniobras[0][1] % 100 == 0 and maniobras[1][1] % 10 == 0, maniobras
    # Cada aviso cae en su umbral o justo por debajo: el coche no pasa por el metro exacto.
    assert 300 <= maniobras[0][1] <= 500 and maniobras[1][1] <= 100, maniobras


def test_pausa_y_reanudar():
    """Pausar y reanudar no repite ni se salta nada: el motor sigue vivo y el contador de metros
    tambien, que es lo que hace NavigationService (no toca el guide y guarda simMeters)."""
    def viaje(pausa_en=None):
        g = Guide(track, steps, [500, 100], 120)
        dicho, m, t = [], 0.0, 0
        while m <= 1000.0:
            if pausa_en is not None and abs(m - pausa_en) < 1e-9:
                # En pausa no entra ninguna posicion al motor, y el reloj del viaje no corre:
                # al reanudar se sigue por el mismo metro.
                t += 60 * 1000
            p = track.point_at(m) or pts[-1]
            habla, _, _ = g.update(p[0], p[1], t)
            dicho += habla
            m += 25.0
            t += 1000
        return dicho

    assert viaje(pausa_en=200.0) == viaje(), (viaje(pausa_en=200.0), viaje())
    # Y una pausa justo antes del aviso de 100 m del giro no se lo come.
    assert viaje(pausa_en=425.0) == viaje(), viaje(pausa_en=425.0)


def test_el_muneco_va_por_la_linea():
    """La simulacion pone al muneco EN la ruta, no al lado: cada posicion a menos de un palmo"""
    m, idx = 0.0, 0
    while m < track.total:
        p = track.point_at(m)
        assert p is not None, m
        idx, recorrido, fuera = track.snap(p[0], p[1], idx)
        assert fuera < 0.1, (m, fuera)
        assert abs(recorrido - m) < 0.1, (m, recorrido)
        m += 7.0


def test_salto_de_posicion():
    """Un salto de posicion (tunel, semaforo con el GPS perdido): de 600 a 90 m del giro"""
    g = Guide(track, steps, [500, 100], 120)
    g.update(pts[0][0], pts[0][1], 0)
    habla, _, _ = g.update(*track.point_at(410.0), now_ms=1000)  # 90 m del giro
    assert len(habla) == 1 and habla[0][1] == 90 and habla[0][3] is True, habla
    # Y el aviso de 500, que ya no sirve, no sale despues.
    habla, _, _ = g.update(*track.point_at(450.0), now_ms=2000)
    assert habla == [], habla


def test_fuera_de_ruta():
    """Fuera de ruta: 100 m al norte de la linea, pero solo cuenta tras el margen"""
    g = Guide(track, steps, [500, 100], 120)
    g.update(pts[0][0], pts[0][1], 0)
    fuera = (pts[4][0] + 100.0 / MPDLAT, pts[4][1])
    _, lost, _ = g.update(fuera[0], fuera[1], 1000)
    assert not lost, "un bandazo del GPS no puede recalcular la ruta"
    _, lost, _ = g.update(fuera[0], fuera[1], 1000 + int(GRACE))
    assert lost, "ocho segundos fuera si son estar fuera"


def test_verbosidad():
    """Las justas habla menos que abundantes, que para eso estan"""
    def cuenta(thresholds):
        g = Guide(track, steps, thresholds, 120)
        n, m, t = 0, 0.0, 0
        while m <= 1000.0:
            p = track.point_at(m) or pts[-1]
            habla, _, _ = g.update(p[0], p[1], t)
            n += len([h for h in habla if h[0] == "maniobra"])
            m += 25.0
            t += 1000
        return n

    assert cuenta([200]) < cuenta([500, 100]) < cuenta([1000, 400, 150, 40]), (
        cuenta([200]), cuenta([500, 100]), cuenta([1000, 400, 150, 40]))


def test_el_despues():
    """El "Despues: ..." sale solo con la maniobra de ahora encima, y nunca es la llegada."""
    g = Guide(track, steps, [500, 100], 120)
    # Arrancando (metro 0): la de ahora es la de salir, asi que ya se ve la del giro.
    g.update(pts[0][0], pts[0][1], 0)
    assert g.luego == 1, g.luego
    # Metro 200, con el giro a 300 m: demasiado lejos, el cartel no lo ensena.
    q = track.point_at(200.0)
    g.update(q[0], q[1], 1000)
    assert g.luego is None, g.luego
    # Metro 450, con el giro encima: lo siguiente es el "ha llegado" y ese no se anuncia aqui.
    q = track.point_at(450.0)
    g.update(q[0], q[1], 2000)
    assert g.luego is None, g.luego


if __name__ == "__main__":
    test_viaje_entero()
    test_el_muneco_va_por_la_linea()
    test_pausa_y_reanudar()
    test_salto_de_posicion()
    test_fuera_de_ruta()
    test_verbosidad()
    test_el_despues()
    print("OK: avisos una sola vez, de lejos a cerca, llegada unica, el despues a tiempo, "
          "pausa sin repetir y fuera de ruta con su margen")
