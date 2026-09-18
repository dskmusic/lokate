import re
from datetime import datetime

from pydantic import BaseModel, Field, field_validator

USERNAME_PATTERN = re.compile(r"^[a-zA-Z0-9_.]{3,30}$")


# ---- Auth ----
class RegisterRequest(BaseModel):
    username: str
    password: str = Field(min_length=8)
    display_name: str = Field(min_length=1, max_length=60)

    @field_validator("username")
    @classmethod
    def validate_username(cls, v: str) -> str:
        if not USERNAME_PATTERN.match(v):
            raise ValueError("El usuario debe tener 3-30 caracteres: letras, números, punto o guion bajo")
        return v.lower()


class LoginRequest(BaseModel):
    username: str
    password: str


class UpdateProfileRequest(BaseModel):
    display_name: str = Field(min_length=1, max_length=60)


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user: "UserResponse"


class UserResponse(BaseModel):
    id: str
    username: str
    display_name: str
    avatar_url: str | None
    group_id: str | None
    is_admin: bool

    model_config = {"from_attributes": True}


# ---- Groups ----
class GroupCreateRequest(BaseModel):
    name: str = Field(min_length=1, max_length=60)


class GroupJoinRequest(BaseModel):
    invite_code: str


class GroupResponse(BaseModel):
    id: str
    name: str
    invite_code: str

    model_config = {"from_attributes": True}


class GroupMemberResponse(BaseModel):
    id: str
    display_name: str
    username: str
    avatar_url: str | None

    model_config = {"from_attributes": True}


# ---- Locations ----
class LocationPingRequest(BaseModel):
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)
    accuracy: float | None = None
    battery_level: int | None = Field(default=None, ge=0, le=100)
    is_charging: bool | None = None
    wifi_connected: bool | None = None
    wifi_ssid: str | None = None


class LocationResponse(BaseModel):
    user_id: str
    display_name: str
    avatar_url: str | None
    lat: float
    lng: float
    accuracy: float | None
    timestamp: datetime
    battery_level: int | None
    is_charging: bool | None
    wifi_connected: bool | None
    wifi_ssid: str | None

    model_config = {"from_attributes": True}


class LocationHistoryPoint(BaseModel):
    lat: float
    lng: float
    accuracy: float | None
    timestamp: datetime

    model_config = {"from_attributes": True}


# ---- Zones ----
class ZoneCreateRequest(BaseModel):
    name: str = Field(min_length=1, max_length=60)
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)
    radius_m: float = Field(gt=0, le=50000)


class ZoneResponse(BaseModel):
    id: str
    name: str
    lat: float
    lng: float
    radius_m: float

    model_config = {"from_attributes": True}


# ---- Device ----
class RegisterDeviceRequest(BaseModel):
    fcm_token: str


class AvatarResponse(BaseModel):
    avatar_url: str


# ---- Preferencias de notificación por zona ----
class ZoneNotificationPrefResponse(BaseModel):
    zone_id: str
    notify_on_enter: bool
    notify_on_exit: bool

    model_config = {"from_attributes": True}


class ZoneNotificationPrefUpdateRequest(BaseModel):
    notify_on_enter: bool
    notify_on_exit: bool


# ---- Admin API (panel de administración nativo de la app, solo admins) ----
class AdminDashboardStats(BaseModel):
    groups: int
    users: int
    admins: int
    online_now: int
    pings_24h: int
    active_users_24h: int
    devices_with_push: int


class AdminActivityDay(BaseModel):
    label: str
    count: int
    pct: int


class AdminRecentActivityItem(BaseModel):
    user_id: str
    display_name: str
    avatar_url: str | None
    timestamp: datetime


class AdminDashboardResponse(BaseModel):
    stats: AdminDashboardStats
    activity_series: list[AdminActivityDay]
    recent_users: list[UserResponse]
    recent_activity: list[AdminRecentActivityItem]


class AdminGroupResponse(BaseModel):
    id: str
    name: str
    invite_code: str
    created_at: datetime

    model_config = {"from_attributes": True}


class AdminGroupCreateRequest(BaseModel):
    name: str = Field(min_length=1, max_length=60)


class AdminUserCreateRequest(BaseModel):
    username: str
    password: str = Field(min_length=8)
    display_name: str = Field(min_length=1, max_length=60)
    group_id: str | None = None
    is_admin: bool = False

    @field_validator("username")
    @classmethod
    def validate_username(cls, v: str) -> str:
        if not USERNAME_PATTERN.match(v):
            raise ValueError("El usuario debe tener 3-30 caracteres: letras, números, punto o guion bajo")
        return v.lower()


class AdminUserResponse(BaseModel):
    id: str
    username: str
    display_name: str
    avatar_url: str | None
    group_id: str | None
    is_admin: bool
    battery_level: int | None
    wifi_connected: bool | None
    created_at: datetime

    model_config = {"from_attributes": True}


class AdminUserUpdateRequest(BaseModel):
    display_name: str | None = Field(default=None, min_length=1, max_length=60)
    is_admin: bool | None = None


class AdminZoneResponse(BaseModel):
    id: str
    group_id: str
    name: str
    lat: float
    lng: float
    radius_m: float

    model_config = {"from_attributes": True}


class AdminZoneUpdateRequest(BaseModel):
    name: str = Field(min_length=1, max_length=60)
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)
    radius_m: float = Field(gt=0, le=50000)


class AdminZoneCreateRequest(BaseModel):
    group_id: str
    name: str = Field(min_length=1, max_length=60)
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)
    radius_m: float = Field(gt=0, le=50000)


class AdminHistoryPoint(BaseModel):
    lat: float
    lng: float
    timestamp: datetime

    model_config = {"from_attributes": True}


class AdminDiskUsageResponse(BaseModel):
    database_bytes: int
    avatars_bytes: int
    attachments_bytes: int
    apk_bytes: int
    web_static_bytes: int
    backups_bytes: int
    total_bytes: int


class AdminBackupResponse(BaseModel):
    id: str
    description: str
    created_at: datetime
    size_bytes: int


class AdminBackupCreateRequest(BaseModel):
    description: str = Field(default="", max_length=200)
