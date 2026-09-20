import uuid
from datetime import datetime, timezone

from sqlalchemy import Boolean, Column, DateTime, Float, ForeignKey, Integer, String
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
    # ponytail: CSV como watched_ids de las zonas, son un puñado de ids y siempre se leen enteros.
    hidden_group_ids = Column(String, nullable=True)
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
    user_id = Column(String, ForeignKey("users.id"), nullable=False, index=True)
    lat = Column(Float, nullable=False)
    lng = Column(Float, nullable=False)
    accuracy = Column(Float, nullable=True)
    timestamp = Column(DateTime, default=utcnow, index=True)


class Zone(Base):
    __tablename__ = "zones"

    id = Column(String, primary_key=True, default=gen_id)
    group_id = Column(String, ForeignKey("groups.id"), nullable=False, index=True)
    name = Column(String, nullable=False)
    lat = Column(Float, nullable=False)
    lng = Column(Float, nullable=False)
    radius_m = Column(Float, nullable=False)
    created_by = Column(String, ForeignKey("users.id"), nullable=False)
    created_at = Column(DateTime, default=utcnow)
    # Quién dispara los avisos de esta zona, separados por comas. Vacío o NULL = todo el grupo,
    # que es lo que quiere casi siempre y lo único que puede valer para los miembros que entren
    # en el grupo después de crear la zona.
    # ponytail: CSV en vez de tabla puente — son cuatro ids por zona y siempre se leen enteros.
    watched_ids = Column(String, nullable=True)

    group = relationship("Group", back_populates="zones")

    @property
    def watched_user_ids(self) -> list[str]:
        return [uid for uid in (self.watched_ids or "").split(",") if uid]

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
    """Por usuario y zona: si quiere que le avisen al entrar y/o al salir. Sin fila = ninguno
    activado — el usuario tiene que activarlos él mismo, no vienen activados por defecto."""

    __tablename__ = "zone_notification_prefs"

    user_id = Column(String, ForeignKey("users.id"), primary_key=True)
    zone_id = Column(String, ForeignKey("zones.id"), primary_key=True)
    notify_on_enter = Column(Boolean, nullable=False, default=False)
    notify_on_exit = Column(Boolean, nullable=False, default=False)


