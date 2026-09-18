"""Tamaño en disco de los datos de la app (BD, avatares, adjuntos, APK, web estática, copias de
seguridad) — usado tanto por el dashboard web (admin.py) como por el panel nativo de la app
(routers/admin_api.py), para no calcularlo dos veces."""

from pathlib import Path

from . import backups


def _file_size(path: Path) -> int:
    return path.stat().st_size if path.is_file() else 0


def _dir_size(path: Path) -> int:
    if not path.is_dir():
        return 0
    return sum(f.stat().st_size for f in path.rglob("*") if f.is_file())


def compute_disk_usage() -> dict:
    database = _file_size(Path("/data/lokate.db"))
    avatars = _dir_size(Path("/data/avatars"))
    attachments = _dir_size(Path("/data/attachments"))
    apk = _file_size(Path("/app/apk/lokate.apk"))
    web_static = _dir_size(Path("/app/web_inicial"))
    backups_bytes = backups.backups_size_bytes()
    return {
        "database_bytes": database,
        "avatars_bytes": avatars,
        "attachments_bytes": attachments,
        "apk_bytes": apk,
        "web_static_bytes": web_static,
        "backups_bytes": backups_bytes,
        "total_bytes": database + avatars + attachments + apk + web_static + backups_bytes,
    }
