import uuid
from datetime import datetime, timezone

from sqlalchemy import Boolean, Column, DateTime, Float, ForeignKey, Index, Integer, String
from sqlalchemy.orm import relationship

from .database import Base


def gen_id() -> str:
    return str(uuid.uuid4())


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Group(Base):
    __tablename__ = "groups"

    id = Column(String, primary_key=True, default=gen_id)
    name = Column(String, nullable=False)
    invite_code = Column(String, unique=True, nullable=False, index=True)
    created_at = Column(DateTime, default=utcnow)

    users = relationship("User", back_populates="group")
    zones = relationship("Zone", back_populates="group", cascade="all, delete-orphan")

    def __str__(self) -> str:  # legible en el panel admin
        return self.name


class User(Base):
    __tablename__ = "users"

    id = Column(String, primary_key=True, default=gen_id)
    username = Column(String, unique=True, nullable=False, index=True)
    password_hash = Column(String, nullable=False)
    display_name = Column(String, nullable=False)
    avatar_url = Column(String, nullable=True)
    group_id = Column(String, ForeignKey("groups.id"), nullable=True, index=True)
    fcm_token = Column(String, nullable=True)
    battery_level = Column(Integer, nullable=True)
    is_charging = Column(Boolean, nullable=True)
    wifi_connected = Column(Boolean, nullable=True)
    wifi_ssid = Column(String, nullable=True)
    # Frecuencia de actualización que ese usuario tiene elegida en su app (REAL_TIME /
    # BALANCED / BATTERY_SAVER / DISABLED): la manda en cada ping y en /auth/device, y el resto
    # del grupo la ve en su ficha. Con DISABLED no llegan pings, por eso importa que /auth/device
    # también la registre: es la única vía por la que el grupo se entera de que está apagada.
    location_frequency = Column(String, nullable=True)
    # Como esta mandando posicion ese movil AHORA mismo (moving / still / home_wifi / live /
    # on_demand): lo decide su propia app (ver UpdateMode.kt) y es lo que explica que alguien
    # con "cada 30 segundos" lleve 7 minutos sin actualizar — en reposo su movil espacia a 15
    # min a proposito. NULL = su app es anterior a esta version y no lo manda.
    update_mode = Column(String, nullable=True)
    # Lista separada por comas de lo que ese usuario tiene SIN configurar en su móvil
    # (permisos denegados, batería sin excluir, GPS apagado...). Cadena vacía = todo correcto;
    # NULL = su app es anterior a esta versión y no lo manda. Viaja por el mismo camino que
    # location_frequency (ping + /auth/device) y el grupo la ve en su ficha.
    config_issues = Column(String, nullable=True)
    # Id del canal de notificaciones de zona en SU móvil. El push de zona lo lleva como
    # android_channel_id para que el aviso lo pinte el SISTEMA aunque la app esté muerta: un
    # push "solo data" obliga a arrancar el proceso, y en un móvil con el envío de ubicación
    # desactivado no hay servicio en primer plano que lo mantenga vivo (de ahí los avisos que
    # llegaban con minutos de retraso). El id cambia cuando el usuario cambia el sonido o la
    # vibración —un canal es inmutable—, por eso lo manda el móvil en cada /auth/device.
    # NULL o vacío = no quiere avisos de zona, o su app es anterior: se le manda "solo data".
    zone_channel_id = Column(String, nullable=True)
    is_admin = Column(Boolean, nullable=False, default=False)
    # Grupos (ids separados por comas) en los que este usuario NO quiere que le vean: no sale en
    # la lista de miembros ni en el mapa de los demás, y sus entradas y salidas de zona no avisan.
    # Es para que un administrador pueda entrar a mirar un grupo sin aparecer en él.
    # ponytail: CSV como los watched_ids de las preferencias, son un puñado de ids y siempre se leen enteros.
    hidden_group_ids = Column(String, nullable=True)
    # Ultimo informe de bateria que subio su movil, JSON tal cual lo escribio la app mas la
    # fecha de recepcion (ver routers/locations.upload_battery_report). Solo lo mira un admin
    # desde la ficha de ese usuario; el servidor no interpreta nada de dentro.
    # ponytail: una columna de texto con el ultimo y ya, como UserBackup — el historico no le
    # hace falta a nadie y desglosarlo en columnas obligaria a tocar el servidor cada vez que
    # la app anade un contador.
    battery_report = Column(String, nullable=True)
    # Version de la app que tiene instalada ese movil (BuildConfig.VERSION_NAME). La manda en
    # cada /auth/device y sirve para una sola cosa: cuando cambia, avisar a los administradores
    # de que esa persona ya ha actualizado. NULL = su app es anterior a esta version.
    app_version = Column(String, nullable=True)
    # Ultimo aviso de "actualiza la app" que se le mando y en que quedo. Tres columnas sueltas en
    # vez de una tabla de envios: de cada persona solo interesa el ultimo, y asi la lista que ve
    # el admin sale de un SELECT a users sin unir nada.
    update_notice_at = Column(DateTime, nullable=True)
    # "sent" (mandado, sin respuesta todavia), "started", "installed" o "dismissed" (ver
    # routers/auth.update_notice_status).
    update_notice_status = Column(String, nullable=True)
    update_notice_status_at = Column(DateTime, nullable=True)
    created_at = Column(DateTime, default=utcnow)

    group = relationship("Group", back_populates="users")

    @property
    def hidden_groups(self) -> list[str]:
        return [gid for gid in (self.hidden_group_ids or "").split(",") if gid]

    def is_hidden_in(self, group_id: str | None) -> bool:
        return bool(group_id) and group_id in self.hidden_groups

    def hidden_from(self, viewer: "User") -> bool:
        """¿Se esconde de quien mira? Uno nunca se esconde de sí mismo."""
        return self.id != viewer.id and self.is_hidden_in(viewer.group_id)

    def __str__(self) -> str:  # legible en el panel admin
        return self.display_name or self.username


class LocationPing(Base):
    __tablename__ = "location_pings"

    id = Column(String, primary_key=True, default=gen_id)
    # Sin index=True propio: el índice compuesto de abajo ya empieza por user_id, así que servía
    # para lo mismo y encarecía cada INSERT (y aquí los INSERT son todo el tráfico).
    user_id = Column(String, ForeignKey("users.id"), nullable=False)
    lat = Column(Float, nullable=False)
    lng = Column(Float, nullable=False)
    accuracy = Column(Float, nullable=True)
    timestamp = Column(DateTime, default=utcnow, index=True)

    # "La última posición de X" (el mapa, cada 15 s y por cada miembro) y "el historial de X
    # entre dos fechas" son LAS dos consultas de esta tabla, y las dos son user_id + timestamp.
    __table_args__ = (Index("ix_location_pings_user_ts", "user_id", "timestamp"),)


class Zone(Base):
    __tablename__ = "zones"

    id = Column(String, primary_key=True, default=gen_id)
    group_id = Column(String, ForeignKey("groups.id"), nullable=False, index=True)
    name = Column(String, nullable=False)
    lat = Column(Float, nullable=False)
    lng = Column(Float, nullable=False)
    radius_m = Column(Float, nullable=False)
    created_by = Column(String, ForeignKey("users.id"), nullable=False)
    # Privada = solo existe para quien la creo: no la ven ni la pueden tocar los demas, y sus
    # avisos solo le llegan a el. Por defecto publica, que es como funcionaban todas hasta ahora.
    is_public = Column(Boolean, nullable=False, default=True)
    created_at = Column(DateTime, default=utcnow)

    group = relationship("Group", back_populates="zones")

    def __str__(self) -> str:  # legible en el panel admin
        return self.name


class ZoneState(Base):
    """Last known inside/outside state per (user, zone), used to detect transitions."""

    __tablename__ = "zone_states"

    user_id = Column(String, ForeignKey("users.id"), primary_key=True)
    zone_id = Column(String, ForeignKey("zones.id"), primary_key=True)
    is_inside = Column(Boolean, nullable=False, default=False)
    updated_at = Column(DateTime, default=utcnow)


class ZoneNotificationPref(Base):
    """Por usuario y zona: si quiere que le avisen al entrar y/o al salir, y de quién. Sin fila =
    ninguno activado — el usuario tiene que activarlos él mismo, no vienen activados por defecto.
    Todo es de ese usuario: cada móvil elige sus avisos sin tocar los de los demás."""

    __tablename__ = "zone_notification_prefs"

    user_id = Column(String, ForeignKey("users.id"), primary_key=True)
    zone_id = Column(String, ForeignKey("zones.id"), primary_key=True)
    notify_on_enter = Column(Boolean, nullable=False, default=False)
    notify_on_exit = Column(Boolean, nullable=False, default=False)
    # De quién quiere que le avisen en esta zona, ids separados por comas. Vacío o NULL = de todo
    # el grupo, incluidos los que entren después.
    # ponytail: CSV en vez de tabla puente — son cuatro ids y siempre se leen enteros.
    watched_ids = Column(String, nullable=True)

    @property
    def watched_user_ids(self) -> list[str]:
        return [uid for uid in (self.watched_ids or "").split(",") if uid]




class ZoneEvent(Base):
    """Cada entrada y salida de zona que el servidor ha decidido de verdad, con a cuanta gente
    avisó y, si no avisó a nadie, por qué.

    Hasta ahora solo se guardaba el estado actual ([ZoneState]), así que "¿por qué no me llegó el
    aviso de ayer?" no tenía respuesta: había que reconstruirla de los pings a mano
    (app/zone_replay.py). Esto lo deja escrito en el momento en que pasa, que es lo único que
    distingue "no se aviso" de "se avisó y el push no llegó".

    ponytail: tabla propia y no una columna más en los pings — una transición ocupa nada (son
    unas pocas al día por persona) y los pings se barren cada 30 días con otra lógica."""

    __tablename__ = "zone_events"

    id = Column(String, primary_key=True, default=gen_id)
    user_id = Column(String, ForeignKey("users.id"), nullable=False)
    zone_id = Column(String, ForeignKey("zones.id"), nullable=False)
    # Redundante con el del usuario, pero el usuario puede cambiar de grupo y el evento no: es
    # dónde pasó, no dónde está ahora esa persona.
    group_id = Column(String, ForeignKey("groups.id"), nullable=False)
    entered = Column(Boolean, nullable=False)
    at = Column(DateTime, default=utcnow, index=True)
    lat = Column(Float, nullable=False)
    lng = Column(Float, nullable=False)
    accuracy = Column(Float, nullable=True)
    # Distancia al centro de la zona en ese momento: con ella y la precisión se ve de un vistazo
    # si la decisión fue holgada o justita, sin tener que repasar los pings.
    distance_m = Column(Float, nullable=True)
    notified = Column(Integer, nullable=False, default=0)
    # Por qué no se avisó a nadie (o en qué contexto raro se decidió). NULL = aviso normal.
    # Los códigos los traduce la app: no_prefs, hidden, private_zone, test, resync.
    reason = Column(String, nullable=True)

    user = relationship("User")
    zone = relationship("Zone")

    # La consulta del registro es siempre "lo de esta persona, lo más reciente primero".
    __table_args__ = (Index("ix_zone_events_user_at", "user_id", "at"),)

    def __str__(self) -> str:  # legible en el panel admin
        return ("entró en" if self.entered else "salió de") + f" {self.zone_id}"


class UserBackup(Base):
    """La copia de los ajustes del movil de un usuario: un JSON tal cual lo manda la app, una
    fila por usuario (la nueva pisa la anterior). El servidor no mira dentro ni lo interpreta —
    lo que haya que entender de ese JSON lo entiende la app que lo escribio.

    ponytail: una columna de texto y a correr. Desglosarlo en columnas obligaria a tocar el
    servidor cada vez que la app anade un ajuste, que es justo lo que no queremos."""

    __tablename__ = "user_backups"

    user_id = Column(String, ForeignKey("users.id"), primary_key=True)
    payload = Column(String, nullable=False)
    # Version de la app que hizo la copia, solo para poder diagnosticar restauraciones raras.
    app_version = Column(String, nullable=True)
    updated_at = Column(DateTime, default=utcnow, onupdate=utcnow)
