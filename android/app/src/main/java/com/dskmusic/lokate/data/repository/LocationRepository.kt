package com.dskmusic.lokate.data.repository

import com.dskmusic.lokate.data.local.LocationHistoryDao
import com.dskmusic.lokate.data.local.LocationHistoryEntity
import com.dskmusic.lokate.data.remote.ApiService
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.data.remote.dto.LocationPingRequestDto
import com.dskmusic.lokate.util.LocationFrequency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class DeviceStatus(
    val batteryLevel: Int?,
    val isCharging: Boolean?,
    val wifiConnected: Boolean?,
    val wifiSsid: String? = null,
    /** Códigos de [com.dskmusic.lokate.util.ConfigCheck] separados por comas, vacío si todo
     * está bien. Va aquí y no como parámetro suelto de [LocationRepository.ping] porque se lee
     * en el mismo sitio que el resto del estado del dispositivo, en cada ping, sin tocar los
     * sitios que llaman. */
    val configIssues: String? = null,
)

class LocationRepository(
    private val api: ApiService,
    private val historyDao: LocationHistoryDao,
) {
    suspend fun ping(
        lat: Double,
        lng: Double,
        accuracy: Float?,
        status: DeviceStatus,
        frequency: LocationFrequency,
    ) = withContext(Dispatchers.IO) {
        api.ping(
            LocationPingRequestDto(
                lat = lat,
                lng = lng,
                accuracy = accuracy,
                battery_level = status.batteryLevel,
                is_charging = status.isCharging,
                wifi_connected = status.wifiConnected,
                wifi_ssid = status.wifiSsid,
                location_frequency = frequency.name,
                config_issues = status.configIssues,
            ),
        )
    }

    suspend fun groupLatest(): List<LocationDto> = withContext(Dispatchers.IO) { api.groupLatestLocations() }

    suspend fun ringDevice(userId: String) = withContext(Dispatchers.IO) { api.ringDevice(userId) }

    suspend fun stopRing(userId: String) = withContext(Dispatchers.IO) { api.stopRing(userId) }

    suspend fun requestLocation(userId: String) = withContext(Dispatchers.IO) { api.requestLocation(userId) }

    /** [fromIso]/[toIso]: instantes UTC en ISO-8601 (calculados en el cliente para respetar
     * su huso horario) — un día concreto ("ayer", "hoy" o una fecha elegida). */
    suspend fun refreshHistoryRange(userId: String, fromIso: String, toIso: String) = withContext(Dispatchers.IO) {
        cacheHistory(userId, api.locationHistory(userId = userId, fromTs = fromIso, toTs = toIso))
    }

    private suspend fun cacheHistory(userId: String, points: List<com.dskmusic.lokate.data.remote.dto.LocationHistoryPointDto>) {
        historyDao.clearForUser(userId)
        historyDao.insertAll(
            points.map {
                LocationHistoryEntity(
                    userId = userId,
                    lat = it.lat,
                    lng = it.lng,
                    accuracy = it.accuracy,
                    timestampMillis = parseIsoToMillis(it.timestamp),
                )
            },
        )
    }

    fun observeLocalHistory(userId: String, sinceMillis: Long) = historyDao.observeHistory(userId, sinceMillis)

    suspend fun clearLocalHistory() = withContext(Dispatchers.IO) { historyDao.clear() }

    private fun parseIsoToMillis(iso: String): Long {
        val formats = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", "yyyy-MM-dd'T'HH:mm:ss")
        for (pattern in formats) {
            runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                return sdf.parse(iso)!!.time
            }
        }
        return System.currentTimeMillis()
    }
}
