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

data class RegisterDeviceRequestDto(
    val fcm_token: String,
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
