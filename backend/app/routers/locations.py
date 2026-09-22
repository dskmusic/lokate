import os
from datetime import datetime, timedelta, timezone
from time import monotonic

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session

from .. import geofence, models, push, schemas, smoothing
from ..auth import get_current_user_with_group
from ..database import get_db

router = APIRouter(prefix="/location", tags=["location"])

RETENTION_DAYS = int(os.getenv("LOCATION_RETENTION_DAYS", "30"))
# Cada cuánto se barren los pings caducados. Antes se barría en CADA ping: un DELETE por ping
# y por usuario para tirar, casi siempre, cero filas.
PURGE_EVERY = timedelta(hours=6)
_last_purge = datetime.min.replace(tzinfo=timezone.utc)


def _purge_old_pings(db: Session) -> None:
    """Tira los pings más viejos que la retención, de todos los usuarios de una vez. No hace
    falta que sea puntual: sobra con pasar la escoba unas cuantas veces al día.
    ponytail: reloj en memoria del proceso, sin cron ni scheduler — si el contenedor reinicia,
    la siguiente escoba pasa antes de tiempo y no pasa nada. Confirma el ping que la llame."""
    global _last_purge
    now = datetime.now(timezone.utc)
    if now - _last_purge < PURGE_EVERY:
        return
    _last_purge = now
    db.query(models.LocationPing).filter(
        models.LocationPing.timestamp < now - timedelta(days=RETENTION_DAYS)
    ).delete()


# Seguimiento en vivo: mientras alguien tiene a un miembro "seguido" en su mapa, ese movil se
# pone en tiempo real. La marca vive en memoria del proceso y caduca sola — si el que sigue
# cierra la app, deja de renovarla y el seguido vuelve a su modo sin que nadie se lo diga.
# ponytail: dict en memoria como el modo prueba de geofence.py, no merece una columna en la BD.
LIVE_TTL_S = 180
# Tope duro de una sesion de seguimiento: aunque el que sigue se deje la pantalla abierta toda
# la noche renovando, el seguido vuelve a su ritmo al llegar aqui. Para seguir mas rato hay que
# volver a pulsar "seguir", que es justo la confirmacion que queremos.
LIVE_MAX_S = 30 * 60
# Si el seguido lleva mas de esto sin mandar un ping, la siguiente renovacion vuelve a mandarle
# el push: un push perdido dejaba el tiempo real sin arrancar hasta su siguiente ping normal,
# que con el movil en reposo puede tardar 15 minutos.
LIVE_REPUSH_SILENT_S = 30
# Cuanto puede llevar callado el seguido antes de que dejemos de decirle a quien sigue que esto
# esta "en vivo". En vivo se pinga cada 3 s, asi que este margen son seis pings perdidos.
LIVE_CONFIRM_SILENT_S = 20
_live_until: dict[str, float] = {}
_live_started: dict[str, float] = {}
_last_ping: dict[str, float] = {}


def _live_seconds(user_id: str) -> int:
    """Segundos que le quedan de seguimiento en vivo, 0 si no esta siendo seguido."""
    until = _live_until.get(user_id)
    if until is None:
        return 0
    remaining = until - monotonic()
    if remaining <= 0:
        _live_stop(user_id)
        return 0
    return int(remaining)


def _live_confirmed_seconds(user_id: str) -> int:
    """Lo mismo, pero visto por QUIEN SIGUE: solo cuenta cuando el otro movil esta respondiendo
    de verdad. Mientras la orden no haya prendido alli (push perdido, servicio denegado por el
    sistema) esto sigue en 0, y asi el boton de seguir no promete algo que no esta pasando."""
    seconds = _live_seconds(user_id)
    if seconds and monotonic() - _last_ping.get(user_id, 0.0) > LIVE_CONFIRM_SILENT_S:
        return 0
    return seconds


def _live_stop(user_id: str) -> None:
    _live_until.pop(user_id, None)
    _live_started.pop(user_id, None)


def _group_member(db: Session, user: models.User, user_id: str) -> models.User | None:
    """El miembro del grupo de quien pregunta, o None. Quien se esconde en ese grupo no existe
    para los demás: ni ubicación, ni historial, ni hacer sonar su móvil."""
    target = db.query(models.User).filter(
        models.User.id == user_id, models.User.group_id == user.group_id
    ).first()
    return None if target is None or target.hidden_from(user) else target


def _apply_device_status(user: models.User, body) -> None:
    """Estado del móvil que viaja con cada ping. Lo comparten el ping suelto y el vaciado de
    la cola."""
    user.battery_level = body.battery_level
    user.is_charging = body.is_charging
    user.wifi_connected = body.wifi_connected
    user.wifi_ssid = body.wifi_ssid
    # Solo si viene: un cliente con una versión anterior no manda este campo, y asignarlo a
    # ciegas borraba en cada ping (cada pocos segundos) la frecuencia que ese mismo usuario
    # había registrado por /auth/device — los demás lo veían siempre como "desconocida".
    if body.location_frequency is not None:
        user.location_frequency = body.location_frequency
    # Mismo motivo que arriba: "" (todo correcto) es un valor válido y distinto de None
    # (cliente antiguo que no lo manda), así que la guarda mira is not None, no si es falsy.
    if body.config_issues is not None:
        user.config_issues = body.config_issues
    if body.update_mode is not None:
        user.update_mode = body.update_mode


@router.post("/ping", response_model=schemas.LocationPingResponse)
def ping(
    body: schemas.LocationPingRequest,
    tasks: BackgroundTasks,
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    db.add(models.LocationPing(user_id=user.id, lat=body.lat, lng=body.lng, accuracy=body.accuracy))
    # Ultima senal de vida de este movil: la mira set_live_tracking para decidir si repetir el
    # push de seguimiento. ponytail: dict en memoria, igual que la propia marca de seguimiento
    # — si el contenedor reinicia, lo peor que pasa es un push de mas.
    _last_ping[user.id] = monotonic()

    _apply_device_status(user, body)

    _purge_old_pings(db)
    db.commit()

    # Modo prueba: mientras un administrador esté arrastrando a este usuario por el mapa, su
    # posición real se guarda igual (arriba) pero no evalúa zonas — si no, este mismo ping
    # desharía la simulación al instante. Si la simulación caducó sin que el admin saliera del
    # modo, este primer ping real recoloca su estado de zonas en silencio.
    simulation = geofence.simulation_status(user.id)
    if simulation != "active":
        geofence.check_zone_transitions(
            db, user, body.lat, body.lng, accuracy=body.accuracy, notify=simulation is None, tasks=tasks
        )
    return schemas.LocationPingResponse(live_seconds=_live_seconds(user.id))


@router.post("/pings", response_model=schemas.LocationPingResponse)
def ping_batch(
    body: schemas.LocationPingBatchRequest,
    tasks: BackgroundTasks,
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    """Los pings que el móvil no pudo entregar en su momento (sin cobertura, servidor caído)
    y suelta de golpe al recuperar la red. Van todos en UNA petición a propósito: la cola se
    vacía cuando el móvil ya está despierto por otra cosa, y una petición por punto sería
    justo el gasto de radio que la cola viene a evitar."""
    now = datetime.now(timezone.utc)
    oldest_allowed = now - timedelta(days=RETENTION_DAYS)
    stored = []
    for item in body.pings:
        timestamp = item.timestamp
        if timestamp.tzinfo is None:
            timestamp = timestamp.replace(tzinfo=timezone.utc)
        # El reloj del móvil no es de fiar: una hora futura dejaría ese punto por encima de
        # todos los que lleguen después, congelando a esa persona en el mapa hasta que la
        # hora falsa quedara atrás de verdad.
        timestamp = min(timestamp, now)
        if timestamp < oldest_allowed:
            continue
        stored.append((timestamp, item))
        db.add(
            models.LocationPing(
                user_id=user.id,
                lat=item.lat,
                lng=item.lng,
                accuracy=item.accuracy,
                timestamp=timestamp,
            )
        )
    _last_ping[user.id] = monotonic()
    _apply_device_status(user, body)
    _purge_old_pings(db)
    db.commit()

    # Las zonas se evalúan SOLO con el punto más reciente: reproducir una ruta de hace dos
    # horas dispararía la ristra entera de entradas y salidas de entonces, todas a la vez y
    # todas fuera de hora. Lo que importa ahora es dónde está esa persona.
    if stored:
        newest = max(stored, key=lambda pair: pair[0])[1]
        simulation = geofence.simulation_status(user.id)
        if simulation != "active":
            geofence.check_zone_transitions(
                db, user, newest.lat, newest.lng, accuracy=newest.accuracy, notify=simulation is None, tasks=tasks
            )
    return schemas.LocationPingResponse(live_seconds=_live_seconds(user.id))


@router.get("/group/latest", response_model=list[schemas.LocationResponse])
def group_latest(
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    members = db.query(models.User).filter(models.User.group_id == user.group_id).all()
    results = []
    for member in members:
        if member.hidden_from(user):
            continue
        latest = (
            db.query(models.LocationPing)
            .filter(models.LocationPing.user_id == member.id)
            .order_by(models.LocationPing.timestamp.desc())
            .first()
        )
        if latest:
            results.append(
                schemas.LocationResponse(
                    user_id=member.id,
                    display_name=member.display_name,
                    avatar_url=member.avatar_url,
                    lat=latest.lat,
                    lng=latest.lng,
                    accuracy=latest.accuracy,
                    timestamp=latest.timestamp,
                    battery_level=member.battery_level,
                    is_charging=member.is_charging,
                    wifi_connected=member.wifi_connected,
                    wifi_ssid=member.wifi_ssid,
                    location_frequency=member.location_frequency,
                    config_issues=member.config_issues,
                    update_mode=member.update_mode,
                    # Para que quien mira el mapa vea si el tiempo real que ha pedido ha
                    # prendido de verdad en el otro movil, y no solo que pulso el boton.
                    live_seconds=_live_confirmed_seconds(member.id),
                )
            )
    return results


@router.get("/history", response_model=list[schemas.LocationHistoryPoint])
def history(
    hours: int = Query(default=24, ge=1, le=24 * RETENTION_DAYS),
    from_ts: datetime | None = None,
    to_ts: datetime | None = None,
    user_id: str | None = None,
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    """[from_ts, to_ts): rango exacto (para "ayer"/"hoy"/fecha elegida en la app, calculado
    en el cliente con su huso horario). Sin ellos: ventana relativa de [hours]."""
    target_id = user_id or user.id
    if target_id != user.id and _group_member(db, user, target_id) is None:
        return []

    query = db.query(models.LocationPing).filter(models.LocationPing.user_id == target_id)
    if from_ts is not None and to_ts is not None:
        query = query.filter(models.LocationPing.timestamp >= from_ts, models.LocationPing.timestamp < to_ts)
    else:
        cutoff = datetime.now(timezone.utc) - timedelta(hours=hours)
        query = query.filter(models.LocationPing.timestamp >= cutoff)
    points = query.order_by(models.LocationPing.timestamp.asc()).all()
    # Los picos se quitan al pintar, no al guardar: lo guardado es lo que el móvil dijo.
    return smoothing.drop_outliers(points)


@router.post("/ring/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
def ring_device(
    user_id: str,
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    target = _group_member(db, user, user_id)
    if not target:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Member not found in your group")

    push.send_to_user(
        db,
        user_id=target.id,
        title="Lokate",
        body=f"{user.display_name} quiere localizar tu dispositivo",
        data={"type": "ring"},
    )


@router.post("/stop-ring/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
def stop_ring_device(
    user_id: str,
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    """Push silencioso que para la alarma que /ring dejó sonando en ese dispositivo — lo manda
    quien pulsa "Detener" en el diálogo de "hacer sonar"."""
    target = _group_member(db, user, user_id)
    if not target:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Member not found in your group")

    push.send_to_user(
        db,
        user_id=target.id,
        title="Lokate",
        body="Parar alarma",
        data={"type": "stop_ring"},
    )


@router.post("/live/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
def set_live_tracking(
    user_id: str,
    active: bool = True,
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    """Pone (o quita) a ese miembro en seguimiento en vivo: su movil pasa a tiempo real mientras
    dure. Quien sigue debe volver a llamar cada minuto para renovar; si deja de hacerlo, la
    marca caduca sola a los LIVE_TTL_S y el seguido vuelve a su modo de siempre.

    El push sale al empezar y se repite en las renovaciones mientras el seguido siga sin dar
    senales (ver LIVE_REPUSH_SILENT_S): si el primero se pierde — movil dormido, fabricante
    agresivo — el siguiente lo despierta, en vez de quedarse esperando a su proximo ping, que
    en reposo puede tardar 15 minutos. Lo demas (renovar, terminar) viaja gratis en la
    respuesta de sus propios pings (ver _live_seconds)."""
    target = _group_member(db, user, user_id)
    if not target:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Member not found in your group")

    if not active:
        _live_stop(target.id)
        return

    now = monotonic()
    was_live = _live_seconds(target.id) > 0
    if was_live and now - _live_started.get(target.id, now) >= LIVE_MAX_S:
        # Tope de seguridad: se acabo la sesion, no se renueva mas. El seguido vuelve a su
        # ritmo solo, sin depender de que el que sigue se acuerde de soltarlo.
        _live_stop(target.id)
        return

    _live_until[target.id] = now + LIVE_TTL_S
    if not was_live:
        _live_started[target.id] = now
    if not was_live or now - _last_ping.get(target.id, 0.0) >= LIVE_REPUSH_SILENT_S:
        push.send_to_user(
            db,
            user_id=target.id,
            title="Lokate",
            body="Seguimiento en vivo",
            data={"type": "live_tracking", "seconds": str(LIVE_TTL_S)},
        )


@router.post("/request-location/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
def request_location(
    user_id: str,
    user: models.User = Depends(get_current_user_with_group),
    db: Session = Depends(get_db),
):
    """Push silencioso (sin sonido/notificación) que pide a ese dispositivo una ubicación
    puntual fresca — a diferencia de /ring, que solo hace sonar la alarma."""
    target = _group_member(db, user, user_id)
    if not target:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Member not found in your group")

    push.send_to_user(
        db,
        user_id=target.id,
        title="Lokate",
        body="Solicitud de ubicación",
        data={"type": "request_location"},
    )
