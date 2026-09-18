package com.dskmusic.lokate.ui.admin

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.lokate.data.remote.dto.AdminBackupDto
import com.dskmusic.lokate.data.remote.dto.AdminDashboardDto
import com.dskmusic.lokate.data.remote.dto.AdminDiskUsageDto
import com.dskmusic.lokate.data.remote.dto.AdminGroupDto
import com.dskmusic.lokate.data.remote.dto.AdminUserDto
import com.dskmusic.lokate.data.remote.dto.AdminZoneDto
import com.dskmusic.lokate.data.repository.AdminRepository
import com.dskmusic.lokate.util.FileUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

data class AdminUiState(
    val loadingDashboard: Boolean = false,
    val dashboard: AdminDashboardDto? = null,
    val diskUsage: AdminDiskUsageDto? = null,
    val loadingUsers: Boolean = false,
    val users: List<AdminUserDto> = emptyList(),
    val loadingGroups: Boolean = false,
    val groups: List<AdminGroupDto> = emptyList(),
    val loadingZones: Boolean = false,
    val zones: List<AdminZoneDto> = emptyList(),
    val loadingBackups: Boolean = false,
    val backups: List<AdminBackupDto> = emptyList(),
    val error: String? = null,
    val actionInProgress: Boolean = false,
)

/** Todo el panel de administración nativo comparte un único ViewModel — son cinco listados y
 * unas pocas acciones sobre ellos, no justifica cinco ViewModels separados. */
class AdminViewModel(private val repository: AdminRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminUiState())
    val uiState: StateFlow<AdminUiState> = _uiState

    init {
        loadDashboard()
    }

    private fun fail(t: Throwable) {
        _uiState.value = _uiState.value.copy(error = t.message)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun loadDashboard() = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(loadingDashboard = true)
        runCatching { repository.dashboard() }
            .onSuccess { _uiState.value = _uiState.value.copy(dashboard = it, loadingDashboard = false) }
            .onFailure { _uiState.value = _uiState.value.copy(loadingDashboard = false); fail(it) }
    }

    fun loadDiskUsage() = viewModelScope.launch {
        runCatching { repository.diskUsage() }
            .onSuccess { _uiState.value = _uiState.value.copy(diskUsage = it) }
            .onFailure { fail(it) }
    }

    fun loadUsers() = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(loadingUsers = true)
        runCatching { repository.listUsers() }
            .onSuccess { _uiState.value = _uiState.value.copy(users = it, loadingUsers = false) }
            .onFailure { _uiState.value = _uiState.value.copy(loadingUsers = false); fail(it) }
    }

    fun loadGroups() = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(loadingGroups = true)
        runCatching { repository.listGroups() }
            .onSuccess { _uiState.value = _uiState.value.copy(groups = it, loadingGroups = false) }
            .onFailure { _uiState.value = _uiState.value.copy(loadingGroups = false); fail(it) }
    }

    fun loadZones() = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(loadingZones = true)
        runCatching { repository.listZones() }
            .onSuccess { _uiState.value = _uiState.value.copy(zones = it, loadingZones = false) }
            .onFailure { _uiState.value = _uiState.value.copy(loadingZones = false); fail(it) }
    }

    fun createUser(
        username: String,
        password: String,
        displayName: String,
        groupId: String?,
        isAdmin: Boolean,
        onDone: () -> Unit = {},
    ) = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(actionInProgress = true)
        runCatching { repository.createUser(username, password, displayName, groupId, isAdmin) }
            .onSuccess {
                _uiState.value = _uiState.value.copy(actionInProgress = false, users = listOf(it) + _uiState.value.users)
                onDone()
            }
            .onFailure { _uiState.value = _uiState.value.copy(actionInProgress = false); fail(it) }
    }

    fun updateUser(id: String, displayName: String? = null, isAdmin: Boolean? = null, onDone: () -> Unit = {}) =
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(actionInProgress = true)
            runCatching { repository.updateUser(id, displayName, isAdmin) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        actionInProgress = false,
                        users = _uiState.value.users.map { u -> if (u.id == id) it else u },
                    )
                    onDone()
                }
                .onFailure { _uiState.value = _uiState.value.copy(actionInProgress = false); fail(it) }
        }

    fun deleteUser(id: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(actionInProgress = true)
        runCatching { repository.deleteUser(id) }
            .onSuccess {
                _uiState.value = _uiState.value.copy(
                    actionInProgress = false,
                    users = _uiState.value.users.filterNot { it.id == id },
                )
                onDone()
            }
            .onFailure { _uiState.value = _uiState.value.copy(actionInProgress = false); fail(it) }
    }

    fun createGroup(name: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(actionInProgress = true)
        runCatching { repository.createGroup(name) }
            .onSuccess {
                _uiState.value = _uiState.value.copy(actionInProgress = false, groups = listOf(it) + _uiState.value.groups)
                onDone()
            }
            .onFailure { _uiState.value = _uiState.value.copy(actionInProgress = false); fail(it) }
    }

    fun deleteGroup(id: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(actionInProgress = true)
        runCatching { repository.deleteGroup(id) }
            .onSuccess {
                _uiState.value = _uiState.value.copy(
                    actionInProgress = false,
                    groups = _uiState.value.groups.filterNot { it.id == id },
                )
                onDone()
            }
            .onFailure { _uiState.value = _uiState.value.copy(actionInProgress = false); fail(it) }
    }

    fun createZone(groupId: String, name: String, lat: Double, lng: Double, radiusM: Double, onDone: () -> Unit = {}) =
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(actionInProgress = true)
            runCatching { repository.createZone(groupId, name, lat, lng, radiusM) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(actionInProgress = false, zones = listOf(it) + _uiState.value.zones)
                    onDone()
                }
                .onFailure { _uiState.value = _uiState.value.copy(actionInProgress = false); fail(it) }
        }

    fun updateZone(id: String, name: String, lat: Double, lng: Double, radiusM: Double, onDone: () -> Unit = {}) =
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(actionInProgress = true)
            runCatching { repository.updateZone(id, name, lat, lng, radiusM) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        actionInProgress = false,
                        zones = _uiState.value.zones.map { z -> if (z.id == id) it else z },
                    )
                    onDone()
                }
                .onFailure { _uiState.value = _uiState.value.copy(actionInProgress = false); fail(it) }
        }

    fun deleteZone(id: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(actionInProgress = true)
        runCatching { repository.deleteZone(id) }
            .onSuccess {
                _uiState.value = _uiState.value.copy(
                    actionInProgress = false,
                    zones = _uiState.value.zones.filterNot { it.id == id },
                )
                onDone()
            }
            .onFailure { _uiState.value = _uiState.value.copy(actionInProgress = false); fail(it) }
    }

    fun loadBackups() = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(loadingBackups = true)
        runCatching { repository.listBackups() }
            .onSuccess { _uiState.value = _uiState.value.copy(backups = it, loadingBackups = false) }
            .onFailure { _uiState.value = _uiState.value.copy(loadingBackups = false); fail(it) }
    }

    fun createBackup(description: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(actionInProgress = true)
        runCatching { repository.createBackup(description) }
            .onSuccess {
                _uiState.value = _uiState.value.copy(actionInProgress = false, backups = listOf(it) + _uiState.value.backups)
                onDone()
            }
            .onFailure { _uiState.value = _uiState.value.copy(actionInProgress = false); fail(it) }
    }

    fun deleteBackup(id: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(actionInProgress = true)
        runCatching { repository.deleteBackup(id) }
            .onSuccess {
                _uiState.value = _uiState.value.copy(
                    actionInProgress = false,
                    backups = _uiState.value.backups.filterNot { it.id == id },
                )
                onDone()
            }
            .onFailure { _uiState.value = _uiState.value.copy(actionInProgress = false); fail(it) }
    }

    fun restoreBackup(id: String, onDone: (success: Boolean) -> Unit) = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(actionInProgress = true)
        runCatching { repository.restoreBackup(id) }
            .onSuccess { _uiState.value = _uiState.value.copy(actionInProgress = false); onDone(true) }
            .onFailure { _uiState.value = _uiState.value.copy(actionInProgress = false); fail(it); onDone(false) }
    }

    fun downloadBackup(id: String, destFile: File, onDone: (success: Boolean) -> Unit) = viewModelScope.launch {
        runCatching { repository.downloadBackup(id, destFile) }
            .onSuccess { onDone(true) }
            .onFailure { fail(it); onDone(false) }
    }

    fun notifyTest(id: String) = viewModelScope.launch {
        runCatching { repository.notifyTest(id) }.onFailure { fail(it) }
    }

    fun locateUser(id: String) = viewModelScope.launch {
        runCatching { repository.locateUser(id) }.onFailure { fail(it) }
    }

    fun uploadAvatar(userId: String, uri: Uri, context: Context) = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(actionInProgress = true)
        runCatching {
            val file = FileUtils.uriToCacheFile(context, uri)
            repository.uploadAvatar(userId, file)
        }
            .onSuccess { url ->
                _uiState.value = _uiState.value.copy(
                    actionInProgress = false,
                    users = _uiState.value.users.map { u -> if (u.id == userId) u.copy(avatar_url = url) else u },
                )
            }
            .onFailure { _uiState.value = _uiState.value.copy(actionInProgress = false); fail(it) }
    }
}
