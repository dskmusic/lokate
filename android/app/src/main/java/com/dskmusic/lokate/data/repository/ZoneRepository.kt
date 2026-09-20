package com.dskmusic.lokate.data.repository

import com.dskmusic.lokate.data.local.ZoneDao
import com.dskmusic.lokate.data.local.ZoneEntity
import com.dskmusic.lokate.data.remote.ApiService
import com.dskmusic.lokate.data.remote.dto.ZoneCreateRequestDto
import com.dskmusic.lokate.data.remote.dto.ZoneDto
import com.dskmusic.lokate.data.remote.dto.ZoneNotificationPrefDto
import com.dskmusic.lokate.data.remote.dto.ZoneNotificationPrefUpdateRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ZoneRepository(
    private val api: ApiService,
    private val dao: ZoneDao,
) {
    /** Zonas cacheadas localmente (disponibles sin conexión); se refrescan con [refresh]. */
    fun observeZones(): Flow<List<ZoneDto>> = dao.observeAll().map { entities -> entities.map { it.toDto() } }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val zones = api.listZones()
        dao.upsertAll(zones.map { it.toEntity() })
        dao.deleteMissing(zones.map { it.id })
    }

    suspend fun createZone(
        name: String,
        lat: Double,
        lng: Double,
        radiusM: Double,
        watchedUserIds: List<String> = emptyList(),
    ): ZoneDto = withContext(Dispatchers.IO) {
        val zone = api.createZone(ZoneCreateRequestDto(name, lat, lng, radiusM, watchedUserIds))
        dao.upsertAll(listOf(zone.toEntity()))
        zone
    }

    suspend fun updateZone(
        id: String,
        name: String,
        lat: Double,
        lng: Double,
        radiusM: Double,
        watchedUserIds: List<String> = emptyList(),
    ): ZoneDto = withContext(Dispatchers.IO) {
        val zone = api.updateZone(id, ZoneCreateRequestDto(name, lat, lng, radiusM, watchedUserIds))
        dao.upsertAll(listOf(zone.toEntity()))
        zone
    }

    suspend fun deleteZone(id: String) = withContext(Dispatchers.IO) {
        api.deleteZone(id)
        dao.deleteById(id)
    }

    suspend fun clearLocalCache() = withContext(Dispatchers.IO) { dao.clear() }

    suspend fun notificationPrefs(): List<ZoneNotificationPrefDto> = withContext(Dispatchers.IO) {
        api.zoneNotificationPrefs()
    }

    suspend fun updateNotificationPref(zoneId: String, notifyOnEnter: Boolean, notifyOnExit: Boolean): ZoneNotificationPrefDto =
        withContext(Dispatchers.IO) {
            api.updateZoneNotificationPref(zoneId, ZoneNotificationPrefUpdateRequestDto(notifyOnEnter, notifyOnExit))
        }
}

private fun ZoneEntity.toDto() = ZoneDto(
    id, name, lat, lng, radiusM,
    watchedIds.split(",").filter { it.isNotBlank() },
)

private fun ZoneDto.toEntity() = ZoneEntity(
    id, name, lat, lng, radius_m,
    watched_user_ids.joinToString(","),
)
