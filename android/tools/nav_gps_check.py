# -*- coding: utf-8 -*-
"""Nadie arranca a navegar de verdad sin pasar por el aviso del GPS.

Uso: python tools/nav_gps_check.py

Con la ubicacion del sistema apagada, NavSession.start(simulate = false) deja el viaje a medias:
el servicio pide posiciones que no llegan, no se publica ningun estado y la pantalla se queda sin
cartel de maniobra ni boton de salir. El arreglo es preguntar antes (GpsRequiredDialog), y lo que
se rompe con el tiempo es que alguien anada otro boton de "navegar" y se salte la pregunta: eso es
lo que vigila esto. Simular no cuenta, que no pide GPS.
"""
import io
import os
import re

RAIZ = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main")
CERCA = 10   # lineas hacia arriba donde tiene que estar la guarda


def arranques():
    """(fichero, linea, texto) de cada NavSession.start que navegue de verdad."""
    salida = []
    for carpeta, _, ficheros in os.walk(RAIZ):
        for f in ficheros:
            if not f.endswith(".kt"):
                continue
            ruta = os.path.join(carpeta, f)
            lineas = io.open(ruta, encoding="utf-8").read().split("\n")
            for i, linea in enumerate(lineas):
                if "NavSession.start(" not in linea:
                    continue
                # La llamada cabe en una linea en todo el proyecto; si alguien la parte, esto
                # deja de verla y el aviso de abajo lo canta.
                assert "simulate" in linea, (f, i + 1, "NavSession.start partido en varias lineas")
                if re.search(r"simulate\s*=\s*false", linea):
                    salida.append((f, i + 1, lineas[max(0, i - CERCA):i + 1]))
    return salida


def main():
    reales = arranques()
    assert reales, "ningun NavSession.start(simulate = false): el boton de navegar ha desaparecido"
    for f, n, antes in reales:
        contexto = "\n".join(antes)
        guardado = ("ConfigCheck.isLocationEnabled" in contexto    # el boton de navegar
                    or "onEnabled" in contexto)                    # el "Encender" del aviso
        assert guardado, (f, n, "arranca a navegar sin comprobar la ubicacion del sistema")
    print("aviso del GPS ok (%d arranques comprobados)" % len(reales))


if __name__ == "__main__":
    main()
