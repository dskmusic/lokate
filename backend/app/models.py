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
    is_admin = Column(Boolean, nullable=False, default=False)
    created_at = Column(DateTime, default=utcnow)

    group = relationship("Group", back_populates="users")

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
    """Por usuario y zona: si quiere que le avisen al entrar y/o al salir. Sin fila = ninguno
    activado — el usuario tiene que activarlos él mismo, no vienen activados por defecto."""

    __tablename__ = "zone_notification_prefs"

    user_id = Column(String, ForeignKey("users.id"), primary_key=True)
    zone_id = Column(String, ForeignKey("zones.id"), primary_key=True)
    notify_on_enter = Column(Boolean, nullable=False, default=False)
    notify_on_exit = Column(Boolean, nullable=False, default=False)


