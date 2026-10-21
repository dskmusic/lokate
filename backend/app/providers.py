"""La puerta de salida a los servicios de fuera (ORS/HeiGIT, Stadia, Pelias): una sola llamada,
una espera corta y un cortacircuitos.

El cortacircuitos existe por lo del 13/10/2026. Ese dia ORS dejo de contestar y CADA "como
llegar" se pasaba 12 s esperandole antes de caerse al suplente, uno detras de otro, toda la
tarde. Con esto, el primero que falla paga la espera y los demas no: durante [AVERIA_S] a ese
proveedor no se le vuelve a preguntar, se va derecho al suplente y el aviso de "no disponible"
sale al momento. Vuelve solo, sin reiniciar nada.
"""
import json
import logging
import time
import urllib.error
import urllib.parse
import urllib.request

logger = logging.getLogger(__name__)

TIMEOUT_S = 12
# Lo que se da por caido a un proveedor que acaba de fallar. Cinco minutos: bastante para no
# pagar la espera en cada intento, poco para que una caida corta no deje la app a medio gas.
AVERIA_S = 300

# nombre del proveedor -> cuando fallo (reloj monotono).
# ponytail: un dict en memoria del proceso, como el modo prueba de geofence.py. Hay un solo
# worker de uvicorn (ver main.py); si algun dia hay varios, cada uno aprendera la averia por su
# cuenta y seguira funcionando igual, solo pagara la espera una vez cada uno.
_averias: dict[str, float] = {}


class NoDisponible(Exception):
    """Con ese proveedor no se puede contar ahora mismo: sin internet, no contesta, cuota
    agotada, o ha contestado algo que no se entiende. Lo que toca es probar el suplente y, si no
    hay, decirselo al usuario."""


class SinRespuesta(Exception):
    """El proveedor dice que ahi no hay nada: ni camino entre esos dos puntos, ni calle cerca de
    esa coordenada. No es una averia — no hay nada que reintentar ni que esperar —, asi que no
    cuenta para el cortacircuitos y al usuario se le dice otra cosa."""


def averiado(nombre: str) -> bool:
    """Si a ese proveedor le toca estar en el banquillo."""
    caida = _averias.get(nombre)
    return caida is not None and time.monotonic() - caida < AVERIA_S


def olvidar_averias() -> None:
    """Todos los proveedores como recien levantados. Para las pruebas."""
    _averias.clear()


def pedir(nombre: str, url: str, *, params: dict | None = None, body: dict | None = None,
          headers: dict | None = None, sin_respuesta: tuple[int, ...] = ()) -> dict:
    """Lo que contesta el proveedor, ya leido como JSON.

    Levanta [NoDisponible] si no se puede contar con el (y lo apunta en el cortacircuitos) o
    [SinRespuesta] si contesta con uno de los codigos de [sin_respuesta] — que es como cada uno
    dice "ahi no hay nada": ORS manda 404 y Valhalla 400.

    Con [body] la peticion es POST; sin el, GET. [params] se escapa aqui: nada de lo que mande
    el movil se pega a mano a la URL.
    """
    if averiado(nombre):
        logger.info("%s saltado: ha fallado hace menos de %s s", nombre, AVERIA_S)
        raise NoDisponible(f"{nombre} en el banquillo")
    if params:
        url = f"{url}?{urllib.parse.urlencode(params)}"
    datos = json.dumps(body).encode() if body is not None else None
    cabeceras = {"Content-Type": "application/json"} if datos else {}
    cabeceras.update(headers or {})
    try:
        peticion = urllib.request.Request(url, data=datos, headers=cabeceras)
        with urllib.request.urlopen(peticion, timeout=TIMEOUT_S) as respuesta:
            payload = json.load(respuesta)
    except urllib.error.HTTPError as exc:
        # Va primero porque HTTPError es un URLError, y ese es un OSError.
        if exc.code in sin_respuesta:
            logger.info("%s dice que ahi no hay nada", nombre)
            raise SinRespuesta(nombre) from exc
        raise _apuntar(nombre, exc) from exc
    except (urllib.error.URLError, OSError, ValueError) as exc:
        # Corte de red, el servidor que no contesta, respuesta que no es JSON.
        raise _apuntar(nombre, exc) from exc
    # Ha contestado: si estaba en el banquillo, fuera.
    _averias.pop(nombre, None)
    return payload


def _apuntar(nombre: str, exc: Exception) -> NoDisponible:
    _averias[nombre] = time.monotonic()
    logger.warning("%s no disponible: %s", nombre, exc)
    return NoDisponible(str(exc))
