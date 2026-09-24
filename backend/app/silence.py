"""Aviso de "sin señal": el servidor es el único que puede darse cuenta de que un móvil del
grupo lleva demasiado tiempo callado. El propio móvil no puede avisar de que está apagado, sin
batería, sin cobertura o con la app matada por el fabricante, y los demás solo ven una hora que
deja de avanzar sin que nada se lo diga.
"""

import asyncio
import logging
from datetime import datetime, timezone

from sqlalchemy import func

from . import models, push
from .database import SessionLocal

logger = logging.getLogger(__name__)

# Cada cuánto se pasa revista. No hace falta más fino: el umbral más corto es de 45 minutos.
CHECK_EVERY_S = 10 * 60

# Cuántos intervalos seguidos hay que fallar para dar la voz de alarma. Con 3, un ping perdido
# suelto (que pasa constantemente) no avisa a nadie.
SILENCE_FACTOR = 3

# Suelo absoluto del umbral, pase lo que pase. Un móvil quieto o en el wifi de casa manda como
# mucho cada 15 minutos POR DISEÑO (ver LocationForegroundService.IDLE_INTERVAL_MS): avisar
# antes de 45 minutos sería llamar avería a lo que es ahorro de batería.
MIN_SILENCE_S = 45 * 60

# Un episodio de silencio avisa una vez; si sigue callado, se repite pasado esto.
REPEAT_AFTER_S = 12 * 3600

# Segundos entre pings de cada ritmo del móvil (espejo de LocationFrequency en la app). Los que
# no están aquí —ON_DEMAND, DISABLED, o un valor de una versión futura— no mandan nada por
# iniciativa propia: callarse no es avería.
FREQUENCY_SECONDS = {
    "REAL_TIME": 3,
    "EVERY_30_SEC": 30,
    "EVERY_1_MIN": 60,
    "BALANCED": 120,
    "EVERY_5_MIN": 300,
    "BATTERY_SAVER": 600,
}

# ponytail: dict en memoria como el resto de relojes del servidor (_live_until, _last_purge) —
# si el contenedor reinicia, lo peor que pasa es un aviso repetido.
_alerted: dict[str, datetime] = {}


def silence_limit(frequency: str | None) -> int | None:
    """Segundos que puede llevar callado ese móvil antes de que sea para preocuparse. None = ese
    ritmo no manda nada por su cuenta, así que no hay nada que vigilar."""
    interval = FREQUENCY_SECONDS.get((frequency or "").strip().upper())
    if interval is None:
        return None
    return max(interval * SILENCE_FACTOR, MIN_SILENCE_S)


def episode_action(silent_for: float, limit: int, alerted_at, now) -> str | None:
    """Qué toca hacer con esta persona en esta pasada: avisar, retirar el aviso, o nada.

    Un episodio de silencio avisa UNA vez y, si sigue callado, se repite cada REPEAT_AFTER_S.
    "recovered" solo sale si antes se avisó: de quien nunca se calló no hay nada que retirar.
    """
    if silent_for < limit:
        return "recovered" if alerted_at is not None else None
    if alerted_at is not None and (now - alerted_at).total_seconds() < REPEAT_AFTER_S:
        return None
    return "alert"


def humanize(seconds: float) -> str:
    """"45 minutos", "3 horas", "2 días" — el aviso lo lee una persona, no un log."""
    minutes = int(seconds // 60)
    if minutes < 60:
        return f"{minutes} minutos"
    hours = minutes // 60
    if hours < 48:
        return "1 hora" if hours == 1 else f"{hours} horas"
    return f"{hours // 24} días"


def check_silent_members() -> None:
    """Una pasada: mira la hora del último ping de cada usuario con grupo y avisa a los suyos si
    lleva callado más de lo que le toca. Bloqueante (SQLAlchemy síncrono, como todo el backend):
    se llama desde un hilo, ver [watch_loop]."""
    db = SessionLocal()
    try:
        now = datetime.now(timezone.utc)
        users = db.query(models.User).filter(models.User.group_id.isnot(None)).all()
        for user in users:
            limit = silence_limit(user.location_frequency)
            if limit is None:
                continue
            last = (
                db.query(func.max(models.LocationPing.timestamp))
                .filter(models.LocationPing.user_id == user.id)
                .scalar()
            )
            if last is None:
                # Nunca ha mandado nada: no ha perdido una señal que no llegó a tener (recién
                # invitado, permisos sin dar). Eso ya se ve en su ficha y no es una novedad.
                continue
            if last.tzinfo is None:
                last = last.replace(tzinfo=timezone.utc)
            silent_for = (now - last).total_seconds()
            action = episode_action(silent_for, limit, _alerted.get(user.id), now)
            if action == "recovered":
                # Ha vuelto: se cierra el episodio (el próximo silencio sí volverá a avisar) y se
                # retira de los móviles del grupo el aviso que ya no cuenta nada.
                _alerted.pop(user.id, None)
                _notify_recovered(db, user)
            elif action == "alert":
                _alerted[user.id] = now
                _notify_group(db, user, silent_for)
    finally:
        db.close()


def _group_recipients(db, user: models.User) -> list[str]:
    members = (
        db.query(models.User)
        .filter(models.User.group_id == user.group_id, models.User.id != user.id)
        .all()
    )
    # Quien se esconde en ese grupo no existe para los demás, tampoco para esto.
    return [m.id for m in members if not user.hidden_from(m)]


def _notify_recovered(db, user: models.User) -> None:
    """Retira el aviso de "sin señal" de los móviles del grupo en cuanto esa persona vuelve.

    El caso que esto arregla: alguien que apaga los datos por la noche. El aviso salta a las
    tantas, y por la mañana, cuando vuelve a haber internet, lo único que queda es una
    notificación vieja que ya no es verdad. Se manda a TODO el grupo sin mirar quién tenía el
    aviso encendido: al que no lo tuviera, la app no le encuentra nada que borrar.
    """
    recipients = _group_recipients(db, user)
    if not recipients:
        return
    # Sin texto a propósito: la app no pinta nada con esto, solo borra (ver
    # LokateFirebaseMessagingService, "member_silent_over").
    push.send_to_users(db, recipients, "Lokate", "", {"type": "member_silent_over", "user_id": user.id})


def _notify_group(db, user: models.User, silent_for: float) -> None:
    recipients = _group_recipients(db, user)
    if not recipients:
        return
    # Se nombra la causa probable en el propio aviso: quien lo recibe casi siempre puede
    # descartarla de un vistazo ("si está en casa, es el móvil apagado") y así el aviso sirve
    # para algo en vez de solo preocupar.
    body = (
        f"{user.display_name} lleva {humanize(silent_for)} sin dar señal: "
        "puede que se haya quedado sin internet o sin batería"
    )
    logger.info(
        "Sin señal: %s (%s) lleva %ds sin pings, se avisa a %d",
        user.display_name, user.id, int(silent_for), len(recipients),
    )
    # Solo "data" (sin system_notification): lo pinta la app en su canal de avisos del sistema y
    # respeta el ajuste de quien lo recibe. No corre prisa — quien lleva 45 minutos callado
    # seguirá callado cinco minutos más.
    push.send_to_users(
        db,
        recipients,
        "Lokate",
        body,
        {"type": "member_silent", "user_id": user.id},
    )


async def watch_loop() -> None:
    """Bucle de fondo del propio proceso de la API.

    ponytail: sin cron ni celery — es una consulta cada diez minutos sobre un índice que ya
    existe. Si algún día esto corre con varios workers de uvicorn, cada uno hará su pasada y
    (por el dict en memoria) el tope sería un aviso duplicado por worker; ahí sí tocaría sacarlo
    a un proceso aparte.
    """
    while True:
        await asyncio.sleep(CHECK_EVERY_S)
        try:
            await asyncio.to_thread(check_silent_members)
        except Exception:
            logger.exception("Fallo al revisar quién lleva sin dar señal")


if __name__ == "__main__":
    # Comprobación mínima de lo único que aquí tiene lógica: el umbral y el texto.
    assert silence_limit("ON_DEMAND") is None
    assert silence_limit(None) is None
    # Con los ritmos de hoy el suelo manda siempre (el más lento, 600 s x 3, son 30 min < 45):
    # el factor está ahí para el día que se añada un ritmo más lento que 15 min.
    assert silence_limit("REAL_TIME") == MIN_SILENCE_S
    assert silence_limit("battery_saver") == MIN_SILENCE_S
    assert max(30 * 60 * SILENCE_FACTOR, MIN_SILENCE_S) == 90 * 60
    assert humanize(45 * 60) == "45 minutos"
    assert humanize(3600) == "1 hora"
    assert humanize(5 * 3600) == "5 horas"
    assert humanize(3 * 24 * 3600) == "3 días"

    # El episodio: avisa una vez, calla mientras siga callado, y al volver manda retirar el aviso.
    from datetime import timedelta
    now = datetime.now(timezone.utc)
    assert episode_action(10 * 60, MIN_SILENCE_S, None, now) is None
    assert episode_action(60 * 60, MIN_SILENCE_S, None, now) == "alert"
    assert episode_action(60 * 60, MIN_SILENCE_S, now, now) is None
    assert episode_action(
        60 * 60, MIN_SILENCE_S, now - timedelta(seconds=REPEAT_AFTER_S + 1), now
    ) == "alert"
    # La noche sin datos de la hija: se avisó de madrugada y por la mañana vuelve.
    assert episode_action(60, MIN_SILENCE_S, now - timedelta(hours=8), now) == "recovered"
    # Y de quien nunca se calló no hay nada que retirar (esto no manda pushes a diario).
    assert episode_action(60, MIN_SILENCE_S, None, now) is None
    print("ok")
