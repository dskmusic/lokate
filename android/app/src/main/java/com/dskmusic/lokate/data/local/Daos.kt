package com.dskmusic.lokate.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ZoneDao {
    @Query("SELECT * FROM zones ORDER BY name")
    fun observeAll(): Flow<List<ZoneEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(zones: List<ZoneEntity>)

    @Query("DELETE FROM zones WHERE id NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<String>)

    @Query("DELETE FROM zones WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM zones")
    suspend fun clear()
}

@Dao
interface LocationHistoryDao {
    @Query("SELECT * FROM location_history WHERE userId = :userId AND timestampMillis >= :sinceMillis ORDER BY timestampMillis ASC")
    fun observeHistory(userId: String, sinceMillis: Long): Flow<List<LocationHistoryEntity>>

    @Insert
    suspend fun insertAll(points: List<LocationHistoryEntity>)

    @Query("DELETE FROM location_history WHERE userId = :userId")
    suspend fun clearForUser(userId: String)

    @Query("DELETE FROM location_history")
    suspend fun clear()
}
