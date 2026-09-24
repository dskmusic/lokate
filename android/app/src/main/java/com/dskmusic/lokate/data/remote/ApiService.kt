package com.dskmusic.lokate.data.remote

import com.dskmusic.lokate.data.remote.dto.AdminBackupCreateRequestDto
import com.dskmusic.lokate.data.remote.dto.AdminBatteryReportDto
import com.dskmusic.lokate.data.remote.dto.AdminBackupDto
import com.dskmusic.lokate.data.remote.dto.AdminDashboardDto
import com.dskmusic.lokate.data.remote.dto.AdminDeleteAllDto
import com.dskmusic.lokate.data.remote.dto.AdminDiskUsageDto
import com.dskmusic.lokate.data.remote.dto.AdminFileDto
import com.dskmusic.lokate.data.remote.dto.AdminGroupCreateRequestDto
import com.dskmusic.lokate.data.remote.dto.AdminGroupDto
import com.dskmusic.lokate.data.remote.dto.AdminKnownWifiDto
import com.dskmusic.lokate.data.remote.dto.AdminKnownWifiRequestDto
import com.dskmusic.lokate.data.remote.dto.AdminSimulateDto
import com.dskmusic.lokate.data.remote.dto.AdminSimulateRequestDto
import com.dskmusic.lokate.data.remote.dto.AdminUserCreateRequestDto
import com.dskmusic.lokate.data.remote.dto.AdminUserDto
import com.dskmusic.lokate.data.remote.dto.AdminUserUpdateRequestDto
import com.dskmusic.lokate.data.remote.dto.AdminZoneCreateRequestDto
import com.dskmusic.lokate.data.remote.dto.AdminZoneDto
import com.dskmusic.lokate.data.remote.dto.AdminDeletedDto
import com.dskmusic.lokate.data.remote.dto.AdminIdsRequestDto
import com.dskmusic.lokate.data.remote.dto.AdminUpdateNoticeDto
import com.dskmusic.lokate.data.remote.dto.AdminUpdateNoticeResultDto
import com.dskmusic.lokate.data.remote.dto.AdminUpdateNoticeStateDto
import com.dskmusic.lokate.data.remote.dto.AdminZoneEventDto
import com.dskmusic.lokate.data.remote.dto.AdminZoneUpdateRequestDto
import com.dskmusic.lokate.data.remote.dto.AvatarResponseDto
import com.dskmusic.lokate.data.remote.dto.BackupDto
import com.dskmusic.lokate.data.remote.dto.BackupUploadRequestDto
import com.dskmusic.lokate.data.remote.dto.BatteryReportDto
import com.dskmusic.lokate.data.remote.dto.GroupCreateRequestDto
import com.dskmusic.lokate.data.remote.dto.GroupDto
import com.dskmusic.lokate.data.remote.dto.GroupJoinRequestDto
import com.dskmusic.lokate.data.remote.dto.GroupSwitchRequestDto
import com.dskmusic.lokate.data.remote.dto.GroupVisibilityDto
import com.dskmusic.lokate.data.remote.dto.GroupVisibilityRequestDto
import com.dskmusic.lokate.data.remote.dto.GroupMemberDto
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.data.remote.dto.LocationHistoryPointDto
import com.dskmusic.lokate.data.remote.dto.LocationPingBatchRequestDto
import com.dskmusic.lokate.data.remote.dto.LocationPingRequestDto
import com.dskmusic.lokate.data.remote.dto.LocationPingResponseDto
import com.dskmusic.lokate.data.remote.dto.LoginRequestDto
import com.dskmusic.lokate.data.remote.dto.RegisterDeviceRequestDto
import com.dskmusic.lokate.data.remote.dto.RegisterRequestDto
import com.dskmusic.lokate.data.remote.dto.TestNotificationRequestDto
import com.dskmusic.lokate.data.remote.dto.TokenResponseDto
import com.dskmusic.lokate.data.remote.dto.UpdateCheckDto
import com.dskmusic.lokate.data.remote.dto.UpdateFlagRequestDto
import com.dskmusic.lokate.data.remote.dto.UpdateNoticeStatusDto
import com.dskmusic.lokate.data.remote.dto.UpdateProfileRequestDto
import com.dskmusic.lokate.data.remote.dto.UserDto
import com.dskmusic.lokate.data.remote.dto.ZoneCreateRequestDto
import com.dskmusic.lokate.data.remote.dto.ZoneDto
import com.dskmusic.lokate.data.remote.dto.ZoneNotificationPrefDto
import com.dskmusic.lokate.data.remote.dto.ZoneNotificationPrefUpdateRequestDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface ApiService {

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequestDto): TokenResponseDto

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequestDto): TokenResponseDto

    @GET("auth/me")
    suspend fun me(): UserDto

    @PUT("auth/me")
    suspend fun updateProfile(@Body body: UpdateProfileRequestDto): UserDto

    @POST("auth/device")
    suspend fun registerDevice(@Body body: RegisterDeviceRequestDto)

    @Multipart
    @POST("auth/avatar")
    suspend fun uploadAvatar(@Part file: MultipartBody.Part): AvatarResponseDto

    @POST("groups")
    suspend fun createGroup(@Body body: GroupCreateRequestDto): GroupDto

    @POST("groups/join")
    suspend fun joinGroup(@Body body: GroupJoinRequestDto): GroupDto

    @POST("groups/switch")
    suspend fun switchGroup(@Body body: GroupSwitchRequestDto): GroupDto

    @POST("groups/visibility")
    suspend fun setGroupVisibility(@Body body: GroupVisibilityRequestDto): GroupVisibilityDto

    @GET("groups/me")
    suspend fun myGroup(): GroupDto

    @GET("groups/me/members")
    suspend fun groupMembers(): List<GroupMemberDto>

    @POST("groups/leave")
    suspend fun leaveGroup()

    @POST("groups/test-notification")
    suspend fun sendTestNotification(@Body body: TestNotificationRequestDto)

    @POST("location/ping")
    suspend fun ping(@Body body: LocationPingRequestDto): LocationPingResponseDto

    @POST("location/pings")
    suspend fun pingBatch(@Body body: LocationPingBatchRequestDto): LocationPingResponseDto

    @GET("location/group/latest")
    suspend fun groupLatestLocations(): List<LocationDto>

    @GET("location/history")
    suspend fun locationHistory(
        @Query("hours") hours: Int = 24,
        @Query("user_id") userId: String? = null,
        @Query("from_ts") fromTs: String? = null,
        @Query("to_ts") toTs: String? = null,
    ): List<LocationHistoryPointDto>

    @POST("location/ring/{userId}")
    suspend fun ringDevice(@Path("userId") userId: String)

    @POST("location/stop-ring/{userId}")
    suspend fun stopRing(@Path("userId") userId: String)

    @POST("location/request-location/{userId}")
    suspend fun requestLocation(@Path("userId") userId: String)

    /** Este móvil sube su propio informe de batería, cuando un admin se lo ha pedido por push. */
    @POST("location/battery-report")
    suspend fun uploadBatteryReport(@Body body: BatteryReportDto)

    /** Pone (o quita) a otro miembro en tiempo real mientras lo sigamos en el mapa. */
    @POST("location/live/{userId}")
    suspend fun setLiveTracking(@Path("userId") userId: String, @Query("active") active: Boolean)

    @GET("backup")
    suspend fun getBackup(): BackupDto

    @PUT("backup")
    suspend fun putBackup(@Body body: BackupUploadRequestDto): BackupDto

    @GET("zones")
    suspend fun listZones(): List<ZoneDto>

    @POST("zones")
    suspend fun createZone(@Body body: ZoneCreateRequestDto): ZoneDto

    @PUT("zones/{id}")
    suspend fun updateZone(@Path("id") id: String, @Body body: ZoneCreateRequestDto): ZoneDto

    @DELETE("zones/{id}")
    suspend fun deleteZone(@Path("id") id: String)

    @GET("zones/notification-prefs")
    suspend fun zoneNotificationPrefs(): List<ZoneNotificationPrefDto>

    @PUT("zones/{id}/notification-prefs")
    suspend fun updateZoneNotificationPref(
        @Path("id") zoneId: String,
        @Body body: ZoneNotificationPrefUpdateRequestDto,
    ): ZoneNotificationPrefDto

    @GET("update-check")
    suspend fun updateCheck(): UpdateCheckDto

    @POST("update-flag")
    suspend fun setUpdateFlag(@Body body: UpdateFlagRequestDto): UpdateCheckDto

    @Multipart
    @POST("messages/emergency/{userId}")
    suspend fun sendEmergencyMessage(
        @Path("userId") userId: String,
        @Part("text") text: RequestBody,
        @Part file: MultipartBody.Part?,
    )

    // ---- Panel de administración nativo (solo admins, ver admin_api.py) ----

    @GET("admin-api/disk-usage")
    suspend fun adminDiskUsage(): AdminDiskUsageDto

    @GET("admin-api/dashboard")
    suspend fun adminDashboard(): AdminDashboardDto

    @GET("admin-api/groups")
    suspend fun adminListGroups(): List<AdminGroupDto>

    @POST("admin-api/groups")
    suspend fun adminCreateGroup(@Body body: AdminGroupCreateRequestDto): AdminGroupDto

    @DELETE("admin-api/groups/{id}")
    suspend fun adminDeleteGroup(@Path("id") id: String)

    @GET("admin-api/users")
    suspend fun adminListUsers(): List<AdminUserDto>

    @POST("admin-api/users")
    suspend fun adminCreateUser(@Body body: AdminUserCreateRequestDto): AdminUserDto

    @PUT("admin-api/users/{id}")
    suspend fun adminUpdateUser(@Path("id") id: String, @Body body: AdminUserUpdateRequestDto): AdminUserDto

    @DELETE("admin-api/users/{id}")
    suspend fun adminDeleteUser(@Path("id") id: String)

    @POST("admin-api/users/{id}/notify-test")
    suspend fun adminNotifyTest(@Path("id") id: String)

    @POST("admin-api/users/{id}/locate")
    suspend fun adminLocateUser(@Path("id") id: String)

    /** Le pide por push al móvil de ese usuario que haga y suba su informe de batería. */
    @POST("admin-api/users/{id}/battery-report")
    suspend fun adminRequestBatteryReport(@Path("id") id: String)

    /** Y el último que subió, que es lo que se sondea mientras se espera al de ahora. */
    @GET("admin-api/users/{id}/battery-report")
    suspend fun adminBatteryReport(@Path("id") id: String): AdminBatteryReportDto

    @GET("admin-api/users/{id}/known-wifi")
    suspend fun adminKnownWifi(@Path("id") id: String): AdminKnownWifiDto

    @POST("admin-api/users/{id}/known-wifi")
    suspend fun adminAddKnownWifi(@Path("id") id: String, @Body body: AdminKnownWifiRequestDto)

    @Multipart
    @POST("admin-api/users/{id}/avatar")
    suspend fun adminUploadAvatar(@Path("id") id: String, @Part file: MultipartBody.Part): AvatarResponseDto

    @GET("admin-api/zones")
    suspend fun adminListZones(): List<AdminZoneDto>

    @POST("admin-api/zones")
    suspend fun adminCreateZone(@Body body: AdminZoneCreateRequestDto): AdminZoneDto

    @PUT("admin-api/zones/{id}")
    suspend fun adminUpdateZone(@Path("id") id: String, @Body body: AdminZoneUpdateRequestDto): AdminZoneDto

    @DELETE("admin-api/zones/{id}")
    suspend fun adminDeleteZone(@Path("id") id: String)

    /** Registro de entradas y salidas de zona. Todos los filtros son opcionales; el servidor
     * limita el tamaño (ver admin_api.list_zone_events). */
    @GET("admin-api/zone-events")
    suspend fun adminZoneEvents(
        @Query("user_id") userId: String? = null,
        @Query("zone_id") zoneId: String? = null,
        @Query("days") days: Int = 7,
        @Query("only_missed") onlyMissed: Boolean = false,
    ): List<AdminZoneEventDto>

    /** Borra entradas del registro: una o las que se hayan marcado, misma llamada. */
    @POST("admin-api/zone-events/delete")
    suspend fun adminDeleteZoneEvents(@Body body: AdminIdsRequestDto): AdminDeletedDto

    /** Aviso de "actualiza la app" a todos, a un grupo o a personas sueltas. */
    @POST("admin-api/notify-update")
    suspend fun adminNotifyUpdate(@Body body: AdminUpdateNoticeDto): AdminUpdateNoticeResultDto

    /** Como quedo el ultimo aviso de actualizacion de cada uno, el mas reciente primero. */
    @GET("admin-api/update-notice/status")
    suspend fun adminUpdateNoticeStates(): List<AdminUpdateNoticeStateDto>

    /** Que ha hecho el usuario con el aviso de actualizacion: los admins se enteran. */
    @POST("auth/update-notice/status")
    suspend fun updateNoticeStatus(@Body body: UpdateNoticeStatusDto)

    @GET("admin-api/files/{folder}")
    suspend fun adminListFiles(@Path("folder") folder: String): List<AdminFileDto>

    @DELETE("admin-api/files/{folder}/{name}")
    suspend fun adminDeleteFile(@Path("folder") folder: String, @Path("name") name: String)

    @DELETE("admin-api/files/{folder}")
    suspend fun adminDeleteAllFiles(@Path("folder") folder: String): AdminDeleteAllDto

    @GET("admin-api/backups")
    suspend fun adminListBackups(): List<AdminBackupDto>

    @POST("admin-api/backups")
    suspend fun adminCreateBackup(@Body body: AdminBackupCreateRequestDto): AdminBackupDto

    @Streaming
    @GET("admin-api/backups/{id}/download")
    suspend fun adminDownloadBackup(@Path("id") id: String): ResponseBody

    @POST("admin-api/backups/{id}/restore")
    suspend fun adminRestoreBackup(@Path("id") id: String)

    @DELETE("admin-api/backups/{id}")
    suspend fun adminDeleteBackup(@Path("id") id: String)

    @POST("admin-api/simulate/{id}")
    suspend fun adminSimulatePosition(@Path("id") id: String, @Body body: AdminSimulateRequestDto): AdminSimulateDto

    @POST("admin-api/simulate/stop")
    suspend fun adminStopSimulation()
}
