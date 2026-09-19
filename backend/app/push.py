import logging
import os

import firebase_admin
from firebase_admin import credentials, exceptions as fb_exceptions, messaging
from sqlalchemy.orm import Session

from . import models

logger = logging.getLogger("lokate.push")

_creds_path = os.getenv("FIREBASE_CREDENTIALS_PATH")
_app = None
if _creds_path and os.path.exists(_creds_path):
    _app = firebase_admin.initialize_app(credentials.Certificate(_creds_path))
else:
    logger.warning("FIREBASE_CREDENTIALS_PATH not set or file missing — push notifications disabled")


def is_dead_token_error(exc: BaseException | None) -> bool:
    """True si el fallo significa "este token ya no sirve NUNCA más" (app desinstalada, datos
    borrados, móvil restaurado) — distinto de un fallo temporal de red o de cuota, donde el
    token sigue siendo bueno y hay que conservarlo."""
    return isinstance(exc, (messaging.UnregisteredError, messaging.SenderIdMismatchError, fb_exceptions.InvalidArgumentError))


def _build_message(user: models.User, token: str, title: str, body: str, payload: dict, system_notification: bool):
    """Con bloque "notification" el aviso lo pinta el SISTEMA del móvil, sin arrancar la app —
    la única forma de que llegue al instante a un proceso muerto (un push "solo data" se queda
    en la cola de FCM hasta que el móvil despierta). Requiere saber a qué canal va, y el canal
    lo registra cada móvil en /auth/device: sin canal registrado se manda "solo data" como
    siempre. No duplica el aviso: con la app en primer plano solo corre onMessageReceived y el
    sistema no pinta nada, y con la app fuera es justo al revés."""
    channel = (user.zone_channel_id or "").strip() if system_notification else ""
    if not channel:
        return messaging.Message(token=token, data=payload, android=messaging.AndroidConfig(priority="high"))
    return messaging.Message(
        token=token,
        data=payload,
        notification=messaging.Notification(title=title, body=body),
        android=messaging.AndroidConfig(
            priority="high",
            # El icono y el canal de reserva (si este id ya no existiera en ese móvil) salen
            # del AndroidManifest: default_notification_icon y default_notification_channel_id.
            notification=messaging.AndroidNotification(channel_id=channel),
        ),
    )


def send_to_users(db: Session, user_ids: list[str], title: str, body: str, data: dict, system_notification: bool = False):
    # Sin credenciales de Firebase no se envía NADA a nadie, y antes se salía de aquí en
    # silencio: el único rastro era un warning al arrancar el contenedor, fácil de no ver nunca.
    if _app is None:
        logger.error("Push '%s' NO enviado: Firebase no está configurado (FIREBASE_CREDENTIALS_PATH)", title)
        return
    if not user_ids:
        logger.warning("Push '%s' NO enviado: la lista de destinatarios llegó vacía", title)
        return
    recipients = db.query(models.User).filter(models.User.id.in_(user_ids)).all()

    # Sin token FCM no se le puede enviar NADA: antes desaparecían de la lista en silencio y no
    # había forma de saber a quién no le estaba llegando. Pasa si nunca ha abierto la app desde
    # que se instaló esta versión, si cerró sesión, o si su token resultó caducado antes.
    no_token = [u.display_name for u in recipients if not u.fcm_token]
    if no_token:
        logger.warning("Push '%s': SIN token FCM (no reciben nada): %s", title, ", ".join(no_token))

    # Se conserva el usuario junto al token para poder limpiar el token muerto de SU fila:
    # send_each_for_multicast devuelve las respuestas en el mismo orden que los tokens.
    targets = [(u, u.fcm_token) for u in recipients if u.fcm_token]
    if not targets:
        logger.warning("Push '%s' descartado: ninguno de los %d destinatarios tiene token FCM", title, len(user_ids))
        return
    # Solo "data" (sin bloque "notification"): con "notification" presente, Android muestra el
    # aviso por defecto del sistema cuando la app está en segundo plano/cerrada SIN pasar por
    # nuestro código — así "hacer sonar" (y todo lo demás) se comportaba como una notificación
    # normal en vez de forzar sonido/vibración. Con solo "data" siempre pasa por nuestro servicio.
    payload = {"title": title, "body": body, **{k: str(v) for k, v in data.items()}}
    # Un mensaje por destinatario en vez de un multicast: el canal de notificación es de CADA
    # móvil. send_each devuelve las respuestas en el mismo orden, igual que el multicast.
    messages = [_build_message(user, token, title, body, payload, system_notification) for user, token in targets]
    try:
        batch = messaging.send_each(messages, app=_app)
    except Exception:
        logger.exception("Failed to send FCM push")
        return

    # Antes la respuesta se ignoraba por completo: un token caducado fallaba en silencio para
    # siempre y no había forma de saber por qué un usuario "no recibe notificaciones".
    dead = 0
    for (user, _token), response in zip(targets, batch.responses):
        if response.success:
            continue
        if is_dead_token_error(response.exception):
            user.fcm_token = None
            dead += 1
            logger.warning("Token FCM caducado de %s (%s): se borra", user.display_name, user.id)
        else:
            logger.error("Fallo al enviar push a %s (%s): %s", user.display_name, user.id, response.exception)
    if dead:
        db.commit()
    logger.info(
        "Push '%s': %d/%d entregados a FCM (%d destinatarios pedidos)",
        title, batch.success_count, len(targets), len(user_ids),
    )


def send_to_user(db: Session, user_id: str, title: str, body: str, data: dict):
    send_to_users(db, [user_id], title, body, data)
