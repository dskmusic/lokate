import logging
import os

import firebase_admin
from firebase_admin import credentials, messaging
from sqlalchemy.orm import Session

from . import models

logger = logging.getLogger("lokate.push")

_creds_path = os.getenv("FIREBASE_CREDENTIALS_PATH")
_app = None
if _creds_path and os.path.exists(_creds_path):
    _app = firebase_admin.initialize_app(credentials.Certificate(_creds_path))
else:
    logger.warning("FIREBASE_CREDENTIALS_PATH not set or file missing — push notifications disabled")


def send_to_users(db: Session, user_ids: list[str], title: str, body: str, data: dict):
    if _app is None or not user_ids:
        return
    tokens = [
        u.fcm_token
        for u in db.query(models.User).filter(models.User.id.in_(user_ids)).all()
        if u.fcm_token
    ]
    if not tokens:
        return
    # Solo "data" (sin bloque "notification"): con "notification" presente, Android muestra el
    # aviso por defecto del sistema cuando la app está en segundo plano/cerrada SIN pasar por
    # nuestro código — así "hacer sonar" (y todo lo demás) se comportaba como una notificación
    # normal en vez de forzar sonido/vibración. Con solo "data" siempre pasa por nuestro servicio.
    payload = {"title": title, "body": body, **{k: str(v) for k, v in data.items()}}
    message = messaging.MulticastMessage(
        data=payload,
        android=messaging.AndroidConfig(priority="high"),
        tokens=tokens,
    )
    try:
        messaging.send_each_for_multicast(message, app=_app)
    except Exception:
        logger.exception("Failed to send FCM push")


def send_to_user(db: Session, user_id: str, title: str, body: str, data: dict):
    send_to_users(db, [user_id], title, body, data)
