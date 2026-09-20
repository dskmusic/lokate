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
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class HistoryUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val startTime: LocalTime = LocalTime.MIN,
    val endTime: LocalTime = LocalTime.MAX,
    val loading: Boolean = true,
    val members: List<GroupMemberDto> = emptyList(),
    val selectedUserId: String? = null,
    val selectedDisplayName: String? = null,
) {
    /** Sin acotar: el día entero, que es lo que quiere casi siempre quien abre el historial. */
    val isFullDay: Boolean get() = startTime == LocalTime.MIN && endTime == LocalTime.MAX
}

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
        val state = _uiState.value
        val uid = state.selectedUserId ?: return
        viewModelScope.launch {
            // El tramo pedido es el que se guarda en la caché local (refreshHistoryRange borra lo
            // anterior de esa persona), así que acotar la hora aquí acota también lo que se pinta.
            val zone = ZoneId.systemDefault()
            val fromIso = DateTimeFormatter.ISO_INSTANT.format(date.atTime(state.startTime).atZone(zone).toInstant())
            val to = if (state.isFullDay) {
                date.plusDays(1).atStartOfDay(zone).toInstant()
            } else {
                date.atTime(state.endTime).atZone(zone).toInstant()
            }
            runCatching { locationRepository.refreshHistoryRange(uid, fromIso, DateTimeFormatter.ISO_INSTANT.format(to)) }
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }

    /** Horas del día elegido. Al revés (22:00 → 06:00) se entiende de la menor a la mayor: el
     * historial va de un día, no cruza la medianoche. */
    fun setTimeRange(a: LocalTime, b: LocalTime) {
        _uiState.value = _uiState.value.copy(startTime = minOf(a, b), endTime = maxOf(a, b))
        setDate(_uiState.value.selectedDate)
    }

    fun clearTimeRange() = setTimeRange(LocalTime.MIN, LocalTime.MAX)
}
