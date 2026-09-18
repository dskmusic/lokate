package com.dskmusic.lokate.data.repository

import com.dskmusic.lokate.data.remote.ApiService
import com.dskmusic.lokate.data.remote.dto.AdminBackupCreateRequestDto
import com.dskmusic.lokate.data.remote.dto.AdminBackupDto
import com.dskmusic.lokate.data.remote.dto.AdminDashboardDto
import com.dskmusic.lokate.data.remote.dto.AdminDiskUsageDto
import com.dskmusic.lokate.data.remote.dto.AdminGroupCreateRequestDto
import com.dskmusic.lokate.data.remote.dto.AdminGroupDto
import com.dskmusic.lokate.data.remote.dto.AdminUserCreateRequestDto
import com.dskmusic.lokate.data.remote.dto.AdminUserDto
import com.dskmusic.lokate.data.remote.dto.AdminUserUpdateRequestDto
import com.dskmusic.lokate.data.remote.dto.AdminZoneCreateRequestDto
import com.dskmusic.lokate.data.remote.dto.AdminZoneDto
import com.dskmusic.lokate.data.remote.dto.AdminZoneUpdateRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/** Todas las acciones del panel de administración nativo — el backend ya exige admin en cada
 * endpoint (ver admin_api.py), aquí no se repite esa comprobación. */
class AdminRepository(private val api: ApiService) {

    suspend fun dashboard(): AdminDashboardDto = withContext(Dispatchers.IO) { api.adminDashboard() }
    suspend fun diskUsage(): AdminDiskUsageDto = withContext(Dispatchers.IO) { api.adminDiskUsage() }

    suspend fun listGroups(): List<AdminGroupDto> = withContext(Dispatchers.IO) { api.adminListGroups() }
    suspend fun createGroup(name: String): AdminGroupDto =
        withContext(Dispatchers.IO) { api.adminCreateGroup(AdminGroupCreateRequestDto(name)) }
    suspend fun deleteGroup(id: String) = withContext(Dispatchers.IO) { api.adminDeleteGroup(id) }

    suspend fun listUsers(): List<AdminUserDto> = withContext(Dispatchers.IO) { api.adminListUsers() }

    suspend fun createUser(username: String, password: String, displayName: String, groupId: String?, isAdmin: Boolean): AdminUserDto =
        withContext(Dispatchers.IO) {
            api.adminCreateUser(AdminUserCreateRequestDto(username, password, displayName, groupId, isAdmin))
        }

    suspend fun updateUser(id: String, displayName: String? = null, isAdmin: Boolean? = null): AdminUserDto =
        withContext(Dispatchers.IO) { api.adminUpdateUser(id, AdminUserUpdateRequestDto(displayName, isAdmin)) }

    suspend fun deleteUser(id: String) = withContext(Dispatchers.IO) { api.adminDeleteUser(id) }
    suspend fun notifyTest(id: String) = withContext(Dispatchers.IO) { api.adminNotifyTest(id) }
    suspend fun locateUser(id: String) = withContext(Dispatchers.IO) { api.adminLocateUser(id) }

    suspend fun uploadAvatar(userId: String, imageFile: File): String = withContext(Dispatchers.IO) {
        val body = imageFile.asRequestBody("image/jpeg".toMediaType())
        val part = MultipartBody.Part.createFormData("file", imageFile.name, body)
        api.adminUploadAvatar(userId, part).avatar_url
    }

    suspend fun listZones(): List<AdminZoneDto> = withContext(Dispatchers.IO) { api.adminListZones() }

    suspend fun createZone(groupId: String, name: String, lat: Double, lng: Double, radiusM: Double): AdminZoneDto =
        withContext(Dispatchers.IO) { api.adminCreateZone(AdminZoneCreateRequestDto(groupId, name, lat, lng, radiusM)) }

    suspend fun updateZone(id: String, name: String, lat: Double, lng: Double, radiusM: Double): AdminZoneDto =
        withContext(Dispatchers.IO) { api.adminUpdateZone(id, AdminZoneUpdateRequestDto(name, lat, lng, radiusM)) }

    suspend fun deleteZone(id: String) = withContext(Dispatchers.IO) { api.adminDeleteZone(id) }

    suspend fun listBackups(): List<AdminBackupDto> = withContext(Dispatchers.IO) { api.adminListBackups() }

    suspend fun createBackup(description: String): AdminBackupDto =
        withContext(Dispatchers.IO) { api.adminCreateBackup(AdminBackupCreateRequestDto(description)) }

    suspend fun deleteBackup(id: String) = withContext(Dispatchers.IO) { api.adminDeleteBackup(id) }
    suspend fun restoreBackup(id: String) = withContext(Dispatchers.IO) { api.adminRestoreBackup(id) }

    suspend fun downloadBackup(id: String, destFile: File) = withContext(Dispatchers.IO) {
        api.adminDownloadBackup(id).byteStream().use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
