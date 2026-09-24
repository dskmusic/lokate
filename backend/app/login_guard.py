"""Freno de fuerza bruta para /auth/login, que hasta ahora aceptaba intentos sin límite: con
contraseñas de familia (y el APK público apuntando a este servidor) probar sin freno es la vía
de entrada más barata que tiene esta app.

Tras [MAX_FAILS] fallos seguidos en [WINDOW_S], ese usuario queda bloqueado [BLOCK_S]. Un
acierto lo limpia todo, así que quien sabe su contraseña no llega a notarlo nunca.

En memoria del proceso, igual que las marcas de seguimiento en vivo de routers/locations.py:
un solo uvicorn y un reinicio no deja a nadie fuera (peor caso: el atacante recupera sus
intentos, y para eso tiene que tirarle el servidor primero).

ponytail: se cuenta por usuario, NO por IP. Detrás del proxy inverso todas las peticiones
llegan con la misma IP (127.0.0.1) salvo que la cadena de X-Forwarded-For sea de fiar, y
bloquear esa IP sería dejar fuera a la familia entera con cinco intentos. La IP se guarda solo
para enseñarla en el panel. Si algún día hace falta bloquear por IP, primero hay que configurar
ProxyHeadersMiddleware y confiar en el proxy.
"""

from time import time

MAX_FAILS = 5
WINDOW_S = 15 * 60
BLOCK_S = 15 * 60

# Tope de usuarios distintos vigilados a la vez: probar con nombres inventados no puede hacer
# crecer esto sin fin. Al pasarse se tira lo más viejo, que es lo que menos importa.
MAX_TRACKED = 500


class _Entry:
    __slots__ = ("fails", "first_at", "last_at", "blocked_until", "ip")

    def __init__(self, ip: str) -> None:
        self.fails = 0
        self.first_at = time()
        self.last_at = self.first_at
        self.blocked_until = 0.0
        self.ip = ip


_entries: dict[str, _Entry] = {}


def _key(username: str) -> str:
    return (username or "").strip().lower()[:64]


def _prune(now: float) -> None:
    for key, entry in list(_entries.items()):
        if entry.blocked_until <= now and now - entry.last_at > WINDOW_S:
            del _entries[key]
    if len(_entries) > MAX_TRACKED:
        # Se tira primero lo que no está bloqueado: si no, bastaría con probar 500 usuarios
        # inventados para que el bloqueo de uno de verdad se cayera de la lista.
        victims = sorted(_entries.items(), key=lambda kv: (kv[1].blocked_until, kv[1].last_at))
        for key, _ in victims[: len(_entries) - MAX_TRACKED]:
            del _entries[key]


def blocked_seconds(username: str) -> int:
    """Lo que le queda de bloqueo a ese usuario, 0 si puede intentarlo."""
    now = time()
    _prune(now)
    entry = _entries.get(_key(username))
    if entry is None:
        return 0
    return max(0, int(entry.blocked_until - now))


def record_failure(username: str, ip: str = "") -> None:
    now = time()
    entry = _entries.get(_key(username))
    # La ventana se mide desde el primer fallo de la racha: cinco fallos repartidos a lo largo
    # de la tarde no son un ataque, cinco en un cuarto de hora sí.
    if entry is None or now - entry.first_at > WINDOW_S:
        entry = _Entry(ip)
        _entries[_key(username)] = entry
    entry.fails += 1
    entry.last_at = now
    entry.ip = ip or entry.ip
    if entry.fails >= MAX_FAILS:
        entry.blocked_until = now + BLOCK_S
    # Al final y no al principio: asi el tope se cumple tambien contando el que se acaba de
    # apuntar, que es justo el caso de alguien probando nombres inventados en bucle.
    _prune(now)


def record_success(username: str) -> None:
    _entries.pop(_key(username), None)


def entries() -> list[dict]:
    """Lo que ve el panel: quién lleva fallos y a quién le queda bloqueo."""
    now = time()
    _prune(now)
    rows = [
        {
            "username": key,
            "fails": entry.fails,
            "ip": entry.ip,
            "blocked_seconds": max(0, int(entry.blocked_until - now)),
            "last_at": entry.last_at,
        }
        for key, entry in _entries.items()
    ]
    # Primero los bloqueados, y dentro, lo más reciente arriba.
    rows.sort(key=lambda r: (-r["blocked_seconds"], -r["last_at"]))
    return rows


def unblock(username: str) -> None:
    record_success(username)


def unblock_all() -> None:
    _entries.clear()
