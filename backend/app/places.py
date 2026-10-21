"""El buscador de sitios: Pelias (el geocodificador de HeiGIT, la misma cuenta que las rutas).

Por qué no Nominatim directo desde el móvil, que es lo que había: Nominatim obliga a esperar
1 s entre búsquedas, no ordena por cercanía y hay que recortarle el mundo a mano con un
"viewbox" — y lo que cae justo fuera del recorte desaparece. Pelias ordena por cercanía con
`focus.point`, que es un empujón, no una tapia: lo de al lado sale primero y lo de Kentucky sale
al final, pero sale.

Aquí se le pide perdón a la elegancia y se contesta con la MISMA forma que Nominatim
(display_name, lat/lon en texto, address desglosada). Así el móvil no distingue quién ha
contestado y Nominatim sigue siendo el suplente sin escribir dos veces el mismo código.

Nada de autocompletar: se busca cuando el usuario le da a buscar. Son 3.000 al día y un
autocompletado se come eso en una tarde escribiendo.
"""
import logging
import os

from . import providers

logger = logging.getLogger(__name__)

# La clave es la misma que la de las rutas (una cuenta de HeiGIT, cuotas aparte: 3.000
# búsquedas al día y 100 por minuto). La URL se puede cambiar desde .env sin tocar código: el
# dominio de HeiGIT está recién estrenado y todavía mueven cosas de sitio.
PELIAS_URL = os.getenv("PELIAS_URL", "https://api.heigit.org/pelias/v1")

# Cuántos sitios se piden. El móvil enseña 5; se piden algunos más porque los repetidos se
# juntan aquí.
RESULTS = 10

# El radio del primer intento, en kilómetros: "por aquí". 60 km es una isla entera o una
# provincia, que es hasta donde se va a comprar algo; más que eso ya no es "por aquí" y lo que
# haya ahí sale igual en el segundo intento.
CERCA_KM = 60

# Lo que Pelias llama a cada cosa, traducido a los nombres de Nominatim que ya lee el móvil.
# El orden importa: el primero que exista gana (ver [_partes]).
CALLE = ("street", "name")
BARRIO = ("neighbourhood", "borough")
PUEBLO = ("localadmin", "locality")
CIUDAD = ("locality", "region")


def search(text: str, lat: float | None = None, lng: float | None = None,
           limit: int = RESULTS) -> list[dict] | None:
    """Los sitios que encajan con [text], los de cerca primero. None = Pelias no está
    disponible y le toca al suplente (Nominatim, desde el móvil).

    Dos intentos cuando se sabe dónde está mirando el mapa: primero solo lo que hay a
    [CERCA_KM] a la redonda y, si por ahí no hay nada, el mundo entero ordenado por cercanía.
    Con un solo intento al mundo, buscar "farmacia" desde Las Palmas contestaba un pueblo de
    Italia llamado Farmacia: `focus.point` solo empuja, y la fama de un pueblo empuja más que
    estar al lado. Con los dos, la farmacia de la esquina sale primero y la Torre Eiffel sigue
    saliendo (no hay ninguna aquí al lado, así que se pregunta por el mundo).
    """
    if not text.strip():
        return []
    params = {"api_key": _clave(), "text": text, "size": limit}
    if lat is not None and lng is not None:
        params["focus.point.lat"] = lat
        params["focus.point.lon"] = lng
        cerca = _buscar(dict(params, **{
            "boundary.circle.lat": lat,
            "boundary.circle.lon": lng,
            "boundary.circle.radius": CERCA_KM,
        }))
        # None = no disponible, y entonces el segundo intento tampoco va a contestar.
        if cerca is None or cerca:
            return cerca
    return _buscar(params)


def _buscar(params: dict) -> list[dict] | None:
    payload = _pedir("/search", params)
    if payload is None:
        return None
    vistos = set()
    sitios = []
    for feature in payload.get("features", []):
        sitio = _sitio(feature)
        # Pelias repite un mismo portal cuando lo conoce por dos fuentes (OSM y catastro).
        if sitio is None or sitio["display_name"] in vistos:
            continue
        vistos.add(sitio["display_name"])
        sitios.append(sitio)
    return sitios


def reverse(lat: float, lng: float) -> dict | None:
    """La dirección de un punto del mapa, con la forma del "reverse" de Nominatim. None = no
    disponible; un dict con display_name a None = ha contestado y de ahí no sabe nada (en medio
    del mar), que no es lo mismo."""
    payload = _pedir("/reverse", {
        "api_key": _clave(), "point.lat": lat, "point.lon": lng, "size": 1,
    })
    if payload is None:
        return None
    features = payload.get("features") or []
    if not features:
        return {"display_name": None, "address": None}
    propiedades = features[0].get("properties") or {}
    return {"display_name": propiedades.get("label"), "address": _partes(propiedades)}


def _clave() -> str:
    # Se lee aquí y no al importar para que el módulo de rutas y este compartan la misma clave
    # aunque se cambie en caliente (las pruebas lo hacen).
    from . import routing
    return routing.ORS_API_KEY


def _pedir(camino: str, params: dict) -> dict | None:
    if not _clave():
        logger.info("Búsqueda pedida sin clave de HeiGIT: contesta el suplente")
        return None
    try:
        return providers.pedir("Pelias", PELIAS_URL + camino, params=params)
    except providers.NoDisponible:
        return None
    except providers.SinRespuesta:
        # Pelias no usa esto (cuando no encuentra nada contesta 200 y una lista vacía), pero si
        # algún día lo usa, "no hay nada ahí" no es motivo para despertar al suplente.
        return []


def _sitio(feature: dict) -> dict | None:
    """Un resultado con la forma de Nominatim: lat/lon en TEXTO, como los manda él."""
    propiedades = feature.get("properties") or {}
    coords = (feature.get("geometry") or {}).get("coordinates") or []
    etiqueta = propiedades.get("label") or propiedades.get("name")
    if len(coords) < 2 or not etiqueta:
        return None
    return {"display_name": etiqueta, "lat": str(coords[1]), "lon": str(coords[0])}


def _partes(propiedades: dict) -> dict:
    """El desglose de la dirección con los nombres de Nominatim, que son los que el móvil sabe
    convertir en "Calle Mayor 12" y el resto debajo."""
    return {
        "house_number": propiedades.get("housenumber"),
        "road": _primero(propiedades, CALLE),
        "neighbourhood": _primero(propiedades, BARRIO),
        "village": _primero(propiedades, PUEBLO),
        "city": _primero(propiedades, CIUDAD),
    }


def _primero(propiedades: dict, nombres: tuple) -> str | None:
    for nombre in nombres:
        valor = propiedades.get(nombre)
        if valor:
            return str(valor)
    return None
