package com.dskmusic.lokate.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.lokate.data.repository.AuthRepository
import com.dskmusic.lokate.data.repository.BackupRepository
import com.dskmusic.lokate.data.repository.GroupRepository
import com.dskmusic.lokate.data.repository.LocationRepository
import com.dskmusic.lokate.data.repository.ZoneRepository
import com.dskmusic.lokate.data.prefs.SettingsDataStore
import com.dskmusic.lokate.data.remote.ServerConfig
import com.dskmusic.lokate.data.remote.dto.GroupMemberDto
import com.dskmusic.lokate.ui.map.MapCameraMemory
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.util.FileUtils
import com.dskmusic.lokate.util.LocationFrequency
import com.dskmusic.lokate.util.MapTileCache
import com.dskmusic.lokate.util.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val settings: SettingsDataStore,
    private val authRepository: AuthRepository,
    private val groupRepository: GroupRepository,
    private val zoneRepository: ZoneRepository,
    private val locationRepository: LocationRepository,
    private val backupRepository: BackupRepository,
) : ViewModel() {

    /** Estado de la copia en la nube para la pantalla de ajustes. */
    data class BackupUiState(
        val working: Boolean = false,
        /** Fecha ISO de la copia que hay en el servidor, null = no hay (o aún no se ha mirado). */
        val savedAt: String? = null,
        val checked: Boolean = false,
        /** Mensaje de resultado (hecho / restaurada / el error), para enseñarlo y olvidarlo. */
        val message: String? = null,
        val failed: Boolean = false,
    )

    private val _backup = MutableStateFlow(BackupUiState())
    val backup: StateFlow<BackupUiState> = _backup

    fun loadBackupInfo() = viewModelScope.launch {
        runCatching { backupRepository.fetch() }
            .onSuccess { info -> _backup.value = _backup.value.copy(savedAt = info.updated_at, checked = true) }
            .onFailure { _backup.value = _backup.value.copy(checked = true) }
    }

    fun backupNow() = viewModelScope.launch {
        _backup.value = _backup.value.copy(working = true, message = null, failed = false)
        runCatching { backupRepository.backupNow() }
            .onSuccess { _backup.value = BackupUiState(savedAt = it, checked = true, message = DONE_BACKUP) }
            .onFailure { _backup.value = _backup.value.copy(working = false, message = it.message, failed = true) }
    }

    /** Restaurar pisa los ajustes de este móvil con los de la copia (avisado en el diálogo). */
    fun restoreBackup() = viewModelScope.launch {
        _backup.value = _backup.value.copy(working = true, message = null, failed = false)
        runCatching { backupRepository.restore() }
            .onSuccess { restored ->
                _backup.value = _backup.value.copy(
                    working = false,
                    message = if (restored) DONE_RESTORE else DONE_EMPTY,
                    failed = !restored,
                )
            }
            .onFailure { _backup.value = _backup.value.copy(working = false, message = it.message, failed = true) }
    }

    fun clearBackupMessage() {
        _backup.value = _backup.value.copy(message = null, failed = false)
    }

    private val _avatarUploading = MutableStateFlow(false)
    val avatarUploading: StateFlow<Boolean> = _avatarUploading

    private val _testNotificationSent = MutableStateFlow(false)
    val testNotificationSent: StateFlow<Boolean> = _testNotificationSent

    private val _groupMembers = MutableStateFlow<List<GroupMemberDto>>(emptyList())
    val groupMembers: StateFlow<List<GroupMemberDto>> = _groupMembers

    private val _mapCacheClearedBytes = MutableStateFlow<Long?>(null)
    val mapCacheClearedBytes: StateFlow<Long?> = _mapCacheClearedBytes

    // null = todavía no se sabe (cargando o no admin). Solo lo usan admins, ver SettingsScreen.
    private val _updateFlagEnabled = MutableStateFlow<Boolean?>(null)
    val updateFlagEnabled: StateFlow<Boolean?> = _updateFlagEnabled
    private val _updateFlagError = MutableStateFlow<String?>(null)
    val updateFlagError: StateFlow<String?> = _updateFlagError

    companion object {
        /** Claves internas que la pantalla traduce a texto: el ViewModel no toca recursos. */
        const val DONE_BACKUP = "backup_done"
        const val DONE_RESTORE = "restore_done"
        const val DONE_EMPTY = "restore_empty"
    }

    fun loadUpdateFlag() = viewModelScope.launch {
        runCatching { authRepository.checkForUpdate() }.onSuccess { _updateFlagEnabled.value = it }
    }

    fun setUpdateFlag(enabled: Boolean) = viewModelScope.launch {
        _updateFlagError.value = null
        runCatching { authRepository.setUpdateFlag(enabled) }
            .onSuccess { _updateFlagEnabled.value = it }
            .onFailure { _updateFlagError.value = it.message }
    }

    fun clearMapCache(context: Context) = viewModelScope.launch {
        val freedBytes = withContext(Dispatchers.IO) {
            val sizeBefore = MapTileCache.sizeBytes(context)
            MapTileCache.clear(context)
            sizeBefore
        }
        settings.setMapCacheClearedAt(System.currentTimeMillis())
        _mapCacheClearedBytes.value = freedBytes
    }

    fun loadGroupMembers() = viewModelScope.launch {
        runCatching { groupRepository.members() }.onSuccess { _groupMembers.value = it }
    }

    /** userIds null = a todo el grupo (la pantalla pide confirmación antes de eso). */
    fun sendTestNotification(userIds: List<String>?) = viewModelScope.launch {
        runCatching { groupRepository.sendTestNotification(userIds) }
            .onSuccess { _testNotificationSent.value = true }
    }

    fun setRingSoundUri(uri: String?) = viewModelScope.launch {
        settings.setRingSoundUri(uri)
        syncZoneChannel()
    }

    fun setVibrationPattern(pattern: com.dskmusic.lokate.util.VibrationPattern) = viewModelScope.launch {
        settings.setVibrationPattern(pattern)
        syncZoneChannel()
    }

    /** El sonido y la vibración de los avisos de zona los pone el canal de notificación, y su
     * id cambia al cambiarlos (ver NotificationHelper.ensureZoneChannel): hay que recrearlo y
     * volver a registrarlo en el servidor, que es quien lo manda dentro del push. */
    private suspend fun syncZoneChannel() {
        runCatching { authRepository.registerCurrentDeviceToken() }
    }

    fun setMapInitialZoom(zoom: Int) = viewModelScope.launch { settings.setMapInitialZoom(zoom) }

    /** Vuelve al zoom por defecto y además olvida la posición/zoom que el mapa tenía
     * recordados, que si no seguirían mandando sobre el ajuste al volver al mapa. */
    fun resetMapInitialZoom() = viewModelScope.launch {
        settings.setMapInitialZoom(Constants.MAP_DEFAULT_ZOOM)
        MapCameraMemory.resetView()
    }

    fun setAppLanguage(language: String) = viewModelScope.launch { settings.setAppLanguage(language) }

    fun updateDisplayName(name: String, onDone: () -> Unit) = viewModelScope.launch {
        runCatching { authRepository.updateDisplayName(name) }.onSuccess { onDone() }
    }

    /** Prueba el sonido/vibración de NOTIFICACIONES (zonas) — "hacer sonar" y los mensajes de
     * emergencia no son personalizables, siempre usan la alarma del sistema. */
    fun testRing(context: Context) = viewModelScope.launch {
        val uri = settings.ringSoundUri.first()
        val pattern = settings.vibrationPattern.first()
        com.dskmusic.lokate.push.NotificationHelper.playRingAlarm(context, uri, pattern, forcePriority = false)
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }
    fun setAccentColor(argb: Int) = viewModelScope.launch { settings.setAccentColor(argb) }
    fun setLocationFrequency(freq: LocationFrequency) = viewModelScope.launch {
        settings.setLocationFrequency(freq)
        runCatching { authRepository.registerCurrentDeviceToken() }
    }
    fun setKnownWifiSsids(ssids: Set<String>) = viewModelScope.launch { settings.setKnownWifiSsids(ssids) }
    fun setNotifyZone(enabled: Boolean) = viewModelScope.launch {
        settings.setNotifyZoneEnabled(enabled)
        // Apagarlos aquí deja de registrar canal: el servidor vuelve a mandar "solo data", que
        // esta app filtra. Si no, el sistema los seguiría pintando por su cuenta.
        syncZoneChannel()
    }
    fun setNotifySystem(enabled: Boolean) = viewModelScope.launch { settings.setNotifySystemEnabled(enabled) }

    /** null/blank restaura la URL por defecto de compilación. Se aplica de inmediato, sin reiniciar la app. */
    fun setServerBaseUrl(url: String?) = viewModelScope.launch {
        settings.setServerBaseUrlOverride(url)
        ServerConfig.update(url)
    }

    fun uploadAvatar(uri: Uri, context: Context) {
        _avatarUploading.value = true
        viewModelScope.launch {
            runCatching {
                val file = FileUtils.uriToCacheFile(context, uri)
                authRepository.uploadAvatar(file)
            }
            _avatarUploading.value = false
        }
    }

    fun logout(context: Context, onDone: () -> Unit) {
        viewModelScope.launch {
            // El borrado de lo que era del usuario (pings sin entregar incluidos) va dentro de
            // logout: tiene que pasar igual entrando con otra cuenta, no solo por este botón.
            authRepository.logout(context)
            onDone()
        }
    }

    fun clearLocalData(onDone: () -> Unit) {
        viewModelScope.launch {
            zoneRepository.clearLocalCache()
            locationRepository.clearLocalHistory()
            settings.clearAll()
            onDone()
        }
    }
}
