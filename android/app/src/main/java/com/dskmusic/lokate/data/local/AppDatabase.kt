package com.dskmusic.lokate.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ZoneEntity::class, LocationHistoryEntity::class, PendingPingEntity::class],
    // 6: wifis de la zona (ZoneEntity.wifiSsids). Con fallbackToDestructiveMigration esto
    // vacia la cache local — las zonas y el historial se vuelven a bajar del servidor, y la
    // cola de pings pendientes (minutos, no dias) se pierde: ver PendingPingEntity.
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun zoneDao(): ZoneDao
    abstract fun locationHistoryDao(): LocationHistoryDao
    abstract fun pendingPingDao(): PendingPingDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "lokate.db")
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
