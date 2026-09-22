package com.dskmusic.lokate.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "zones")
data class ZoneEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Double,
    val isPublic: Boolean = true,
    val createdBy: String? = null,
)

/**
 * Ping que no se pudo entregar (sin cobertura, servidor caido, tunel de metro). Se guarda
 * con SU hora, no con la del envio: lo que interesa del historial es donde estaba entonces.
 */
@Entity(tableName = "pending_pings")
data class PendingPingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val accuracy: Float?,
    val batteryLevel: Int?,
    val isCharging: Boolean?,
    val wifiConnected: Boolean?,
    val wifiSsid: String?,
    val locationFrequency: String?,
    val configIssues: String?,
    val updateMode: String?,
    val timestampMillis: Long,
)

@Entity(tableName = "location_history")
data class LocationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val lat: Double,
    val lng: Double,
    val accuracy: Float?,
    val timestampMillis: Long,
)
