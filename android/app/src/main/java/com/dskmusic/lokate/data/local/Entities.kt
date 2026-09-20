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
    /** Ids separados por comas, como los guarda el servidor. Vacío = todo el grupo. */
    val watchedIds: String = "",
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
