package com.dskmusic.lokate.data.remote.dto

/** DTOs del panel de administración nativo (endpoints admin-api, solo admins) — ver
 * backend/app/routers/admin_api.py. */

data class AdminDashboardStatsDto(
    val groups: Int,
    val users: Int,
    val admins: Int,
    val online_now: Int,
    val pings_24h: Int,
    val active_users_24h: Int,
    val devices_with_push: Int,
)

data class AdminActivityDayDto(val label: String, val count: Int, val pct: Int)

data class AdminRecentActivityItemDto(
    val user_id: String,
    val display_name: String,
    val avatar_url: String?,
    val timestamp: String,
)

data class AdminDashboardDto(
    val stats: AdminDashboardStatsDto,
    val activity_series: List<AdminActivityDayDto>,
    val recent_users: List<UserDto>,
    val recent_activity: List<AdminRecentActivityItemDto>,
)

data class AdminGroupDto(
    val id: String,
    val name: String,
    val invite_code: String,
    val created_at: String,
)

data class AdminGroupCreateRequestDto(val name: String)

data class AdminUserDto(
    val id: String,
    val username: String,
    val display_name: String,
    val avatar_url: String?,
    val group_id: String?,
    val is_admin: Boolean,
    val battery_level: Int?,
    val wifi_connected: Boolean?,
    val created_at: String,
)

data class AdminUserUpdateRequestDto(val display_name: String?, val is_admin: Boolean?)

/** Wifi que un admin añade a la lista de "wifis de casa" de un usuario desde su ficha. */
data class AdminKnownWifiRequestDto(val ssid: String)

/** Las wifis de casa que el servidor le conoce a un usuario (de su copia en la nube).
 * known=false: ese usuario no tiene copia todavía, asi que no hay forma de saberlo. */
data class AdminKnownWifiDto(
    val known: Boolean = false,
    val updated_at: String? = null,
    val ssids: List<String> = emptyList(),
)

data class AdminUserCreateRequestDto(
    val username: String,
    val password: String,
    val display_name: String,
    val group_id: String?,
    val is_admin: Boolean,
)

data class AdminZoneDto(
    val id: String,
    val group_id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius_m: Double,
)

data class AdminZoneUpdateRequestDto(val name: String, val lat: Double, val lng: Double, val radius_m: Double)

data class AdminZoneCreateRequestDto(
    val group_id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius_m: Double,
)

/** Una entrada o salida de zona ya decidida por el servidor (ver models.ZoneEvent). [notified]
 * = a cuánta gente se le mandó el aviso; 0 con [reason] explica por qué no se mandó a nadie. */
data class AdminZoneEventDto(
    val id: String,
    val at: String,
    val user_id: String,
    val user_name: String,
    val zone_id: String,
    val zone_name: String,
    val entered: Boolean,
    val notified: Int,
    /** null = aviso normal. Resto: no_prefs, hidden, private_zone, test, resync. */
    val reason: String? = null,
    val distance_m: Double? = null,
    val accuracy: Double? = null,
    val lat: Double,
    val lng: Double,
)

/** Lista de ids para borrar de golpe; una fila suelta es una lista de uno. */
data class AdminIdsRequestDto(val ids: List<String>)

data class AdminDeletedDto(val deleted: Int)

/** Aviso de "actualiza la app". Destinatarios del mas concreto al mas amplio: [user_ids] manda
 * sobre [group_id], y sin ninguno de los dos va a todo el mundo. */
data class AdminUpdateNoticeDto(
    val message: String? = null,
    val group_id: String? = null,
    val user_ids: List<String>? = null,
)

/** [sent] = a cuantos se les ha podido mandar; [without_token] = los que no tienen token FCM y
 * no se van a enterar (nunca han abierto esta version, o cerraron sesion). */
data class AdminUpdateNoticeResultDto(val sent: Int, val without_token: Int)

/** Como quedo el ultimo aviso de actualizacion de una persona. [status]: "sent" (mandado, sin
 * respuesta), "started", "installed" o "dismissed"; [status_at] null mientras no conteste. */
data class AdminUpdateNoticeStateDto(
    val user_id: String,
    val user_name: String,
    val group_name: String? = null,
    val sent_at: String,
    val status: String,
    val status_at: String? = null,
    val app_version: String? = null,
)

data class AdminDiskUsageDto(
    val database_bytes: Long,
    val avatars_bytes: Long,
    val attachments_bytes: Long,
    val apk_bytes: Long,
    val web_static_bytes: Long,
    val backups_bytes: Long,
    val total_bytes: Long,
)

/** Un archivo borrable de /data (avatar o adjunto). [kind]: image/video/audio/other — decide
 * cómo lo previsualiza la app. [in_use]: solo los avatares pueden estar en uso. */
data class AdminFileDto(
    val name: String,
    val size_bytes: Long,
    val modified: String,
    val url: String,
    val kind: String,
    val in_use: Boolean,
)

data class AdminDeleteAllDto(val deleted: Int)

data class AdminBackupDto(
    val id: String,
    val description: String,
    val created_at: String,
    val size_bytes: Long,
)

data class AdminBackupCreateRequestDto(val description: String)

/** Modo prueba: posición falsa a la que el admin arrastra a un miembro, y a quién avisar si
 * ese arrastre cruza el borde de una zona (ver admin_api.simulate_position). */
data class AdminSimulateRequestDto(
    val lat: Double,
    val lng: Double,
    val recipient_ids: List<String>,
)

data class AdminSimulateDto(
    /** Textos de los avisos disparados; vacío = el arrastre no ha cruzado ninguna zona. */
    val transitions: List<String>,
    val notified: Int,
)

/** El último informe de batería que subió el móvil de ese usuario. known=false: todavía no ha
 * subido ninguno (su app es anterior, está apagado o no le ha dado tiempo a contestar). */
data class AdminBatteryReportDto(
    val known: Boolean = false,
    val received_at: String? = null,
    val report: BatteryReportDto? = null,
)
