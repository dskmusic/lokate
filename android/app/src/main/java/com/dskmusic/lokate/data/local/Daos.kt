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
interface PendingPingDao {
    @Insert
    suspend fun insert(ping: PendingPingEntity)

    @Query("SELECT * FROM pending_pings ORDER BY timestampMillis ASC LIMIT :max")
    suspend fun oldest(max: Int): List<PendingPingEntity>

    @Query("DELETE FROM pending_pings WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("DELETE FROM pending_pings WHERE timestampMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    /** Deja solo los [max] mas recientes: una cola sin tope acaba siendo un segundo
     * historial, y lo viejo ya no le importa a nadie. */
    @Query("DELETE FROM pending_pings WHERE id NOT IN (SELECT id FROM pending_pings ORDER BY timestampMillis DESC LIMIT :max)")
    suspend fun trim(max: Int)

    @Query("DELETE FROM pending_pings")
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
