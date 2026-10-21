# -*- coding: utf-8 -*-
"""El cerco del buscador: espejo de widenBox/boxCenter de PlaceSearchField.kt.

Uso: python tools/search_box_check.py

Los tres intentos del buscador (lo que se ve -> lo que se ve ensanchado -> el mundo) no se
pueden probar aqui porque necesitan red; lo que se prueba es la cuenta del ensanche, que es
donde se mete la pata: un cerco que no crece deja la busqueda cayendo al mundo, y uno que crece
de mas trae resultados de la otra provincia.
"""
import io
import os
import re

KT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                  "..", "app", "src", "main", "java", "com", "dskmusic", "lokate",
                  "ui", "common", "PlaceSearchField.kt")


def constante(nombre):
    """Los numeros se leen del Kotlin: si alguien afloja el cerco de mas, esto lo canta."""
    fuente = io.open(KT, encoding="utf-8").read()
    return float(re.search(r"private const val " + nombre + r" = ([\d.]+)", fuente).group(1))


MIN_HALF_SPAN_DEG = constante("MIN_HALF_SPAN_DEG")
WIDEN_TIMES = constante("WIDEN_TIMES")


def box_center(box):
    oeste, norte, este, sur = [float(x) for x in box.split(",")]
    return ((norte + sur) / 2, (oeste + este) / 2)


def widen_box(box, veces=WIDEN_TIMES):
    oeste, norte, este, sur = [float(x) for x in box.split(",")]
    lon_medio = (oeste + este) / 2
    lat_medio = (norte + sur) / 2
    ancho = max(abs(este - oeste) / 2 * veces, MIN_HALF_SPAN_DEG)
    alto = max(abs(norte - sur) / 2 * veces, MIN_HALF_SPAN_DEG)
    return (max(lon_medio - ancho, -180.0), min(lat_medio + alto, 90.0),
            min(lon_medio + ancho, 180.0), max(lat_medio - alto, -90.0))


def dentro(box4, lat, lng):
    oeste, norte, este, sur = box4
    return oeste <= lng <= este and sur <= lat <= norte


def main():
    # 1. Mirando un barrio de Madrid (unos 2,5 km de lado), el ensanche tiene que llegar a la
    #    otra punta de la ciudad: ahi esta el Corte Ingles que no salia.
    barrio = "-3.72,40.43,-3.69,40.41"
    assert box_center(barrio) == (40.42, -3.705), box_center(barrio)
    ancho = widen_box(barrio)
    assert dentro(ancho, 40.4530, -3.6883), ancho   # Nuevos Ministerios, 4 km al norte
    assert dentro(ancho, 40.6667, -4.0010), ancho   # El Escorial, 45 km: sigue siendo "por aqui"
    assert not dentro(ancho, 41.6561, -4.7243), ancho   # Valladolid, 160 km: eso ya no es "cerca"

    # 2. Mirando una calle de cerca (200 m), doce veces una calle sigue siendo una calle: aqui
    #    manda el minimo (MIN_HALF_SPAN_DEG).
    calle = "-3.7045,40.4175,-3.7025,40.4165"
    estrecho = widen_box(calle)
    assert dentro(estrecho, 40.4530, -3.6883), estrecho
    assert round(estrecho[1] - box_center(calle)[0], 6) == MIN_HALF_SPAN_DEG, estrecho

    # 3. Nada se sale del mundo, ni mirando el Polo Norte.
    polo = widen_box("-10,89.9,10,89.5")
    assert polo[1] <= 90.0 and polo[3] >= -90.0, polo
    borde = widen_box("179.0,1.0,179.9,0.1")
    assert borde[0] >= -180.0 and borde[2] <= 180.0, borde

    # 4. Ordenar por distancia al centro: el de al lado antes que el "importante" de lejos.
    centro = box_center(barrio)
    resultados = [("El Corte Ingles Preciados", 40.4194, -3.7036),
                  ("El Corte Ingles Castellana", 40.4530, -3.6883)]
    cerca_primero = sorted(resultados, key=lambda r: (r[1] - centro[0]) ** 2 + (r[2] - centro[1]) ** 2)
    assert cerca_primero[0][0] == "El Corte Ingles Preciados", cerca_primero

    print("cerco del buscador ok")


if __name__ == "__main__":
    main()
