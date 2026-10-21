"""Rutas de verdad —por calles y aceras— para el "cómo llegar" del mapa.

El móvil no habla con OpenRouteService: habla con nosotros, y nosotros con ellos. Así la clave
no viaja dentro del APK (un APK se abre con un descompresor) y las rutas repetidas no gastan
cuota: la cuenta gratuita son 2.000 al día.

Y hay suplente. ORS estuvo caído entero el 13/10/2026 y con él se quedó sin "cómo llegar" toda
la app, así que cuando no contesta se le pregunta a Stadia Maps. Los dos leen el mismo mapa
(OpenStreetMap) y aquí los dos salen con la misma forma, así que al móvil le da igual quién
conteste.

Aquí viven las tres cosas que se le piden a un mapa de rutas, cada una con su suplente: la ruta
([route]), hasta dónde se llega en X minutos ([isochrone]) y cuánto tarda cada uno en llegar al
mismo sitio ([matrix], que es lo que ordena la lista del "cómo llegar" por minutos de verdad en
vez de por línea recta).

Si no hay ruta, no hay ruta. Aquí nunca se devuelve una línea recta de consuelo: una recta que
cruza un río o una vía de tren no es un camino, y enseñarla como tal es peor que no enseñar
nada.
"""
import logging
import os
from functools import lru_cache

from . import providers

logger = logging.getLogger(__name__)

ORS_API_KEY = os.getenv("ORS_API_KEY", "")
# El servidor nuevo de ORS. El de siempre (api.openrouteservice.org) se apaga del 2 al 6 de
# noviembre de 2026 y desde agosto le estaban recortando la cuota: eso es exactamente lo que se
# vio aqui el 13/10/2026, peticiones autenticadas que se quedaban colgadas. Misma clave, mismo
# formato, solo cambia la direccion.
ORS_BASE = "https://api.heigit.org/openrouteservice/v2"
ORS_URL = ORS_BASE + "/directions/{profile}/geojson"
ORS_ISO_URL = ORS_BASE + "/isochrones/{profile}"
ORS_MATRIX_URL = ORS_BASE + "/matrix/{profile}"

# El suplente: Stadia Maps (que por dentro es Valhalla). Entra solo cuando ORS no contesta, que
# es lo que pasó el 13/10/2026. La clave va en .env, como todas las demás: en el código acabaría
# en GitHub. Plan gratuito: 200.000 al mes, sin uso comercial.
STADIA_API_KEY = os.getenv("STADIA_API_KEY", "")
STADIA_URL = "https://api.stadiamaps.com/route/v1"
STADIA_ISO_URL = "https://api.stadiamaps.com/isochrone/v1"
STADIA_MATRIX_URL = "https://api.stadiamaps.com/matrix/v1"

# Lo que ORS entiende detrás de "a pie" y "en coche". El modo va dentro de la URL, así que se
# traduce por esta tabla y nunca se concatena lo que mande el móvil.
PROFILES = {"foot": "foot-walking", "car": "driving-car"}

# Lo mismo para Valhalla, que a esto lo llama "costing".
COSTINGS = {"foot": "pedestrian", "car": "auto"}

# Valhalla quiere el idioma con región.
STADIA_LANGUAGES = {"es": "es-ES", "en": "en-US"}

# Las maniobras de Valhalla traducidas a los números de ORS, que son los que el móvil ya sabe
# convertir en flechas. Lo que no esté aquí se dibuja como "siga recto" (6), que es la flecha
# que menos miente.
VALHALLA_TYPES = {
    1: 11, 2: 11, 3: 11,            # salida
    4: 10, 5: 10, 6: 10,            # destino
    7: 6, 8: 6, 17: 6, 22: 6, 25: 6,  # sigue recto / se incorpora
    9: 5, 18: 5, 20: 5,             # leve derecha / rampa / salida por la derecha
    10: 1, 11: 3,                   # derecha, cerrada a la derecha
    12: 9, 13: 9,                   # cambio de sentido
    14: 2, 15: 0,                   # cerrada a la izquierda, izquierda
    16: 4, 19: 4, 21: 4,            # leve izquierda / rampa / salida por la izquierda
    23: 13, 24: 12,                 # manténgase a la derecha / izquierda
    26: 7, 27: 8,                   # entra y sale de la rotonda
}

# Tres decimales de más de los que hacen falta: redondear a 5 son ~1 m, y dos peticiones del
# mismo portal comparten respuesta en vez de gastar dos de la cuota.
COORD_DECIMALS = 5

# Idiomas en los que se piden las indicaciones habladas. En blanco y negro a proposito: el
# idioma viaja en el cuerpo de la peticion, y lo que mande el movil no se reenvia tal cual.
LANGUAGES = {"es", "en"}
DEFAULT_LANGUAGE = "es"

# Los anillos que se dibujan: hasta donde se llega en 5, 10 y 15 minutos. Tres y no mas porque
# cada peticion gasta una de las 500 isocronas al dia, y los tres vienen en la MISMA peticion.
ISO_MINUTES = (5, 10, 15)

# ORS escribe asi las calles sin nombre. Al movil se le manda vacio: "continue por -" no se dice.
UNNAMED_ROAD = "-"


# "Entre esos dos puntos no hay camino", o "no hay calle cerca de ahi" (en medio del campo, una
# isla, otro continente). Se separa de "no se ha podido" porque lo que puede hacer el usuario es
# lo contrario: aqui cambiar de destino, y alli esperar. Ver [route] y providers.SinRespuesta.
NoRoute = providers.SinRespuesta


@lru_cache(maxsize=512)
def _ors_route(profile: str, lang: str, from_lat: float, from_lng: float, to_lat: float, to_lng: float) -> dict:
    """La llamada de verdad, con su caché. ponytail: caché en memoria del proceso, se vacía al
    reiniciar el contenedor. Hay un solo worker de uvicorn a propósito (ver main.py), así que no
    hay copias que descoordinar; si algún día hay varios, cada uno tendrá la suya y seguirá
    estando bien, solo se repetirán algunas peticiones."""
    payload = _ors("ORS rutas", ORS_URL.format(profile=profile), {
        "coordinates": [[from_lng, from_lat], [to_lng, to_lat]],
        # Las indicaciones paso a paso, que son lo que lee en voz alta la navegacion del movil.
        # Vienen en la MISMA peticion que la linea: no gastan nada mas de la cuota diaria.
        "instructions": True,
        "language": lang,
    }, sin_camino=404)

    feature = payload["features"][0]
    summary = feature["properties"]["summary"]
    return {
        # Quien ha contestado. El movil lo enseña un momento y ya: con dos proveedores, saber
        # cual ha trazado es la diferencia entre "falla algo" y "el principal esta caido".
        "provider": "OpenRouteService",
        # ORS da [lon, lat] y el móvil dibuja [lat, lng]. Se le da la vuelta aquí, una sola vez
        # y en un sitio: una ruta con la latitud y la longitud cambiadas sale en el mar de Somalia.
        "points": [[c[1], c[0]] for c in feature["geometry"]["coordinates"]],
        # Con los dos puntos en el mismo portal, ORS contesta un resumen vacío. No es un error:
        # es una ruta de cero metros.
        "distance_m": summary.get("distance", 0.0),
        "duration_s": summary.get("duration", 0.0),
        "steps": _steps(feature["properties"].get("segments", [])),
    }


@lru_cache(maxsize=512)
def _stadia_route(costing: str, lang: str, from_lat: float, from_lng: float, to_lat: float, to_lng: float) -> dict:
    """Lo mismo que [_ors_route] pero contra Valhalla, y devolviendo la MISMA forma: el móvil no
    se entera de quién ha contestado."""
    payload = _stadia("Stadia rutas", STADIA_URL, {
        "locations": [{"lat": from_lat, "lon": from_lng}, {"lat": to_lat, "lon": to_lng}],
        "costing": costing,
        "directions_options": {
            "language": STADIA_LANGUAGES.get(lang, STADIA_LANGUAGES[DEFAULT_LANGUAGE]),
            "units": "kilometers",
        },
    }, sin_camino=400)

    leg = payload["trip"]["legs"][0]
    summary = leg.get("summary", {})
    return {
        "provider": "Stadia Maps",
        "points": _decode_polyline(leg["shape"]),
        # Valhalla mide en kilómetros (se lo pedimos así arriba) y el móvil pinta metros.
        "distance_m": float(summary.get("length", 0.0)) * 1000,
        "duration_s": float(summary.get("time", 0.0)),
        "steps": _stadia_steps(leg.get("maneuvers", [])),
    }


def _decode_polyline(encoded: str, factor: float = 1e6) -> list[list[float]]:
    """La línea de Valhalla viene comprimida en texto (polilínea de Google, pero con seis
    decimales en vez de cinco). Se descomprime a [lat, lng], que es como dibuja el móvil.

    Cada número son grupos de 5 bits en caracteres ASCII desplazados 63, el último sin el bit
    0x20; y es un incremento sobre el anterior, en zigzag (el bit bajo es el signo).
    """
    puntos: list[list[float]] = []
    lat = lng = i = 0
    while i < len(encoded):
        for eje in range(2):
            desplazamiento = valor = 0
            while True:
                byte = ord(encoded[i]) - 63
                i += 1
                valor |= (byte & 0x1F) << desplazamiento
                desplazamiento += 5
                if byte < 0x20:
                    break
            delta = ~(valor >> 1) if valor & 1 else valor >> 1
            if eje == 0:
                lat += delta
            else:
                lng += delta
        puntos.append([lat / factor, lng / factor])
    return puntos


def _stadia_steps(maneuvers: list) -> list[dict]:
    """Las maniobras de Valhalla con la forma que ya espera el móvil (ver [_steps]).

    ponytail: se habla el texto escrito ("instruction"), no el hablado
    ("verbal_pre_transition_instruction"), para que sea lo mismo que con ORS: una sola voz y una
    sola lista vengan de donde vengan. Si algún día suena raro leído, ahí está ese campo.
    """
    pasos = []
    for maniobra in maneuvers:
        calles = maniobra.get("street_names") or []
        pasos.append({
            "instruction": maniobra.get("instruction", ""),
            "type": VALHALLA_TYPES.get(int(maniobra.get("type", 0)), 6),
            "name": calles[0] if calles else "",
            "distance_m": float(maniobra.get("length", 0.0)) * 1000,
            "duration_s": float(maniobra.get("time", 0.0)),
            "point_index": int(maniobra.get("begin_shape_index", 0)),
        })
    return pasos


def _steps(segments: list) -> list[dict]:
    """Las maniobras, en una sola lista y ya masticadas para el movil.

    ORS las agrupa por tramos (uno por cada parada intermedia) y aqui no hay paradas, asi que se
    aplanan. De cada paso pasa solo lo que hace falta para hablar y para pintar la lista: el
    texto, el tipo de maniobra (la flecha), la calle, cuanto mide y donde ocurre.

    [point_index] es la clave de toda la navegacion: es el indice dentro de `points`, y con el
    el movil sabe cuantos metros faltan para la maniobra sin volver a preguntar nada.
    """
    pasos = []
    for segment in segments:
        for step in segment.get("steps", []):
            # way_points = [donde empieza el paso, donde acaba]. La maniobra se hace al EMPEZAR
            # el paso ("gire a la derecha", y luego ya vienen sus 300 m de calle).
            way_points = step.get("way_points") or [0]
            name = step.get("name") or ""
            pasos.append({
                "instruction": step.get("instruction", ""),
                "type": int(step.get("type", -1)),
                "name": "" if name == UNNAMED_ROAD else name,
                "distance_m": float(step.get("distance", 0.0)),
                "duration_s": float(step.get("duration", 0.0)),
                "point_index": int(way_points[0]),
            })
    return pasos


def _ors(nombre: str, url: str, body: dict, sin_camino: int) -> dict:
    """Una peticion a ORS/HeiGIT, que quiere la clave en la cabecera. [sin_camino] es el codigo
    con el que ORS dice "ahi no hay nada" (404): sale por [NoRoute], no por averia."""
    return providers.pedir(nombre, url, body=body, headers={"Authorization": ORS_API_KEY},
                           sin_respuesta=(sin_camino,))


def _stadia(nombre: str, url: str, body: dict, sin_camino: int) -> dict:
    """Lo mismo contra Stadia (Valhalla), que quiere la clave en la URL y dice "no hay nada" con
    un 400."""
    return providers.pedir(nombre, url, params={"api_key": STADIA_API_KEY}, body=body,
                           sin_respuesta=(sin_camino,))


def _intentar(nombre: str, fetch, *args) -> dict | None:
    """Lo que conteste ese proveedor, o None si ahora mismo no se puede contar con el — y
    entonces le toca al suplente.

    [providers.SinRespuesta] NO se traga: sube tal cual, porque "ahi no hay nada" no es una
    averia y quien pregunta lo cuenta de otra manera. Lo que si se traga, aparte de la averia,
    es una respuesta que no se entiende: un proveedor que cambia el formato es, para quien
    espera la ruta, un proveedor caido.
    """
    try:
        return fetch(*args)
    except providers.NoDisponible:
        return None
    except (KeyError, IndexError, TypeError, ValueError) as exc:
        logger.warning("%s ha contestado algo que no se entiende: %s", nombre, exc)
        return None


def route(mode: str, from_lat: float, from_lng: float, to_lat: float, to_lng: float,
          lang: str = DEFAULT_LANGUAGE) -> dict | None:
    """La ruta, o None si NINGUN proveedor esta disponible: modo desconocido, servidor sin
    claves, sin internet, los dos caidos o las dos cuotas agotadas. Si lo que pasa es que no hay
    camino entre los dos puntos, levanta [NoRoute] — eso no es una averia y al usuario se le
    dice otra cosa.

    Quien pregunta convierte None en 502 y [NoRoute] en 404.

    Se pregunta a ORS y, si no contesta, a Stadia. "No hay camino" no se reintenta con el
    suplente: los dos leen el mismo mapa (OpenStreetMap), asi que diran lo mismo y solo seria
    esperar el doble para la misma respuesta.

    [lang] es el idioma de las indicaciones habladas. Uno que no este en [LANGUAGES] no es un
    error: se contesta en espanol, que es mejor que no contestar.
    """
    if mode not in PROFILES:
        logger.warning("Ruta pedida sin modo valido: %s", mode)
        return None
    if not ORS_API_KEY and not STADIA_API_KEY:
        logger.warning("Ruta pedida sin ninguna clave de rutas")
        return None
    idioma = lang if lang in LANGUAGES else DEFAULT_LANGUAGE
    puntos = (round(from_lat, COORD_DECIMALS), round(from_lng, COORD_DECIMALS),
              round(to_lat, COORD_DECIMALS), round(to_lng, COORD_DECIMALS))
    if ORS_API_KEY:
        ruta = _intentar("ORS rutas", _ors_route, PROFILES[mode], idioma, *puntos)
        if ruta is not None:
            return ruta
    if STADIA_API_KEY:
        return _intentar("Stadia rutas", _stadia_route, COSTINGS[mode], idioma, *puntos)
    return None


@lru_cache(maxsize=128)
def _ors_isochrone(profile: str, lat: float, lng: float, minutos: tuple) -> dict:
    """Hasta donde se llega desde ese punto, segun ORS. Los tres anillos vienen en UNA peticion."""
    payload = _ors("ORS isocronas", ORS_ISO_URL.format(profile=profile), {
        "locations": [[lng, lat]],
        "range": [m * 60 for m in minutos],
        "range_type": "time",
    }, sin_camino=404)
    return {"provider": "OpenRouteService", "rings": _anillos(payload["features"], "value", 60)}


@lru_cache(maxsize=128)
def _stadia_isochrone(costing: str, lat: float, lng: float, minutos: tuple) -> dict:
    """Lo mismo contra Valhalla, que cuenta los contornos en minutos y no en segundos.

    polygons=True es lo que lo hace dibujable: sin eso contesta lineas sueltas.
    """
    payload = _stadia("Stadia isocronas", STADIA_ISO_URL, {
        "locations": [{"lat": lat, "lon": lng}],
        "costing": costing,
        "contours": [{"time": m} for m in minutos],
        "polygons": True,
    }, sin_camino=400)
    return {"provider": "Stadia Maps", "rings": _anillos(payload["features"], "contour", 1)}


def _anillos(features: list, campo: str, por_minuto: float) -> list[dict]:
    """Los contornos con la forma que pinta el movil: minutos y puntos [lat, lng].

    De mas grande a mas pequeno a proposito: el movil los dibuja en ese orden y asi el de 5
    minutos queda ENCIMA del de 15 y se ve; al contrario, el grande taparia a los otros dos.
    """
    anillos = []
    for feature in features:
        valor = float(feature["properties"].get(campo, 0.0))
        anillos.append({"minutes": round(valor / por_minuto), "points": _contorno(feature["geometry"])})
    return sorted(anillos, key=lambda anillo: -anillo["minutes"])


def _contorno(geometry: dict) -> list[list[float]]:
    """El borde de fuera, en [lat, lng].

    ponytail: solo el borde de fuera. Un poligono de GeoJSON puede traer agujeros (un parque
    cerrado, un poligono industrial sin paso) y aqui se tiran: se dibuja una mancha, no un queso.
    Si algun dia importan, estan en coordinates[1:]. Si contesta varias manchas (MultiPolygon,
    tipico al otro lado de un rio sin puente cerca), se queda la mas grande.
    """
    coords = geometry["coordinates"]
    if geometry.get("type") == "MultiPolygon":
        coords = max(coords, key=lambda poligono: len(poligono[0]))
    return [[c[1], c[0]] for c in coords[0]]


def _ors_matrix(profile: str, origenes: tuple, to_lat: float, to_lng: float) -> dict:
    """Cuanto tarda cada origen en llegar al destino, en una sola peticion.

    Una matriz de N origenes x 1 destino, no N rutas: gasta UNA de la cuota en vez de N, y no
    trae la linea ni las indicaciones, que aqui no se dibujan.
    """
    payload = _ors("ORS matriz", ORS_MATRIX_URL.format(profile=profile), {
        "locations": [[lng, lat] for lat, lng in origenes] + [[to_lng, to_lat]],
        "sources": list(range(len(origenes))),
        "destinations": [len(origenes)],
        "metrics": ["duration"],
    }, sin_camino=404)
    return {
        "provider": "OpenRouteService",
        # Una fila por origen, con un solo valor dentro (el unico destino). null = no llega.
        "durations_s": [(fila or [None])[0] for fila in payload["durations"]],
    }


def _stadia_matrix(costing: str, origenes: tuple, to_lat: float, to_lng: float) -> dict:
    """Lo mismo contra Valhalla, que a los origenes los llama sources y al destino target."""
    payload = _stadia("Stadia matriz", STADIA_MATRIX_URL, {
        "sources": [{"lat": lat, "lon": lng} for lat, lng in origenes],
        "targets": [{"lat": to_lat, "lon": to_lng}],
        "costing": costing,
    }, sin_camino=400)
    return {
        "provider": "Stadia Maps",
        "durations_s": [(fila or [{}])[0].get("time") for fila in payload["sources_to_targets"]],
    }


def isochrone(mode: str, lat: float, lng: float, minutos: tuple = ISO_MINUTES) -> dict | None:
    """Las manchas de "hasta aqui se llega en 5, 10 y 15 minutos" desde un punto, o None si no
    se puede contar con ningun proveedor. Levanta [NoRoute] si ese punto no tiene calle cerca
    (en medio del mar no hay isocrona que dibujar).

    Solo se pide cuando alguien le da al boton: son 500 al dia entre toda la familia y no hay
    nada que se dibuje solo al abrir una pantalla.
    """
    if mode not in PROFILES:
        logger.warning("Isocrona pedida sin modo valido: %s", mode)
        return None
    punto = (round(lat, COORD_DECIMALS), round(lng, COORD_DECIMALS))
    if ORS_API_KEY:
        anillos = _intentar("ORS isocronas", _ors_isochrone, PROFILES[mode], *punto, minutos)
        if anillos is not None:
            return anillos
    if STADIA_API_KEY:
        return _intentar("Stadia isocronas", _stadia_isochrone, COSTINGS[mode], *punto, minutos)
    return None


def matrix(mode: str, origenes: list, to_lat: float, to_lng: float) -> dict | None:
    """Los segundos que tarda cada uno de [origenes] en llegar al destino, en el mismo orden que
    llegaron, con None en los que no llegan. None entero = ningun proveedor disponible.

    Quien pregunta no tiene que hacer nada si esto falla: la lista se queda ordenada por linea
    recta, que es como estaba antes de esto. Un orden aproximado es mejor que una espera.

    ponytail: sin cache. La familia se mueve, asi que la misma matriz no se repite casi nunca y
    guardarla solo serviria para contestar con posiciones viejas.
    """
    if mode not in PROFILES or not origenes:
        logger.warning("Matriz pedida sin modo valido o sin origenes: %s", mode)
        return None
    puntos = tuple((round(lat, COORD_DECIMALS), round(lng, COORD_DECIMALS)) for lat, lng in origenes)
    destino = (round(to_lat, COORD_DECIMALS), round(to_lng, COORD_DECIMALS))
    if ORS_API_KEY:
        tiempos = _intentar("ORS matriz", _ors_matrix, PROFILES[mode], puntos, *destino)
        if tiempos is not None:
            return tiempos
    if STADIA_API_KEY:
        return _intentar("Stadia matriz", _stadia_matrix, COSTINGS[mode], puntos, *destino)
    return None
