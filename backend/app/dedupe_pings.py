"""Escoba de una pasada para los pings duplicados que YA están guardados.

El que entren nuevos lo arregla /location/pings (descarta el punto que ya tiene). Esto es para
los que entraron antes de ese arreglo: eran el 3 % de la tabla, con hasta diez copias del mismo
punto. Cuenta primero y solo borra si se lo pides:

    docker compose exec lokate-api python -m app.dedupe_pings           # solo cuenta
    docker compose exec lokate-api python -m app.dedupe_pings --apply   # borra
    docker compose exec lokate-api python -m app.dedupe_pings --check   # self-check

Se puede volver a pasar sin miedo: la segunda vez no encuentra nada que borrar.

ponytail: script suelto y no endpoint, como app/inspect_spikes.py — esto se pasa una vez, a mano,
con una copia de seguridad delante.
"""

import sqlite3
import sys
import tempfile
from pathlib import Path

from sqlalchemy import text

from .database import SessionLocal

# Duplicado es lo mismo que descarta /location/pings: misma persona, misma hora y mismas
# coordenadas. De cada grupo se queda el rowid más bajo, que es el que llegó primero — y por eso
# el que puede traer wifi_ssid, porque los puntos de la cola viajan sin SSID.
KEEP = "SELECT MIN(rowid) FROM location_pings GROUP BY user_id, timestamp, lat, lng"
COUNT_DUPES = "SELECT COUNT(*) FROM location_pings WHERE rowid NOT IN (%s)" % KEEP
DELETE_DUPES = "DELETE FROM location_pings WHERE rowid NOT IN (%s)" % KEEP


def main(apply: bool) -> None:
    db = SessionLocal()
    try:
        total = db.execute(text("SELECT COUNT(*) FROM location_pings")).scalar()
        sobran = db.execute(text(COUNT_DUPES)).scalar()
        porcentaje = sobran / total * 100 if total else 0.0
        print(f"pings guardados: {total}")
        print(f"copias de sobra: {sobran} ({porcentaje:.1f} %)")
        if not sobran:
            return
        if not apply:
            print("nada borrado. Repite con --apply (y con una copia de seguridad hecha).")
            return
        borrados = db.execute(text(DELETE_DUPES)).rowcount
        db.commit()
        print(f"borrados: {borrados}, quedan {total - borrados}")
    finally:
        db.close()


def check() -> None:
    """La misma SQL contra una base de juguete: tres copias de un punto y un vecino legítimo."""
    ruta = Path(tempfile.mkdtemp()) / "check.db"
    con = sqlite3.connect(ruta)
    con.execute("CREATE TABLE location_pings (id TEXT, user_id TEXT, lat REAL, lng REAL, timestamp TEXT, wifi_ssid TEXT)")
    filas = [
        ("a", "u1", 52.25, -7.12, "2026-10-01 10:00:00", "Casa"),  # la que se queda
        ("b", "u1", 52.25, -7.12, "2026-10-01 10:00:00", None),    # copia de la cola
        ("c", "u1", 52.25, -7.12, "2026-10-01 10:00:00", None),    # y otra
        ("d", "u1", 52.25, -7.12, "2026-10-01 10:05:00", None),    # otra hora: no es copia
        ("e", "u2", 52.25, -7.12, "2026-10-01 10:00:00", None),    # otra persona: tampoco
    ]
    con.executemany("INSERT INTO location_pings VALUES (?, ?, ?, ?, ?, ?)", filas)
    assert con.execute(COUNT_DUPES).fetchone()[0] == 2
    con.execute(DELETE_DUPES)
    quedan = [r[0] for r in con.execute("SELECT id FROM location_pings ORDER BY id")]
    assert quedan == ["a", "d", "e"], quedan
    # Se queda la que trae la wifi, y pasarlo otra vez no borra nada.
    assert con.execute(COUNT_DUPES).fetchone()[0] == 0
    con.close()
    print("OK dedupe_pings")


if __name__ == "__main__":
    if "--check" in sys.argv:
        check()
    else:
        main(apply="--apply" in sys.argv)
