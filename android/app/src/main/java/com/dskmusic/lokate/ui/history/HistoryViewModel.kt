package com.dskmusic.lokate.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.lokate.data.prefs.SessionManager
import com.dskmusic.lokate.data.prefs.SettingsDataStore
import com.dskmusic.lokate.data.remote.dto.GroupMemberDto
import com.dskmusic.lokate.data.repository.GroupRepository
import com.dskmusic.lokate.data.repository.LocationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class HistoryUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val loading: Boolean = true,
    val members: List<GroupMemberDto> = emptyList(),
    val selectedUserId: String? = null,
    val selectedDisplayName: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val locationRepository: LocationRepository,
    private val groupRepository: GroupRepository,
    private val settings: SettingsDataStore,
    private val session: SessionManager,
    targetUserId: String?,
    targetDisplayName: String?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        HistoryUiState(selectedUserId = targetUserId, selectedDisplayName = targetDisplayName),
    )
    val uiState: StateFlow<HistoryUiState> = _uiState

    private val selectedUserIdFlow = MutableStateFlow(targetUserId)

    val points = selectedUserIdFlow.flatMapLatest { uid ->
        uid?.let { locationRepository.observeLocalHistory(it, sinceMillis = 0L) } ?: emptyFlow()
    }

    init {
        viewModelScope.launch {
            val members = runCatching { groupRepository.members() }.getOrDefault(emptyList())
            val resolvedId = targetUserId ?: settings.lastHistoryUserId.first() ?: session.userId
            val resolvedMember = members.firstOrNull { it.id == resolvedId }
            _uiState.value = _uiState.value.copy(
                members = members,
                selectedUserId = resolvedId,
                selectedDisplayName = targetDisplayName ?: resolvedMember?.display_name,
            )
            selectedUserIdFlow.value = resolvedId
            setDate(LocalDate.now())
        }
    }

    fun selectUser(member: GroupMemberDto) {
        _uiState.value = _uiState.value.copy(selectedUserId = member.id, selectedDisplayName = member.display_name)
        selectedUserIdFlow.value = member.id
        viewModelScope.launch { settings.setLastHistoryUserId(member.id) }
        setDate(_uiState.value.selectedDate)
    }

    fun setYesterday() = setDate(LocalDate.now().minusDays(1))

    fun setToday() = setDate(LocalDate.now())

    fun setDate(date: LocalDate) {
        _uiState.value = _uiState.value.copy(selectedDate = date, loading = true)
        val uid = _uiState.value.selectedUserId ?: return
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val fromIso = DateTimeFormatter.ISO_INSTANT.format(date.atStartOfDay(zone).toInstant())
            val toIso = DateTimeFormatter.ISO_INSTANT.format(date.plusDays(1).atStartOfDay(zone).toInstant())
            runCatching { locationRepository.refreshHistoryRange(uid, fromIso, toIso) }
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }
}
