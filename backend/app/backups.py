"""Copias de seguridad de los datos de la app (BD + avatares + adjuntos) — un .tar.gz de /data
por copia, más un .json al lado con su descripción y fecha. Usado tanto por el panel nativo
(routers/admin_api.py) como por el dashboard web (admin.py)."""

import json
import tarfile
import uuid
from datetime import datetime, timezone
from pathlib import Path

BACKUPS_DIR = Path("/app/backups")
DATA_DIR = Path("/data")


def _paths(backup_id: str) -> tuple[Path, Path]:
    return BACKUPS_DIR / f"{backup_id}.tar.gz", BACKUPS_DIR / f"{backup_id}.json"


def _info(backup_id: str) -> dict:
    tar_path, meta_path = _paths(backup_id)
    meta = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.exists() else {}
    created_at = (
        datetime.fromisoformat(meta["created_at"]) if "created_at" in meta
        # Copias de antes de que se guardara created_at en el metadata (si las hubiera):
        # la fecha de modificación del propio archivo es la mejor aproximación disponible.
        else datetime.fromtimestamp(tar_path.stat().st_mtime, tz=timezone.utc)
    )
    return {
        "id": backup_id,
        "description": meta.get("description", ""),
        "created_at": created_at,
        "size_bytes": tar_path.stat().st_size if tar_path.exists() else 0,
    }


def list_backups() -> list[dict]:
    if not BACKUPS_DIR.is_dir():
        return []
    ids = [p.name[: -len(".tar.gz")] for p in BACKUPS_DIR.glob("*.tar.gz")]
    return sorted((_info(i) for i in ids), key=lambda b: b["created_at"], reverse=True)


def create_backup(description: str) -> dict:
    BACKUPS_DIR.mkdir(parents=True, exist_ok=True)
    backup_id = uuid.uuid4().hex
    tar_path, meta_path = _paths(backup_id)
    with tarfile.open(tar_path, "w:gz") as tar:
        tar.add(DATA_DIR, arcname=".")
    meta_path.write_text(
        json.dumps({"description": description, "created_at": datetime.now(timezone.utc).isoformat()}),
        encoding="utf-8",
    )
    return _info(backup_id)


def get_tar_path(backup_id: str) -> Path | None:
    tar_path, _ = _paths(backup_id)
    return tar_path if tar_path.exists() else None


def delete_backup(backup_id: str) -> bool:
    tar_path, meta_path = _paths(backup_id)
    if not tar_path.exists():
        return False
    tar_path.unlink()
    meta_path.unlink(missing_ok=True)
    return True


def restore_backup(backup_id: str) -> bool:
    tar_path, _ = _paths(backup_id)
    if not tar_path.exists():
        return False
    with tarfile.open(tar_path, "r:gz") as tar:
        try:
            tar.extractall(DATA_DIR, filter="data")
        except TypeError:
            # Python < 3.12 (y versiones de parche anteriores a la que añadió `filter`) — la
            # imagen Docker real usa python:3.12-slim, esto es solo red de seguridad.
            tar.extractall(DATA_DIR)
    return True


def backups_size_bytes() -> int:
    if not BACKUPS_DIR.is_dir():
        return 0
    return sum(f.stat().st_size for f in BACKUPS_DIR.rglob("*") if f.is_file())
