# -*- coding: utf-8 -*-
"""El encuadre de la ruta: espejo de MapView.frameRoute (ui/map/RoutePlanner.kt).

Uso: python tools/route_frame_check.py

La cuenta es corta pero tiene dos signos faciles de invertir (el margen y el desplazamiento), y
equivocarse no da error: da una ruta cortada por el cartel, que es justo lo que se arreglaba.
Aqui se simula el mapa en pixeles y se comprueba que la ruta acaba entera en el hueco libre.
"""
import io
import os
import re

KT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                  "..", "app", "src", "main", "java", "com", "dskmusic", "lokate",
                  "ui", "map", "MeasureOverlay.kt")

PADDING = int(re.search(r"SNAPSHOT_PADDING_PX = (\d+)",
                        io.open(KT, encoding="utf-8").read()).group(1))


def encuadrar(ancho, alto, arriba, abajo):
    """Devuelve (borde, desplazamiento), igual que frameRoute."""
    tope = max(min(ancho, alto) // 2 - 1, 0)
    borde = min(PADDING + (arriba + abajo) // 2, tope)
    return borde, (abajo - arriba) // 2


def banda_de_la_ruta(alto, borde, desplazar):
    """Donde acaba la ruta en la pantalla: osmdroid la encaja en alto - 2*borde y la centra;
    scrollBy la sube `desplazar` pixeles."""
    medio = (alto - 2 * borde) / 2.0
    return (alto / 2.0 - desplazar - medio, alto / 2.0 - desplazar + medio)


def comprobar(nombre, ancho, alto, arriba, abajo):
    borde, desplazar = encuadrar(ancho, alto, arriba, abajo)
    arriba_ruta, abajo_ruta = banda_de_la_ruta(alto, borde, desplazar)
    assert arriba_ruta >= arriba, (nombre, "se mete debajo del buscador", arriba_ruta, arriba)
    assert abajo_ruta <= alto - abajo, (nombre, "se mete debajo del cartel", abajo_ruta, alto - abajo)
    assert abajo_ruta > arriba_ruta, (nombre, "sin sitio", arriba_ruta, abajo_ruta)
    return borde, desplazar


def main():
    # De pie: buscador ~190 px arriba, cartel de la ruta ~230 px abajo (los de la captura).
    borde, desplazar = comprobar("de pie", 1080, 1700, 190, 230)
    assert desplazar > 0, desplazar   # abajo estorba mas: la ruta tiene que subir
    # Y el hueco se aprovecha entero: lo que sobra por arriba y por abajo es el margen de
    # cortesia de siempre (SNAPSHOT_PADDING_PX), ni un pixel mas.
    arriba_ruta, abajo_ruta = banda_de_la_ruta(1700, borde, desplazar)
    assert round(arriba_ruta - 190) == PADDING, arriba_ruta
    assert round((1700 - 230) - abajo_ruta) == PADDING, abajo_ruta

    # Tumbado: menos alto y el cartel pesa mas en proporcion.
    comprobar("tumbado", 1700, 800, 120, 170)

    # Sin nada que esquivar (la ruta de una foto, sin interfaz encima): como siempre.
    borde, desplazar = comprobar("pelado", 1080, 1700, 0, 0)
    assert (borde, desplazar) == (PADDING, 0), (borde, desplazar)

    # Un cartel disparatado (media pantalla) no deja a osmdroid sin sitio: ahi manda el tope.
    borde, _ = encuadrar(1080, 1700, 0, 1500)
    assert borde == 1080 // 2 - 1, borde

    # Y al reves, con el estorbo arriba, la ruta baja.
    _, desplazar = comprobar("al reves", 1080, 1700, 300, 60)
    assert desplazar < 0, desplazar

    print("encuadre de la ruta ok")


if __name__ == "__main__":
    main()
