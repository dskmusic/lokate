package com.dskmusic.lokate.ui.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.lokate.data.remote.dto.GroupDto
import com.dskmusic.lokate.data.remote.dto.GroupMemberDto
import com.dskmusic.lokate.data.remote.dto.UserDto
import com.dskmusic.lokate.data.repository.AuthRepository
import com.dskmusic.lokate.data.repository.GroupRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class GroupUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val existingGroup: GroupDto? = null,
    val members: List<GroupMemberDto> = emptyList(),
    val currentUser: UserDto? = null,
    val checkedExisting: Boolean = false,
    val left: Boolean = false,
    val connectionError: Boolean = false,
)

class GroupViewModel(
    private val groupRepository: GroupRepository,
    private val authRepository: AuthRepository,
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
                    _uiState.value = _uiState.value.copy(
                        currentUser = user,
                        existingGroup = existing,
                        members = members,
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

    fun leaveGroup(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { groupRepository.leaveGroup() }
                .onSuccess { onDone() }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }
}
