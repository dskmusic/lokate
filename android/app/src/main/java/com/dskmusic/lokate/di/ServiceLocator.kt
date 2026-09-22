package com.dskmusic.lokate.di

import android.content.Context
import com.dskmusic.lokate.data.local.AppDatabase
import com.dskmusic.lokate.data.prefs.SessionManager
import com.dskmusic.lokate.data.prefs.SettingsDataStore
import com.dskmusic.lokate.data.remote.ApiService
import com.dskmusic.lokate.data.remote.NetworkModule
import com.dskmusic.lokate.data.remote.ServerConfig
import com.dskmusic.lokate.data.remote.createNominatimService
import com.dskmusic.lokate.data.repository.AdminRepository
import com.dskmusic.lokate.data.repository.AuthRepository
import com.dskmusic.lokate.data.repository.BackupRepository
import com.dskmusic.lokate.data.repository.GroupRepository
import com.dskmusic.lokate.data.repository.LocationRepository
import com.dskmusic.lokate.data.repository.MessageRepository
import com.dskmusic.lokate.data.repository.ZoneRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * DI manual (sin Hilt): la app tiene pocas dependencias y todas son singletons,
 * un ServiceLocator evita el coste de anotaciones/KSP de Hilt para este tamaño de proyecto.
 */
class ServiceLocator(context: Context) {

    val session: SessionManager by lazy { SessionManager(context) }
    val settings: SettingsDataStore by lazy { SettingsDataStore(context) }

    init {
        // Igual que el arranque de MainActivity: lectura bloqueante única de DataStore para tener
        // la URL del servidor lista antes de la primera petición de red.
        ServerConfig.update(runBlocking { settings.serverBaseUrlOverride.first() })
    }

    private val database by lazy { AppDatabase.getInstance(context) }
    private val api: ApiService by lazy { NetworkModule.createApiService(context.applicationContext, session) }
    val nominatim by lazy { createNominatimService() }

    val authRepository: AuthRepository by lazy { AuthRepository(api, session, settings, context.applicationContext) }
    val groupRepository: GroupRepository by lazy { GroupRepository(api) }
    val zoneRepository: ZoneRepository by lazy { ZoneRepository(api, database.zoneDao()) }
    val locationRepository: LocationRepository by lazy {
        LocationRepository(api, database.locationHistoryDao(), database.pendingPingDao(), settings, context.applicationContext)
    }
    val messageRepository: MessageRepository by lazy { MessageRepository(api) }
    val backupRepository: BackupRepository by lazy { BackupRepository(api, settings) }
    val adminRepository: AdminRepository by lazy { AdminRepository(api) }

    companion object {
        @Volatile private var instance: ServiceLocator? = null

        fun getInstance(context: Context): ServiceLocator =
            instance ?: synchronized(this) {
                instance ?: ServiceLocator(context.applicationContext).also { instance = it }
            }
    }
}
