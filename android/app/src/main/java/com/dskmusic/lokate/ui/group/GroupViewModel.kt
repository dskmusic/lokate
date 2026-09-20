package com.dskmusic.lokate.ui.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.lokate.data.remote.dto.AdminGroupDto
import com.dskmusic.lokate.data.remote.dto.GroupDto
import com.dskmusic.lokate.data.remote.dto.GroupMemberDto
import com.dskmusic.lokate.data.remote.dto.UserDto
import com.dskmusic.lokate.data.repository.AdminRepository
import com.dskmusic.lokate.data.repository.AuthRepository
import com.dskmusic.lokate.data.repository.GroupRepository
import com.dskmusic.lokate.data.repository.ZoneRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class GroupUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val existingGroup: GroupDto? = null,
    val members: List<GroupMemberDto> = emptyList(),
    /** Solo se rellena para administradores: los grupos a los que pueden saltar. */
    val availableGroups: List<AdminGroupDto> = emptyList(),
    /** Grupos en los que el admin entra sin que le vean. */
    val hiddenGroups: Set<String> = emptySet(),
    val currentUser: UserDto? = null,
    val checkedExisting: Boolean = false,
    val left: Boolean = false,
    val connectionError: Boolean = false,
)

class GroupViewModel(
    private val groupRepository: GroupRepository,
    private val authRepository: AuthRepository,
    private val adminRepository: AdminRepository,
    private val zoneRepository: ZoneRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroupUiState())
    val uiState: StateFlow<GroupUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val user = runCatching { authRepository.me() }.getOrNull()
            runCatching { groupRepository.myGroupOrNull() }
                .onSuccess { existing ->
                    val members = if (existing != null) runCatching { groupRepository.members() }.getOrDefault(emptyList()) else emptyList()
                    val groups = if (user?.is_admin == true) {
                        runCatching { adminRepository.listGroups() }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                    _uiState.value = _uiState.value.copy(
                        currentUser = user,
                        existingGroup = existing,
                        members = members,
                        availableGroups = groups,
                        hiddenGroups = user?.hidden_groups.orEmpty().toSet(),
                        checkedExisting = true,
                        connectionError = false,
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(currentUser = user, checkedExisting = true, connectionError = true)
                }
        }
    }

    fun createGroup(name: String, onSuccess: () -> Unit) {
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { groupRepository.createGroup(name) }
                .onSuccess { _uiState.value = _uiState.value.copy(loading = false); onSuccess() }
                .onFailure { _uiState.value = _uiState.value.copy(loading = false, error = it.message) }
        }
    }

    fun joinGroup(inviteCode: String, onSuccess: () -> Unit) {
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { groupRepository.joinGroup(inviteCode.trim().uppercase()) }
                .onSuccess { _uiState.value = _uiState.value.copy(loading = false); onSuccess() }
                .onFailure { _uiState.value = _uiState.value.copy(loading = false, error = it.message) }
        }
    }

    /** Solo admin: cambiar de grupo activo. El servidor solo manda avisos a los miembros del
     * grupo al que perteneces, así que el grupo activo es también el único del que llegan. */
    fun switchGroup(groupId: String) {
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { groupRepository.switchGroup(groupId) }
                .onSuccess {
                    // Las zonas cacheadas son las del grupo anterior y el mapa las pinta desde
                    // Room: refrescar aquí evita enseñar zonas de un grupo en el que ya no estás.
                    runCatching { zoneRepository.refresh() }
                    _uiState.value = _uiState.value.copy(loading = false)
                    refresh()
                }
                .onFailure { _uiState.value = _uiState.value.copy(loading = false, error = it.message) }
        }
    }

    /** Solo admin: aparecer o no en un grupo, también en los que no son el activo. */
    fun setGroupVisible(groupId: String, visible: Boolean) {
        viewModelScope.launch {
            runCatching { groupRepository.setGroupVisibility(groupId, visible) }
                .onSuccess { _uiState.value = _uiState.value.copy(hiddenGroups = it.toSet()) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun leaveGroup(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { groupRepository.leaveGroup() }
                .onSuccess { onDone() }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }
}
