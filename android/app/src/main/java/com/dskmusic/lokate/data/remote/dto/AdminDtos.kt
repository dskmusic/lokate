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

data class AdminDiskUsageDto(
    val database_bytes: Long,
    val avatars_bytes: Long,
    val attachments_bytes: Long,
    val apk_bytes: Long,
    val web_static_bytes: Long,
    val backups_bytes: Long,
    val total_bytes: Long,
)

data class AdminBackupDto(
    val id: String,
    val description: String,
    val created_at: String,
    val size_bytes: Long,
)

data class AdminBackupCreateRequestDto(val description: String)
