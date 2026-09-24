package com.dskmusic.lokate.data.remote.dto

data class RegisterRequestDto(
    val username: String,
    val password: String,
    val display_name: String,
)

data class LoginRequestDto(
    val username: String,
    val password: String,
)

data class TokenResponseDto(
    val access_token: String,
    val token_type: String,
    val user: UserDto,
)

data class UserDto(
    val id: String,
    val username: String,
    val display_name: String,
    val avatar_url: String?,
    val group_id: String?,
    val is_admin: Boolean,
    /** Grupos en los que este usuario se esconde (solo admin). */
    val hidden_groups: List<String> = emptyList(),
)

data class AvatarResponseDto(
    val avatar_url: String,
)

data class GroupCreateRequestDto(val name: String)
data class GroupJoinRequestDto(val invite_code: String)

data class GroupSwitchRequestDto(val group_id: String)

data class GroupVisibilityRequestDto(val group_id: String, val visible: Boolean)

data class GroupVisibilityDto(val hidden_groups: List<String>)

data class GroupDto(
    val id: String,
    val name: String,
    val invite_code: String,
)

data class GroupMemberDto(
    val id: String,
    val display_name: String,
    val username: String,
    val avatar_url: String?,
)

/** null = a todo el grupo. */
data class TestNotificationRequestDto(val user_ids: List<String>?)

/** Un ping que se quedo en la cola del movil: viaja con la hora de cuando se tomo. */
data class QueuedPingDto(
    val lat: Double,
    val lng: Double,
    val accuracy: Float?,
    /** ISO-8601 UTC ("2026-09-22T19:03:11Z"). */
    val timestamp: String,
)

/** Vaciado de la cola: todos los pings pendientes en UNA peticion. El estado del movil
 * (bateria, wifi...) es el de AHORA, no el de cada punto: es lo que los demas ven en la
 * ficha, y de nada sirve enterarse de la bateria que habia hace dos horas. */
data class LocationPingBatchRequestDto(
    val pings: List<QueuedPingDto>,
    val battery_level: Int?,
    val is_charging: Boolean?,
    val wifi_connected: Boolean?,
    val wifi_ssid: String?,
    val location_frequency: String?,
    val config_issues: String?,
    val update_mode: String?,
)

data class LocationPingRequestDto(
    val lat: Double,
    val lng: Double,
    val accuracy: Float?,
    val battery_level: Int?,
    val is_charging: Boolean?,
    val wifi_connected: Boolean?,
    val wifi_ssid: String?,
    val location_frequency: String?,
    val config_issues: String?,
    /** Cómo está mandando posición este móvil ahora mismo. Ver
     * [com.dskmusic.lokate.location.UpdateMode]. */
    val update_mode: String?,
)

/** Lo único que el servidor contesta al ping: cuántos segundos quedan de seguimiento en vivo
 * (0 = nadie nos está siguiendo). Ver [com.dskmusic.lokate.location.LiveTracking]. */
data class LocationPingResponseDto(
    val live_seconds: Int = 0,
)

data class LocationDto(
    val user_id: String,
    val display_name: String,
    val avatar_url: String?,
    val lat: Double,
    val lng: Double,
    val accuracy: Float?,
    val timestamp: String,
    val battery_level: Int?,
    val is_charging: Boolean?,
    val wifi_connected: Boolean?,
    val wifi_ssid: String?,
    /** Nombre del enum [com.dskmusic.lokate.util.LocationFrequency] que ese miembro tiene
     * elegido; null si su app aún no ha mandado ningún ping con esta versión. */
    val location_frequency: String?,
    /** Códigos de [com.dskmusic.lokate.util.ConfigCheck] separados por comas con lo que ese
     * miembro tiene sin configurar. Cadena vacía = todo correcto; null = su app es anterior a
     * esta versión y no lo manda (se muestra como "desconocido"). */
    val config_issues: String?,
    /** Valor de [com.dskmusic.lokate.location.UpdateMode] con el que ese miembro está
     * mandando posición ahora mismo: es lo que explica que alguien con "cada minuto" lleve
     * doce sin actualizar (casi siempre, que no se está moviendo). null = su app es anterior
     * a esta versión y no lo manda. */
    val update_mode: String? = null,
    /** Segundos que le quedan a ese miembro de seguimiento en vivo (0 = ninguno). Mientras sea
     * >0 su móvil está en tiempo real, mande lo que mande [location_frequency]: es la
     * confirmación de que la orden de "seguir" prendió de verdad en el otro móvil. */
    val live_seconds: Int = 0,
)

data class LocationHistoryPointDto(
    val lat: Double,
    val lng: Double,
    val accuracy: Float?,
    val timestamp: String,
)

data class ZoneCreateRequestDto(
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius_m: Double,
    /** false = zona privada: solo la ve y solo avisa a quien la creó. */
    val is_public: Boolean = true,
)

data class ZoneDto(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius_m: Double,
    /** Con un servidor anterior a esto llega ausente, y Gson deja el valor por defecto: pública,
     * que es justo como se comportaban todas las zonas entonces. */
    val is_public: Boolean = true,
    val created_by: String? = null,
    // Puede faltar si el servidor es anterior a que se expusiera: solo se usa para ordenar.
    val created_at: String? = null,
)

/** Ajustes de avisos de una zona para ESTE usuario: son suyos, no los comparte con el grupo. */
data class ZoneNotificationPrefDto(
    val zone_id: String,
    val notify_on_enter: Boolean,
    val notify_on_exit: Boolean,
    /** De quién quiere que le avisen en esta zona. Vacía = de todo el grupo. */
    val watched_user_ids: List<String> = emptyList(),
)

data class ZoneNotificationPrefUpdateRequestDto(
    val notify_on_enter: Boolean,
    val notify_on_exit: Boolean,
    val watched_user_ids: List<String> = emptyList(),
)

data class UpdateProfileRequestDto(val display_name: String)

/** [stage]: "started" (ha pulsado actualizar), "installed" (ha vuelto a abrir la app despues)
 * o "dismissed" (ha barrido el aviso). La version va siempre: es lo unico que prueba de verdad
 * en cual se ha quedado. */
data class UpdateNoticeStatusDto(val stage: String, val app_version: String)

data class RegisterDeviceRequestDto(
    val fcm_token: String,
    /** La version instalada. El servidor solo la usa para avisar a los admins cuando cambia. */
    val app_version: String,
    val location_frequency: String,
    val config_issues: String,
    val zone_channel_id: String,
    val battery_level: Int?,
    val is_charging: Boolean?,
    val wifi_connected: Boolean?,
    val wifi_ssid: String?,
)

data class UpdateCheckDto(val update_available: Boolean)

data class UpdateFlagRequestDto(val enabled: Boolean)

/** La copia de ajustes guardada en el servidor. [payload] es el JSON que escribió la propia app
 * (ver SettingsDataStore.exportJson); el servidor no mira dentro. exists=false = aún no hay. */
data class BackupDto(
    val exists: Boolean = false,
    val updated_at: String? = null,
    val app_version: String? = null,
    val payload: String? = null,
)

data class BackupUploadRequestDto(val payload: String, val app_version: String?)

/**
 * El informe de batería que un móvil hace de sí mismo cuando un admin lo pide desde su ficha
 * (ver BatteryStats.kt, donde se explica qué se puede medir y qué no). Sube tal cual y baja tal
 * cual: el servidor solo lo guarda.
 *
 * Los tiempos en milisegundos y siempre desde la última carga. -1 en un número = ese móvil no
 * publica ese dato.
 */
data class BatteryReportDto(
    val generated_at: Long = 0L,
    val app_version: String? = null,
    val device: String? = null,
    val period_start: Long = 0L,
    val period_ms: Long = 0L,
    val period_from_charge: Boolean = false,
    val battery_start_pct: Int = -1,
    val battery_now_pct: Int = -1,
    val is_charging: Boolean = false,
    val temperature_c: Double = -1.0,
    /** Tiempo por modo de la app: seguimiento en vivo, moviéndose, en reposo y con el servicio
     * parado. Suman el tiempo MEDIDO, que puede ser menos que [period_ms] (lo que el proceso
     * pasó muerto no se le suma a nadie). */
    val live_ms: Long = 0L,
    val move_ms: Long = 0L,
    val idle_ms: Long = 0L,
    val off_ms: Long = 0L,
    /** Y lo mismo repartido por precisión pedida al sistema, que es lo que de verdad gasta. */
    val gps_high_ms: Long = 0L,
    val gps_balanced_ms: Long = 0L,
    val fixes_ok: Long = 0L,
    val fixes_dropped: Long = 0L,
    val pings_ok: Long = 0L,
    val pings_failed: Long = 0L,
    val one_shots: Long = 0L,
    val live_sessions: Long = 0L,
    val worker_runs: Long = 0L,
    val geofence_events: Long = 0L,
    val geofence_registers: Long = 0L,
    val pushes: Long = 0L,
    val frequency: String? = null,
    val mode: String? = null,
    val last_tick_at: Long = 0L,
    val service_running: Boolean = false,
    /** Cubo de reposo del sistema: 10 activo, 20 en uso, 30 frecuente, 40 raro, 45 restringido. */
    val standby_bucket: Int = -1,
    val ignoring_battery_optimizations: Boolean = false,
    val power_save: Boolean = false,
    val device_idle: Boolean = false,
    /** Códigos de ConfigCheck separados por comas, vacío = todo correcto. */
    val config_issues: String? = null,
    /** Veces que el sistema ha matado el proceso desde la última carga, y la última de ellas. */
    val exit_count: Int = 0,
    val last_exit_at: Long = 0L,
    val last_exit_reason: Int = 0,
    val last_exit_description: String? = null,
)
