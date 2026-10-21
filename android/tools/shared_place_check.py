# -*- coding: utf-8 -*-
"""Lo que llega al compartir una direccion desde otra app, contra los patrones de verdad.

Las expresiones no se copian aqui: se leen de PlaceQuery.kt, asi que si alguien las toca y
rompe el "compartir" de Maps, esto falla. Uso: python tools/shared_place_check.py
"""
import io
import os
import re

KT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                  "..", "app", "src", "main", "java", "com", "dskmusic", "lokate",
                  "util", "PlaceQuery.kt")


def patrones():
    """Saca los Regex de PlaceQuery.kt (los de triple comilla) por su nombre."""
    fuente = io.open(KT, encoding="utf-8").read()
    crudos = dict(re.findall(r'private val (\w+) = Regex\(\s*"{3}(.+?)"{3}', fuente, re.S))
    assert len(crudos) >= 8, crudos.keys()
    return dict((k, re.compile(v, re.I)) for k, v in crudos.items())


def limpiar(texto):
    """El mismo apaño que hace MainActivity: Maps comparte tres líneas, el buscador es una."""
    return re.sub(r"\s+", " ", texto).strip()


def main():
    p = patrones()

    # 1. El "Compartir > Copiar" de Google Maps: nombre, direccion y enlace corto.
    compartido = limpiar(u"Plaza Mayor\nPlaza Mayor, 28012 Madrid\nhttps://maps.app.goo.gl/YNgFGBGz2TdMtFfz6")
    assert "\n" not in compartido, compartido
    url = p["URL"].search(compartido)
    assert url and url.group(0) == "https://maps.app.goo.gl/YNgFGBGz2TdMtFfz6", compartido
    # Un enlace corto no lleva el punto dentro: hace falta seguir la redireccion (expand()).
    assert not p["GOOGLE_PLACE_POINT"].search(compartido)
    assert not p["AT_POINT"].search(compartido)

    # 2. A donde redirige ese enlace: ahi ya esta el punto exacto del sitio.
    largo = ("https://www.google.com/maps/place/Plaza+Mayor/@40.4155,-3.7074,17z/"
             "data=!3m1!4b1!4m6!3m5!1s0xd42287b!8m2!3d40.4154776!4d-3.7074123")
    punto = p["GOOGLE_PLACE_POINT"].search(largo)
    assert punto and (float(punto.group(1)), float(punto.group(2))) == (40.4154776, -3.7074123), largo

    # 3. Enlace geo: (el otro filtro del manifest), que llega como intent.dataString.
    geo = p["GEO_POINT"].search(limpiar(u"geo:40.4168,-3.7038?z=17"))
    assert geo and (float(geo.group(1)), float(geo.group(2))) == (40.4168, -3.7038)

    # 4. Y lo que llega sin enlace ninguno: texto para Nominatim, sin coordenadas que sacar.
    direccion = limpiar(u"Calle de Alcalá 1,\n Madrid")
    assert not p["URL"].search(direccion)
    assert direccion == u"Calle de Alcalá 1, Madrid", direccion

    print("compartir direcciones ok")


if __name__ == "__main__":
    main()
