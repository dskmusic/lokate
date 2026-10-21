import logging
import os
from datetime import datetime, timedelta, timezone
from math import atan2, cos, radians, sin, sqrt
from time import monotonic

from fastapi import BackgroundTasks
from sqlalchemy.orm import Session

from . import models, push

logger = logging.getLogger("lokate.geofence")

EARTH_RADIUS_M = 6_371_000

# Margen de histéresis: una vez dentro, hay que alejarse radius_m + este margen para contar
# como salida. Evita el parpadeo entrada/salida (y el bombardeo de notificaciones) cuando el
# GPS oscila cerca del borde o una carretera roza el límite de la zona.
ZONE_EXIT_MARGIN_M = 20

# Tope de lo que la precisión del fix puede endurecer el límite de una zona (ver
# _accuracy_margin). Sin tope, un fix de ±1 km dejaría la zona en nada.
MAX_ACCURACY_MARGIN_M = 150

# Techo de credibilidad de un fix: por encima de este error no decide nada de ninguna zona, ni
# para entrar ni para salir. El resto de la regla trata la precisión como una cota honesta ("a
# 601 m, ±93 m" prueba que estoy fuera de una zona de 30 m), y para un fix de GPS o de wifi lo
# es. Para el centroide de una antena no: declara ±800 m y su centro puede estar a kilómetros,
# porque no mide dónde estás sino dónde está la antena. Eso puso a una persona sentada en casa
# a 2186 m ±800 m tres veces en un cuarto de hora — mismas coordenadas exactas las tres, así que
# ni la confirmación de dos pings (CONFIRM_ACCURACY_M) la habría pillado: el fix malo se
# confirmaba a sí mismo.
#
# El número tiene que caer ENTRE los valores que manda Android, no encima de uno. La precisión no
# llega como un número continuo sino redondeada a unos pocos cubos (en la familia se ven 100, 200
# y 800), así que un techo de 200 lo pasaba un fix de ±200 clavados — "200 > 200" es falso — y eso
# sacó a la misma persona de su casa a los dos días de poner el techo, desde 631 m. 150 cae en el
# hueco entre el cubo de ±100 (wifi, el normal con el móvil quieto en casa, que sí decide) y el de
# ±200 (antenas, que no).
#
# ponytail: este es el botón de calibración de los falsos avisos, y el mismo número que el móvil
# se aplica para no mandarlos siquiera (LocationForegroundService.MAX_ACCURACY_METERS). Deja vivo
# el caso que motivó las salidas asimétricas (±93 m). Si se mueve, que sea a otro hueco entre
# cubos, no a un cubo. Medir con app/zone_replay.py antes de tocarlo.
MAX_TRUSTED_ACCURACY_M = 150

# A partir de este error, un fix no cambia el estado de una zona él solo: hace falta que el
# siguiente ping diga lo mismo. Es la frontera entre un fix de GPS (metros) y uno de wifi o
# antenas (decenas o cientos de metros), que es el que salta de sitio con el móvil quieto y
# producía entradas y salidas que no habían pasado. Un cruce de verdad sí se repite dos veces
# seguidas, porque moverse dispara la geocerca del sistema y sube la cadencia de pings.
#
# Es el mando de ajuste de esta protección: subirlo deja pasar más fixes a la primera (avisos más
# rápidos, más falsos positivos), bajarlo exige confirmación a casi todo.
CONFIRM_ACCURACY_M = 50

# Cuántas veces el radio puede medir el error de un fix y aún servir para ENTRAR. Un fix cuyo
# círculo cubre la zona entera y mucho más no prueba que estés dentro, pero el listón de antes
# (más error que el radio, a secas) dejaba tiradas las zonas pequeñas: una de 80 m con los fixes
# de wifi de ±100 m que manda un móvil quieto en casa era IMPOSIBLE de pisar, así que un falso
# aviso de salida no se deshacía solo — la persona se quedaba fuera hasta que alguien le pedía
# una ubicación precisa a mano (pasó el 04/10/2026). Con el doble, esa misma zona la decide un
# fix de wifi normal, y la de 30 m con ±93 m sigue sin decidirla nadie.
#
# Lo que impide que esto devuelva las entradas fantasma no es este número, es MAX_TRUSTED_ACCURACY_M:
# los fixes que metían a gente en zonas donde no había estado eran de antena (±200, ±800), y esos
# ya no llegan hasta aquí. Esto solo gradúa los que sí nos creemos.
ENTRY_ACCURACY_RATIO = 2

# Cuánto tiene que separar en el tiempo al ping que apunta un cambio dudoso del que lo confirma.
# Existe porque "que otro ping lo repita" no se estaba cumpliendo con otro ping: al cruzar un
# borde, el GeofenceReceiver y el servicio en primer plano mandan EL MISMO fix en dos peticiones,
# y la segunda confirmaba lo que la primera acababa de apuntar. Eso sacó a una persona de su casa
# el 11/10/2026 con un fix de wifi a 293 m ±129 m: dos filas en el mismo segundo ("salida
# pendiente" y "salida -> aviso"), y cuatro minutos después estaba dentro a 3 m ±17 m. Un fix
# duplicado no es una segunda opinión, es la misma.
#
# ponytail: por el reloj y no comparando coordenadas, que habría que guardar aparte. El techo es
# ese: un duplicado que tardara más que esto en llegar volvería a colarse. 20 s cabe de sobra
# entre los dos envíos del mismo fix, y queda por debajo de los ~30 s que hay entre pings de
# alguien que se mueve de verdad, que es el único caso al que esto podría costarle un retraso.
MIN_CONFIRM_GAP_S = 20

# Ancla de wifi. Estar conectado a la wifi de un sitio es la única prueba de estar ahí que no
# sale del GPS, y la única que sirve contra un fix de wifi con el centro equivocado: ese declara
# el error normal (±100 m, igual que los buenos) pero apunta a donde está registrado OTRO punto
# de acceso, y se puso a 495 m de una casa con el móvil dentro. Por precisión es indistinguible
# de un fix bueno, así que ni el techo ni la confirmación de dos pings lo pueden parar — de
# hecho el malo llegó dos veces seguidas y se habría confirmado a sí mismo.
#
# Qué wifis son de cada zona lo dice zones.wifi_ssids, y cada usuario puede apagarlo entero
# (users.wifi_anchor). Una zona sin wifis apuntadas se comporta como siempre.
#
# El margen cubre el parpadeo: la wifi se cae y se reengancha sola, y es justo en ese hueco
# cuando el móvil escanea y saca el fix malo. Se paga en salidas de verdad, que pueden tardar
# hasta ese margen en avisar, y por eso no es más grande.
# ponytail: los dos son calibración, no diseño. Si las salidas de verdad llegan tarde, baja el
# margen; si siguen colándose falsos positivos en el hueco de la wifi, súbelo.
WIFI_ANCHOR_GRACE_MIN = 10
# ...pero el margen no tapa un fix que diga que esa persona está lejos de verdad: a esa distancia
# ya no es el error de una base de datos de wifis, es que se ha ido. Sin esto, alguien que sale
# de casa en coche se queda clavado dentro hasta que el margen caduque.
WIFI_ANCHOR_MAX_JUMP_M = 1000

# Cuánto se guarda el registro de entradas y salidas. Lo mismo que los pings por defecto, para
# que el registro y las posiciones que lo explican caduquen a la vez: un evento sin sus pings no
# se puede repasar con app/zone_replay.py.
EVENT_RETENTION_DAYS = int(os.getenv("ZONE_EVENT_RETENTION_DAYS", os.getenv("LOCATION_RETENTION_DAYS", "30")))
# La escoba pasa como mucho una vez al día: aquí se entra solo cuando alguien cruza un borde,
# que son unas pocas veces al día en todo el servidor.
_PURGE_EVERY = timedelta(days=1)
_last_purge = datetime.min.replace(tzinfo=timezone.utc)


def _purge_old_events(db: Session) -> None:
    global _last_purge
    now = datetime.now(timezone.utc)
    if now - _last_purge < _PURGE_EVERY:
        return
    _last_purge = now
    db.query(models.ZoneEvent).filter(
        models.ZoneEvent.at < now - timedelta(days=EVENT_RETENTION_DAYS)
    ).delete()

# ---- Modo prueba de los administradores ----
# Mientras un admin arrastra a alguien por el mapa para probar los avisos, la posición REAL de
# ese alguien se sigue guardando pero deja de evaluar zonas: si no, su siguiente ping (cada
# pocos segundos si comparte en tiempo real) desharía la simulación al instante y dispararía el
# aviso contrario. Vive en memoria a propósito: si el servidor se reinicia, nadie se queda
# congelado. El TTL es la red de seguridad para cuando la app del admin muere sin salir del modo.
SIMULATION_TTL_S = 15 * 60
_simulated_until: dict[str, float] = {}


def start_simulation(user_id: str) -> None:
    _simulated_until[user_id] = monotonic() + SIMULATION_TTL_S


def simulation_status(user_id: str) -> str | None:
    """None = posición real. "active" = simulada ahora mismo. "expired" = lo estuvo y ha
    caducado; el primer ping real que llegue debe recolocar su estado de zonas EN SILENCIO,
    porque la "transición" de volver a su sitio nunca ocurrió de verdad."""
    until = _simulated_until.get(user_id)
    if until is None:
        return None
    if until > monotonic():
        return "active"
    del _simulated_until[user_id]
    return "expired"


def stop_simulation(user_ids: list[str]) -> None:
    for user_id in user_ids:
        _simulated_until.pop(user_id, None)


def resync_zone_states(db: Session, user_ids: list[str]) -> None:
    """Devuelve el estado de zonas de cada usuario a lo que dice su última posición REAL, sin
    avisar a nadie: al salir del modo prueba hay que deshacer las transiciones simuladas, pero
    sin disparar los avisos contrarios (nadie ha entrado ni salido de nada)."""
    for user_id in user_ids:
        user = db.get(models.User, user_id)
        if user is None:
            continue
        last = (
            db.query(models.LocationPing)
            .filter(models.LocationPing.user_id == user_id)
            .order_by(models.LocationPing.timestamp.desc())
            .first()
        )
        if last is not None:
            check_zone_transitions(db, user, last.lat, last.lng, notify=False)


def distance_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    """Haversine distance in meters."""
    phi1, phi2 = radians(lat1), radians(lat2)
    d_phi = radians(lat2 - lat1)
    d_lambda = radians(lng2 - lng1)
    a = sin(d_phi / 2) ** 2 + cos(phi1) * cos(phi2) * sin(d_lambda / 2) ** 2
    return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))


def watches(watched_ids: list[str], user_id: str) -> bool:
    """¿Los movimientos de este usuario disparan este aviso? Lista vacía = avisa de todos,
    incluidos los que entren en el grupo más adelante."""
    return not watched_ids or user_id in watched_ids


def _notification_recipients(
    db: Session, group_id: str, moved_user_id: str, zone_id: str, is_inside: bool,
) -> tuple[list[str], list[str]]:
    """(a quien se avisa, quien lo tiene puesto pero ya no esta en el grupo).

    Quieren el aviso los que tienen fila de preferencia para esta zona, en esta direccion y de
    esta persona. Sin fila guardada se asume que NO — el usuario tiene que activarlos el mismo,
    no vienen activados por defecto. Cada uno tiene su propia lista: es un ajuste de su movil.

    La segunda lista existe por el caso del 06/10/2026: un admin se cambio de grupo (ver
    routers/groups.switch_group) y durante toda una manana sus seis avisos de zona dejaron de
    llegarle sin que nada lo dijera — las preferencias seguian intactas, pero el push solo sale a
    los MIEMBROS del grupo. Se recorren las preferencias y no los miembros justamente para poder
    ver a los que ya no estan; antes la consulta los filtraba y eran invisibles.
    """
    members = {row.id for row in db.query(models.User.id).filter(models.User.group_id == group_id).all()}
    recipients: list[str] = []
    missed: list[str] = []
    prefs = db.query(models.ZoneNotificationPref).filter(
        models.ZoneNotificationPref.zone_id == zone_id,
    ).all()
    for pref in prefs:
        if pref.user_id == moved_user_id:
            continue
        wants = pref.notify_on_enter if is_inside else pref.notify_on_exit
        if not (wants and watches(pref.watched_user_ids, moved_user_id)):
            continue
        (recipients if pref.user_id in members else missed).append(pref.user_id)
    return recipients, missed


def _accuracy_margin(accuracy: float | None, radius_m: float) -> float:
    """Cuánto hay que apretar el límite de una zona por culpa del margen de error del fix.

    Un ping que dice "estoy aquí, ±300 m" no prueba nada sobre una zona de 100 m de radio: si
    se acepta tal cual, el grupo recibe entradas y salidas que no han pasado. Exigiendo estar
    dentro con esa holgura, un fix malo simplemente no decide nada y se espera al siguiente.

    Se acota por dos sitios: nunca más de MAX_ACCURACY_MARGIN_M, y nunca más de la mitad del
    radio — si no, una zona pequeña se volvería imposible de pisar en un día de GPS regular.
    """
    if not accuracy or accuracy <= 0:
        return 0.0
    return min(accuracy, MAX_ACCURACY_MARGIN_M, radius_m / 2)


def evaluate(
    distance: float,
    accuracy: float | None,
    radius_m: float,
    was_inside: bool,
) -> tuple[bool | None, float]:
    """¿Qué dice este fix de esta zona? (dentro/fuera/None = no dice nada, y el umbral que le
    tocaba, para poder contarlo). Es LA regla de las entradas y salidas: la usan el servidor y
    app/zone_replay.py, para que el repaso no mienta.

    Asimétrica a propósito, porque las dos direcciones no cuestan lo mismo de probar:

    - Para SALIR basta con que el círculo de error entero quede fuera de la zona más el margen de
      histéresis: "a 601 m, ±93 m" no deja ninguna duda sobre una zona de 30 m, aunque ese error
      sea el triple del radio. Antes se descartaba cualquier fix con más error que el radio, y
      como al irse de un sitio el móvil se queda quieto y solo manda fixes de wifi (±100 m), la
      salida esperaba a un fix bueno: 14 minutos de retraso medidos con app/zone_replay.py.
    - Para ENTRAR sí hace falta un fix que lo pruebe: uno con el círculo de error mucho mayor que
      la zona (ver ENTRY_ACCURACY_RATIO) no dice nada, y es justo el que metía a gente en zonas
      donde no había estado. Además el borde se aprieta lo que mida el error (_accuracy_margin),
      así que entrar exige caer bien adentro, no rozar el borde.

    Cuando el círculo de error pisa el borde, devuelve el estado que ya había: ese fix no decide
    y se espera al siguiente. Y un fix con más error que MAX_TRUSTED_ACCURACY_M no decide nunca:
    a esa escala la precisión que declara ya no es una cota de la que fiarse.
    """
    if accuracy and accuracy > MAX_TRUSTED_ACCURACY_M:
        return None, radius_m
    if was_inside:
        threshold = radius_m + ZONE_EXIT_MARGIN_M
        return distance - (accuracy or 0.0) <= threshold, threshold
    if accuracy and accuracy > radius_m * ENTRY_ACCURACY_RATIO:
        return None, radius_m
    threshold = radius_m - _accuracy_margin(accuracy, radius_m)
    return distance <= threshold, threshold


def beyond_doubt(distance: float, threshold: float, accuracy: float | None, is_inside: bool) -> bool:
    """¿Está tan al otro lado del borde que no hace falta que otro ping lo confirme?

    El umbral ya descuenta una vez el error del fix; aquí se exige otro tanto de propina. Sirve
    para no cobrar un intervalo de ping entero de retraso (que en reposo son 5 o 15 minutos) en
    el caso normal: quien sale de casa se va calle abajo, no se queda temblando en el borde.
    """
    doubt = 2 * (accuracy or 0.0)
    return distance + doubt <= threshold if is_inside else distance - doubt > threshold


def confirms(
    pending_inside: bool | None,
    pending_at: datetime | None,
    is_inside: bool,
    now: datetime,
) -> bool:
    """¿El candidato que hay apuntado confirma este cambio? Lo usan el servidor y
    app/zone_replay.py, para que el repaso no mienta.

    Pide dos cosas: que diga lo mismo, y que lo haya apuntado un fix ANTERIOR de verdad (ver
    MIN_CONFIRM_GAP_S) y no la copia del mismo ping llegando por el otro camino.

    pending_at a None son las filas de antes de esta columna: se les cree, como se hacía antes.
    """
    if pending_inside != is_inside:
        return False
    if pending_at is None:
        return True
    return _naive_utc(now) - _naive_utc(pending_at) >= timedelta(seconds=MIN_CONFIRM_GAP_S)


def _naive_utc(moment: datetime) -> datetime:
    """UTC sin zona, que es como SQLite devuelve las fechas que guarda."""
    return moment.astimezone(timezone.utc).replace(tzinfo=None) if moment.tzinfo else moment


def _wifi_anchor(
    user: models.User,
    zone: models.Zone,
    state: models.ZoneState | None,
    wifi_ssid: str | None,
    distance: float,
    now: datetime | None = None,
) -> bool | None:
    """¿La wifi dice que esta persona está en esta zona? None = no dice nada, decide el fix.

    Devuelve True solo mientras haya prueba: conectado AHORA a una wifi de la zona, o conectado
    hace menos de WIFI_ANCHOR_GRACE_MIN y sin que el fix la ponga lejos de verdad. Nunca
    devuelve False: no estar en la wifi no prueba que te hayas ido (pasa cada noche con el wifi
    apagado), y forzar una salida con eso sería el falso positivo de siempre al revés.

    now es para app/zone_replay.py, que repasa pings de ayer: el margen se mide contra la hora
    del ping que se está repasando, no contra el reloj de ahora.
    """
    if user.wifi_anchor is False:
        return None
    if wifi_ssid and wifi_ssid in zone.wifi_ssid_list:
        return True
    if state is None or state.wifi_seen_at is None or not zone.wifi_ssid_list:
        return None
    momento = _naive_utc(now or datetime.now(timezone.utc))
    if momento - _naive_utc(state.wifi_seen_at) > timedelta(minutes=WIFI_ANCHOR_GRACE_MIN):
        return None
    return distance < WIFI_ANCHOR_MAX_JUMP_M or None


def check_zone_transitions(
    db: Session,
    user: models.User,
    lat: float,
    lng: float,
    *,
    accuracy: float | None = None,
    wifi_ssid: str | None = None,
    notify: bool = True,
    recipients_override: list[str] | None = None,
    tasks: BackgroundTasks | None = None,
) -> list[tuple[str, int]]:
    """Compara la posición contra cada zona del grupo y avisa al entrar/salir.

    Un fix malo no cambia nada él solo: o no decide (ver evaluate) o deja el cambio apuntado
    como candidato hasta que otro ping POSTERIOR lo repita (ver CONFIRM_ACCURACY_M y confirms). Por eso un ping puede
    no devolver transición aunque la posición esté al otro lado del borde.

    notify=False actualiza el estado sin mandar nada (resincronizar tras el modo prueba).
    tasks (el del ping): manda los push DESPUÉS de contestar, porque FCM tarda cientos de ms por
    destinatario y mientras tanto el ping retiene un hilo del pool. Sin él se envían en el acto,
    que es lo que quiere el modo prueba del panel: el admin ve el informe cuando ya ha salido.
    recipients_override sustituye a las preferencias por zona de cada uno: en el modo prueba el
    admin elige a mano a quién le llega el aviso, en vez de que lo decida quién lo tenga activado.
    Devuelve (texto del aviso, nº de destinatarios) por cada transición detectada.
    """
    report: list[tuple[str, int]] = []
    pendientes: list[dict] = []
    zones = db.query(models.Zone).filter(models.Zone.group_id == user.group_id).all()
    for zone in zones:
        state = db.get(models.ZoneState, (user.id, zone.id))
        was_inside = state.is_inside if state else False

        distance = distance_m(lat, lng, zone.lat, zone.lng)
        # La wifi manda sobre el fix cuando habla (ver _wifi_anchor): dentro y no hay más que
        # discutir, ni umbral que calcular ni confirmación que esperar.
        anchored = _wifi_anchor(user, zone, state, wifi_ssid, distance)
        verdict, threshold = (
            (True, zone.radius_m) if anchored else evaluate(distance, accuracy, zone.radius_m, was_inside)
        )
        if verdict is None:
            # Fix que no dice nada de esta zona (ver evaluate): se deja el estado como estaba.
            continue
        is_inside = verdict

        if state is None:
            # Nace con el estado de antes (fuera) y lo confirma más abajo: así el candidato
            # pendiente funciona igual en la primera evaluación que en las siguientes.
            state = models.ZoneState(user_id=user.id, zone_id=zone.id, is_inside=was_inside)
            db.add(state)

        # El reloj del margen se pone a cero con cada ping que llegue en la wifi de la zona, no
        # solo cuando el estado cambia: lo normal es estar dentro y que no cambie nada.
        if wifi_ssid and wifi_ssid in zone.wifi_ssid_list:
            state.wifi_seen_at = models.utcnow()

        if is_inside == was_inside:
            # Vuelve a decir lo de siempre: lo que hubiera pendiente era ruido de un fix suelto.
            state.pending_inside = None
            continue

        # Fix de los que no deciden solos (ver CONFIRM_ACCURACY_M): el cambio se queda apuntado
        # como candidato y no pasa nada más. Si el siguiente ping lo repite, entra por aquí con
        # el candidato ya puesto y sigue hacia el aviso; si lo contradice, se borra arriba.
        if (
            not anchored
            and accuracy is not None
            and accuracy > CONFIRM_ACCURACY_M
            and not confirms(state.pending_inside, state.pending_at, is_inside, models.utcnow())
            # ...salvo si el fix, con su error y otro tanto de propina, está clarísimamente al
            # otro lado: eso ya no lo confirma nadie mejor, y esperar cuesta un ping entero.
            and not beyond_doubt(distance, threshold, accuracy, is_inside)
        ):
            if state.pending_inside != is_inside:
                # El reloj se pone en marcha con el candidato y no con cada ping que lo repita:
                # si no, una copia que llegara detrás lo atrasaría y el cambio no llegaría nunca.
                state.pending_inside = is_inside
                state.pending_at = models.utcnow()
            continue

        # Escribir el estado es LA sección crítica, y por eso no se asigna: se pelea. El mismo
        # fix puede llegar dos veces A LA VEZ — al cruzar un borde, el ping del GeofenceReceiver
        # y el del servicio en primer plano son la misma posición en dos peticiones, cada una con
        # su sesión — y las dos leyeron is_inside arriba, antes de que ninguna lo cambiara. El
        # 05/10/2026 eso mandó dos veces "Luci ha salido de Host house" con 1 ms de diferencia:
        # dos filas en zone_events y dos push de un único cruce. Con el UPDATE condicionado a que
        # la fila siga como la leímos, el que llega segundo no encuentra nada que cambiar y se va
        # sin avisar: la base de datos decide quién gana, que es lo único que ven los dos.
        # ponytail: esto y no un candado en memoria — seguiría valiendo con más de un worker.
        db.flush()  # la fila puede acabar de nacer unas líneas más arriba
        won = db.query(models.ZoneState).filter(
            models.ZoneState.user_id == user.id,
            models.ZoneState.zone_id == zone.id,
            models.ZoneState.is_inside == was_inside,
        ).update({"is_inside": is_inside, "pending_inside": None}, synchronize_session=False)
        if not won:
            # Otro ping con esta misma posición llegó primero y ya avisó: aquí no hay transición.
            continue
        state.is_inside = is_inside
        state.pending_inside = None

        verb = "ha entrado en" if is_inside else "ha salido de"
        body = f"{user.display_name} {verb} {zone.name}"
        # reason acompaña al evento guardado: es la diferencia entre "no te avisó" y "no te
        # podía avisar", que es justo lo que se pregunta uno tres días después.
        reason = None
        missed: list[str] = []
        if recipients_override is not None:
            recipients = [uid for uid in recipients_override if uid != user.id]
            reason = "test"
        elif user.is_hidden_in(user.group_id):
            # Escondido en este grupo: sus idas y venidas no avisan a nadie.
            recipients = []
            reason = "hidden"
        else:
            recipients, missed = _notification_recipients(db, user.group_id, user.id, zone.id, is_inside)
        if missed:
            # El fallo silencioso que costo una manana entera de diagnostico: alguien con el aviso
            # puesto al que no se le puede mandar porque ya no esta en el grupo. Se grita aqui
            # ademas de guardarse en la fila, para que salga en `docker compose logs`.
            ausentes = db.query(models.User).filter(models.User.id.in_(missed)).all()
            logger.warning(
                "Aviso '%s' (%s): %s lo tiene activado pero ya NO esta en el grupo — no se le avisa",
                zone.name, "entrar" if is_inside else "salir",
                ", ".join(a.display_name for a in ausentes),
            )
        # Zona privada: solo avisa a quien la creo, aunque a otro le quedara una preferencia
        # guardada de cuando la zona era publica.
        if not zone.is_public:
            before = len(recipients)
            recipients = [uid for uid in recipients if uid == zone.created_by]
            if not recipients and before:
                reason = "private_zone"
        report.append((body, len(recipients)))
        # Lo que de verdad ha salido por el aire: con notify=False (recolocación en silencio al
        # salir del modo prueba) la transición es real pero a propósito no avisa a nadie.
        sent = len(recipients) if notify else 0
        if reason is None:
            reason = "resync" if not notify else ("no_prefs" if not recipients else None)

        db.add(models.ZoneEvent(
            user_id=user.id,
            zone_id=zone.id,
            group_id=user.group_id,
            entered=is_inside,
            lat=lat,
            lng=lng,
            accuracy=accuracy,
            distance_m=distance,
            notified=sent,
            notified_ids=",".join(recipients) if sent else None,
            missed_ids=",".join(missed) or None,
            reason=reason,
        ))
        _purge_old_events(db)

        if notify:
            aviso = dict(
                user_ids=recipients,
                title=zone.name,
                body=body,
                data={"type": "zone_transition", "zone_id": zone.id, "user_id": user.id, "entered": is_inside},
                # Lo pinta el sistema del móvil que lo recibe, sin arrancar su app: es el único
                # push que puede llegar tarde y seguir importando (el resto —hacer sonar,
                # emergencia— necesita ejecutar código nuestro sí o sí).
                system_notification=True,
            )
            if tasks is None:
                # Se apunta y se manda al final, con la transacción ya cerrada: desde que el
                # UPDATE de arriba gana la carrera hay un bloqueo de escritura abierto, y FCM
                # tarda cientos de ms por destinatario — mandar aquí dentro lo mantendría cogido
                # todo ese rato y los pings de los demás esperarían a que terminara. (Con tasks
                # no hace falta: esos salen cuando la petición ya ha contestado.)
                pendientes.append(aviso)
            else:
                tasks.add_task(push.send_to_users_bg, **aviso)

    db.commit()
    for aviso in pendientes:
        push.send_to_users(db, **aviso)
    return report


def _demo() -> None:
    """Las dos reglas, con los números del caso que las motivó: zona de 30 m de radio y fixes de
    wifi de ±100 m. Se ejecuta con: python -m app.geofence"""
    radius = 30.0

    # Dentro, y un fix la planta a 601 m con ±93: está fuera y no hay discusión, aunque su error
    # sea el triple del radio. Es el ping que antes se tiraba y retrasaba la salida 14 minutos.
    verdict, threshold = evaluate(601, 93, radius, was_inside=True)
    assert verdict is False, "una salida clarísima sigue sin contar"
    assert beyond_doubt(601, threshold, 93, verdict), "esa salida no debería esperar otro ping"

    # El mismo error pero pegada al borde: el círculo de error pisa la zona, así que no la saca.
    assert evaluate(60, 93, radius, was_inside=True)[0] is True, "un fix dudoso no puede sacar a nadie"

    # Fuera, y un fix malo encima de la zona: no prueba nada (son las entradas fantasma de wifi).
    assert evaluate(5, 93, radius, was_inside=False)[0] is None, "un fix peor que el radio mete a gente"

    # Y el reverso, que es lo que dejaba a alguien fuera de su propia casa: zona de 80 m y el fix
    # de wifi normal de un móvil quieto dentro (±100 m, centrado a 23 m). Cae a 2 veces el radio,
    # así que decide, pero solo porque el centro está bien adentro (el umbral baja a 40 m).
    assert evaluate(23, 100, 80.0, was_inside=False)[0] is True, "no se puede volver a casa sin GPS"
    assert evaluate(60, 100, 80.0, was_inside=False)[0] is False, "rozando el borde no se entra"

    # El caso del 03/10: dentro de una zona de 80 m y una antena la planta a 2186 m ±800 m. Las
    # cuentas de la salida salen (2186 - 800 > 100) y beyond_doubt la daba por segura, pero un
    # fix así no prueba nada: por encima del techo no decide, el estado se queda como estaba.
    assert evaluate(2186, 800, 80.0, was_inside=True)[0] is None, "una antena vuelve a sacar a gente de casa"
    assert evaluate(2186, 800, 80.0, was_inside=False)[0] is None, "una antena decide algo estando fuera"

    # El del 04/10: el mismo caso pero con la precisión en el cubo de ±200, justo encima del techo
    # que había entonces. Un fix que cae CLAVADO en el listón no puede colarse por el "mayor que".
    assert evaluate(631, 200, 80.0, was_inside=True)[0] is None, "un fix en el cubo de 200 saca a gente de casa"

    # El del 11/10: el mismo fix llegando dos veces (GeofenceReceiver + servicio) no se confirma
    # a sí mismo, aunque el segundo traiga el candidato ya apuntado en la mano.
    momento = datetime(2026, 10, 11, 14, 42, 44, tzinfo=timezone.utc)
    assert not confirms(False, momento, False, momento), "una copia del mismo fix confirma un cambio"
    assert not confirms(False, momento, False, momento + timedelta(seconds=1)), "1 s no es otro ping"
    # Pero el siguiente ping de verdad sí, y el candidato contrario nunca.
    assert confirms(False, momento, False, momento + timedelta(seconds=MIN_CONFIRM_GAP_S))
    assert not confirms(True, momento, False, momento + timedelta(minutes=5)), "confirma lo contrario"
    assert confirms(False, None, False, momento), "una fila anterior a pending_at tiene que valer"

    # Fuera y un fix bueno dentro: entra.
    assert evaluate(5, 10, radius, was_inside=False)[0] is True, "la entrada buena no cuenta"

    # Entrada justita: cuenta, pero no sin discusión — si el fix fuera malo, esperaría confirmación.
    verdict, threshold = evaluate(8, 29, radius, was_inside=False)
    assert verdict is True and not beyond_doubt(8, threshold, 29, verdict), "entrada justa dada por segura"

    print("ok")


if __name__ == "__main__":
    _demo()
