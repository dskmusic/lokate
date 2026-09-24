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
    # Grupos en los que se esconde (solo lo usa su propia app, para pintar las casillas).
    hidden_groups: list[str] = []

    model_config = {"from_attributes": True}


# ---- Groups ----
class GroupCreateRequest(BaseModel):
    name: str = Field(min_length=1, max_length=60)


class GroupJoinRequest(BaseModel):
    invite_code: str


class GroupSwitchRequest(BaseModel):
    group_id: str


class GroupVisibilityRequest(BaseModel):
    group_id: str
    visible: bool


class GroupVisibilityResponse(BaseModel):
    hidden_groups: list[str]


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


class TestNotificationRequest(BaseModel):
    """user_ids vacío/ausente = todo el grupo (así los APK antiguos, que mandan POST sin cuerpo, siguen funcionando)."""

    user_ids: list[str] | None = None


# ---- Locations ----
class LocationPingRequest(BaseModel):
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)
    accuracy: float | None = None
    battery_level: int | None = Field(default=None, ge=0, le=100)
    is_charging: bool | None = None
    wifi_connected: bool | None = None
    wifi_ssid: str | None = None
    location_frequency: str | None = None
    config_issues: str | None = None
    # Ver models.User.update_mode. Texto libre a proposito: si una version futura del movil
    # manda un modo nuevo, el servidor lo guarda igual y ya lo pintara quien sepa leerlo.
    update_mode: str | None = None


class QueuedPing(BaseModel):
    """Un ping que el móvil no pudo entregar en su momento: viaja con SU hora."""

    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)
    accuracy: float | None = None
    timestamp: datetime


class LocationPingBatchRequest(BaseModel):
    """Vaciado de la cola del móvil. El estado (batería, wifi, modo) es el de AHORA y no el
    de cada punto: es lo que los demás ven en la ficha, y la batería de hace dos horas no le
    sirve a nadie. El tope de 300 es el mismo que aplica el móvil a su cola."""

    pings: list[QueuedPing] = Field(min_length=1, max_length=300)
    battery_level: int | None = Field(default=None, ge=0, le=100)
    is_charging: bool | None = None
    wifi_connected: bool | None = None
    wifi_ssid: str | None = None
    location_frequency: str | None = None
    config_issues: str | None = None
    update_mode: str | None = None


class LocationPingResponse(BaseModel):
    """Lo unico que el movil necesita saber de vuelta: si alguien lo tiene en seguimiento en
    vivo y cuantos segundos le quedan. Viaja en la respuesta del ping y no por push a proposito
    — asi el movil se entera de que el seguimiento ha terminado (o se ha renovado) sin depender
    de que llegue ningun mensaje: si nadie renueva, el contador se agota solo."""

    live_seconds: int = 0


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
    location_frequency: str | None
    config_issues: str | None
    update_mode: str | None = None
    # Segundos que le quedan a ese miembro de seguimiento en vivo (0 = ninguno). Mientras sea
    # >0 su movil esta en tiempo real, mande lo que mande location_frequency.
    live_seconds: int = 0

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
    # Por defecto publica: es lo que hacian todas las zonas antes de que esto existiera, y lo
    # que espera quien no se fija en el selector.
    is_public: bool = True


class ZoneResponse(BaseModel):
    id: str
    name: str
    lat: float
    lng: float
    radius_m: float
    is_public: bool = True
    # Quien la creo: la app lo usa para marcar "solo tuya" y para saber que puede editarla.
    created_by: str | None = None
    # Para poder ordenar las zonas por "reciente" en la app.
    created_at: datetime | None = None

    model_config = {"from_attributes": True}


# ---- Device ----
class RegisterDeviceRequest(BaseModel):
    fcm_token: str
    # Version instalada (ver models.User.app_version). Solo se guarda: los avisos al admin los
    # manda la app a proposito (ver /auth/update-notice/status), asi llegan aunque se reinstale
    # la misma version encima.
    app_version: str | None = None
    location_frequency: str | None = None
    config_issues: str | None = None
    # Canal de notificación de zona de ese móvil (ver models.User.zone_channel_id).
    zone_channel_id: str | None = None
    # Estado del dispositivo: /auth/device hace también de latido para quien tiene el envío de
    # ubicación desactivado — sin pings, es la única vía por la que el grupo ve su batería.
    battery_level: int | None = Field(default=None, ge=0, le=100)
    is_charging: bool | None = None
    wifi_connected: bool | None = None
    wifi_ssid: str | None = None


class AvatarResponse(BaseModel):
    avatar_url: str


# ---- Preferencias de notificación por zona ----
class ZoneNotificationPrefResponse(BaseModel):
    zone_id: str
    notify_on_enter: bool
    notify_on_exit: bool
    # De quién quiere que le avisen ESTE usuario en esta zona. Vacía = de todo el grupo.
    watched_user_ids: list[str] = []

    model_config = {"from_attributes": True}


class ZoneNotificationPrefUpdateRequest(BaseModel):
    notify_on_enter: bool
    notify_on_exit: bool
    watched_user_ids: list[str] = []


# ---- Copia de ajustes en la nube ----
class BackupUploadRequest(BaseModel):
    # 512 KB de tope: los ajustes de un movil ocupan unos pocos KB, y asi nadie usa esto de
    # almacen de lo que le apetezca.
    payload: str = Field(min_length=2, max_length=512 * 1024)
    app_version: str | None = None


class BackupResponse(BaseModel):
    """exists=False cuando el usuario no tiene copia todavia. Se contesta 200 con exists=False en
    vez de 404 para que el cliente no tenga que tratar un error para el caso normal de 'aun no
    hay copia'."""

    exists: bool
    updated_at: datetime | None = None
    app_version: str | None = None
    payload: str | None = None


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


class AdminKnownWifiRequest(BaseModel):
    """La wifi que un admin quiere anadir a la lista de "wifis de casa" de un usuario."""

    # 32 bytes es el maximo de un SSID; 64 por si viene escapado.
    ssid: str = Field(min_length=1, max_length=64)


class BatteryReport(BaseModel):
    """Lo que un movil cuenta de si mismo cuando un admin le pide el informe de bateria.

    Lo hace y lo mide la propia app (ver BatteryStats.kt en Android): el desglose por app que
    enseña Android es privilegiado y ninguna app puede leerlo, ni el suyo. Aqui el servidor no
    interpreta nada — solo valida que sea del tamano y del tipo que dice ser, y lo guarda.

    Todos los campos con valor por defecto a proposito: una version de la app mas antigua o mas
    nueva que la del servidor sigue pudiendo subir el suyo sin que esto reviente.
    """

    generated_at: int = 0
    app_version: str | None = Field(default=None, max_length=40)
    device: str | None = Field(default=None, max_length=160)
    period_start: int = 0
    period_ms: int = 0
    period_from_charge: bool = False
    battery_start_pct: int = -1
    battery_now_pct: int = -1
    is_charging: bool = False
    temperature_c: float = -1
    live_ms: int = 0
    move_ms: int = 0
    idle_ms: int = 0
    off_ms: int = 0
    gps_high_ms: int = 0
    gps_balanced_ms: int = 0
    fixes_ok: int = 0
    fixes_dropped: int = 0
    pings_ok: int = 0
    pings_failed: int = 0
    one_shots: int = 0
    live_sessions: int = 0
    worker_runs: int = 0
    geofence_events: int = 0
    geofence_registers: int = 0
    pushes: int = 0
    frequency: str | None = Field(default=None, max_length=30)
    mode: str | None = Field(default=None, max_length=20)
    last_tick_at: int = 0
    service_running: bool = False
    standby_bucket: int = -1
    ignoring_battery_optimizations: bool = False
    power_save: bool = False
    device_idle: bool = False
    config_issues: str | None = Field(default=None, max_length=200)
    exit_count: int = 0
    last_exit_at: int = 0
    last_exit_reason: int = 0
    last_exit_description: str | None = Field(default=None, max_length=200)


class AdminBatteryReportResponse(BaseModel):
    """known=False: ese movil no ha subido ningun informe todavia (esta apagado, no le ha dado
    tiempo a contestar o su app es anterior a esto)."""

    known: bool
    received_at: datetime | None = None
    report: BatteryReport | None = None


class AdminKnownWifiResponse(BaseModel):
    """known=False: ese usuario no tiene copia en la nube, asi que el servidor no ha visto nunca
    su lista de wifis. updated_at es de cuando se hizo esa copia, no de ahora mismo."""

    known: bool
    updated_at: datetime | None = None
    ssids: list[str] = []


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


class AdminIdsRequest(BaseModel):
    """Una lista de ids para borrar de golpe. Uno solo es una lista de uno: así el borrado de
    una fila y el de una selección entera son el mismo endpoint."""

    ids: list[str]


class AdminDeletedResponse(BaseModel):
    deleted: int


class UpdateNoticeStatusRequest(BaseModel):
    """Que ha hecho el usuario con el aviso de "actualiza la app"."""

    # "started" (ha pulsado actualizar y el APK ya esta descargado), "installed" (ha vuelto a
    # abrir la app despues) o "dismissed" (lo ha barrido sin hacer nada).
    stage: str
    app_version: str | None = None


class AdminUpdateNoticeRequest(BaseModel):
    """Aviso de "actualiza la app". Destinatarios, del más concreto al más amplio: si vienen
    user_ids se usan esos; si no, el grupo; si no hay nada, TODOS los usuarios de todos los
    grupos (incluidos los que no tienen grupo)."""

    message: str | None = None
    group_id: str | None = None
    user_ids: list[str] | None = None


class AdminUpdateNoticeResponse(BaseModel):
    """[sent] son los que tienen token FCM y por tanto pueden recibirlo; [without_token] los que
    no lo tienen (nunca han abierto esta versión, o cerraron sesión) y no se van a enterar."""

    sent: int
    without_token: int


class AdminUpdateNoticeStateResponse(BaseModel):
    """Como va el ultimo aviso de actualizacion de una persona. [status]: "sent" (mandado y
    sin noticias), "started", "installed" o "dismissed"; [status_at] es cuando llego esa
    respuesta, null mientras no haya ninguna."""

    user_id: str
    user_name: str
    group_name: str | None = None
    sent_at: datetime
    status: str
    status_at: datetime | None = None
    app_version: str | None = None


class AdminZoneEventResponse(BaseModel):
    """Una entrada o salida ya decidida por el servidor, tal y como la enseña el registro del
    panel. Los nombres vienen resueltos (no los ids): esto se lee, no se cruza con nada."""

    id: str
    at: datetime
    user_id: str
    user_name: str
    zone_id: str
    zone_name: str
    entered: bool
    notified: int
    # NULL = aviso normal. Resto de códigos en models.ZoneEvent.reason; los traduce la app.
    reason: str | None = None
    distance_m: float | None = None
    accuracy: float | None = None
    lat: float
    lng: float


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


class AdminFileResponse(BaseModel):
    name: str
    size_bytes: int
    modified: datetime
    url: str
    #: image / video / audio / other — decide si la app lo previsualiza y con qué
    kind: str
    #: solo los avatares pueden estar referenciados en la BD; los adjuntos viajan en el push y
    #: nadie los apunta, así que ahí siempre es False
    in_use: bool

    # managed_files devuelve dataclasses, no dicts
    model_config = {"from_attributes": True}


class AdminDeleteAllResponse(BaseModel):
    deleted: int


class AdminBackupResponse(BaseModel):
    id: str
    description: str
    created_at: datetime
    size_bytes: int


class AdminBackupCreateRequest(BaseModel):
    description: str = Field(default="", max_length=200)


class AdminSimulateRequest(BaseModel):
    """Posición falsa a la que un admin arrastra a un miembro en el modo prueba."""

    lat: float
    lng: float
    # A quién le llega el aviso si el arrastre cruza el borde de una zona. El admin lo elige a
    # mano en la app (y se recuerda ahí), en vez de respetar las preferencias por zona de cada
    # uno: es una prueba, quien la hace decide en qué móvil quiere verla sonar.
    recipient_ids: list[str] = []


class AdminSimulateResponse(BaseModel):
    # Textos de los avisos disparados por este arrastre ("Luci ha salido de Casa"); vacío = el
    # arrastre no ha cruzado ningún borde.
    transitions: list[str]
    notified: int
