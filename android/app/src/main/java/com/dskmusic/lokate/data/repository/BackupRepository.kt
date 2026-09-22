package com.dskmusic.lokate.data.repository

import com.dskmusic.lokate.BuildConfig
import com.dskmusic.lokate.data.prefs.SettingsDataStore
import com.dskmusic.lokate.data.remote.ApiService
import com.dskmusic.lokate.data.remote.dto.BackupDto
import com.dskmusic.lokate.data.remote.dto.BackupUploadRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Copia de los ajustes del móvil en el servidor: una sola por usuario, la nueva pisa la vieja.
 *
 * Lo que se copia son los ajustes locales (tema, color, notificaciones, frecuencia, wifis de
 * casa...). Las zonas, los avisos de cada zona y el grupo ya viven en el servidor y vuelven
 * solos al entrar con el mismo usuario: duplicarlos aquí solo daría dos verdades que se
 * contradicen.
 */
class BackupRepository(
    private val api: ApiService,
    private val settings: SettingsDataStore,
) {
    /** Sube los ajustes de ahora mismo. Devuelve la fecha que ha quedado guardada en el servidor. */
    suspend fun backupNow(): String? = withContext(Dispatchers.IO) {
        val response = api.putBackup(BackupUploadRequestDto(settings.exportJson(), BuildConfig.VERSION_NAME))
        settings.setBackupLastAt(System.currentTimeMillis())
        response.updated_at
    }

    /** Lo que hay guardado (o exists=false si nunca se ha hecho copia). */
    suspend fun fetch(): BackupDto = withContext(Dispatchers.IO) { api.getBackup() }

    /** Vuelca la copia del servidor encima de los ajustes de este móvil. false = no había copia. */
    suspend fun restore(): Boolean = withContext(Dispatchers.IO) {
        val backup = api.getBackup()
        val payload = backup.payload
        if (!backup.exists || payload.isNullOrBlank()) return@withContext false
        settings.importJson(payload)
        true
    }

    /**
     * Copia automática: una al día como mucho. La llama el worker de respaldo, que ya se
     * despierta cada 15 minutos con red — un worker propio solo para esto sería otro despertador
     * más en la batería del usuario para el mismo trabajo.
     *
     * Si falla no pasa nada: el siguiente intento es dentro de un cuarto de hora.
     */
    suspend fun autoBackupIfDue() {
        val last = settings.backupLastAt.first()
        if (System.currentTimeMillis() - last < AUTO_BACKUP_INTERVAL_MS) return
        backupNow()
    }

    private companion object {
        const val AUTO_BACKUP_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
