"""Archivos subidos por los usuarios que se pueden explorar y borrar desde los dos paneles.

Vive aparte (igual que `backups.py`) porque lo consumen el panel nativo de la app y el panel web,
y el guardia de rutas es justo lo último que conviene tener duplicado en dos sitios: una copia
que se queda atrás es un borrado arbitrario en el servidor.
"""

import mimetypes
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from sqlalchemy.orm import Session

from . import models

# Solo contenido subido por los usuarios: ni la BD, ni el APK, ni la web estática, ni las copias
# de seguridad (esas tienen su propia pantalla, con su propio borrado).
MANAGED_DIRS: dict[str, Path] = {
    "avatars": Path("/data/avatars"),
    "attachments": Path("/data/attachments"),
}


class InvalidName(ValueError):
    """Nombre que no puede venir de un cliente legítimo — un intento de salirse de la carpeta."""


@dataclass
class ManagedFile:
    name: str
    size_bytes: int
    modified: datetime
    url: str
    #: image / video / audio / other — decide si el panel lo previsualiza y con qué
    kind: str
    #: solo los avatares pueden estar referenciados en la BD; los adjuntos viajan en el push y
    #: nadie los apunta, así que ahí siempre es False
    in_use: bool


def file_kind(name: str) -> str:
    mime = mimetypes.guess_type(name)[0] or ""
    for prefix in ("image", "video", "audio"):
        if mime.startswith(prefix):
            return prefix
    return "other"


def _resolve(folder: str, name: str) -> Path:
    """Ruta del archivo dentro de la carpeta gestionada, o InvalidName si el nombre es sospechoso.

    El nombre viene del cliente, así que se prohíben los separadores de ambos sistemas (en Linux
    "\\" es un carácter normal y colarlo aquí daría comportamientos distintos según el host) y
    después se comprueba que la ruta ya resuelta siga colgando de la carpeta.
    """
    base = MANAGED_DIRS[folder]  # KeyError: carpeta fuera de la lista blanca
    if name in ("", ".", "..") or "/" in name or "\\" in name:
        raise InvalidName(name)
    target = (base / name).resolve()
    if base.resolve() not in target.parents:
        raise InvalidName(name)
    return target


def list_files(db: Session, folder: str) -> list[ManagedFile]:
    base = MANAGED_DIRS[folder]
    in_use: set[str] = set()
    if folder == "avatars":
        in_use = {
            (u.avatar_url or "").rsplit("/", 1)[-1]
            for u in db.query(models.User).filter(models.User.avatar_url.isnot(None)).all()
        }

    files = [
        ManagedFile(
            name=entry.name,
            size_bytes=entry.stat().st_size,
            modified=datetime.fromtimestamp(entry.stat().st_mtime, tz=timezone.utc),
            url=f"/{folder}/{entry.name}",
            kind=file_kind(entry.name),
            in_use=entry.name in in_use,
        )
        for entry in base.iterdir()
        if entry.is_file()
    ] if base.is_dir() else []
    return sorted(files, key=lambda f: f.modified, reverse=True)


def _clear_avatar_urls(db: Session, names: list[str]) -> None:
    """Un avatar borrado deja el perfil sin foto, en vez de apuntando a un 404."""
    urls = [f"/avatars/{name}" for name in names]
    stale = db.query(models.User).filter(models.User.avatar_url.in_(urls)).all()
    for user in stale:
        user.avatar_url = None
    if stale:
        db.commit()


def delete_file(db: Session, folder: str, name: str) -> bool:
    """False si no existe. InvalidName si el nombre es sospechoso, KeyError si la carpeta no está."""
    target = _resolve(folder, name)
    if not target.is_file():
        return False
    target.unlink()
    if folder == "avatars":
        _clear_avatar_urls(db, [name])
    return True


def delete_folder(db: Session, folder: str) -> int:
    """Vacía la carpeta entera y devuelve cuántos archivos se han borrado."""
    base = MANAGED_DIRS[folder]
    if not base.is_dir():
        return 0
    deleted = []
    for entry in base.iterdir():
        if entry.is_file():
            entry.unlink()
            deleted.append(entry.name)
    if folder == "avatars" and deleted:
        _clear_avatar_urls(db, deleted)
    return len(deleted)
