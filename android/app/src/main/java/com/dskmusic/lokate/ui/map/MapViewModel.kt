package com.dskmusic.lokate.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.lokate.data.remote.dto.GroupDto
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.data.repository.GroupRepository
import com.dskmusic.lokate.data.repository.LocationRepository
import com.dskmusic.lokate.data.repository.ZoneRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class MapUiState(
    val members: List<LocationDto> = emptyList(),
    val loading: Boolean = true,
    val checkedGroup: Boolean = false,
    val group: GroupDto? = null,
    val connectionError: Boolean = false,
)

private const val POLL_INTERVAL_MS = 15_000L

class MapViewModel(
    private val locationRepository: LocationRepository,
    private val zoneRepository: ZoneRepository,
    private val groupRepository: GroupRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState

    val zones = zoneRepository.observeZones()

    private var pollingJob: Job? = null

    /** Modo prueba de los admins: con él activo el sondeo se para, para que un marcador que se
     * acaba de arrastrar no vuelva de golpe a su sitio a los 15 s. */
    private var testMode = false

    init {
        refresh()
    }

    /** Se llama también al volver de Grupo/Zonas/Ajustes (onResume) por si el grupo cambió. */
    fun refresh() {
        viewModelScope.launch {
            runCatching { groupRepository.myGroupOrNull() }
                .onSuccess { group ->
                    _uiState.value = _uiState.value.copy(group = group, checkedGroup = true, connectionError = false)
                    if (group != null) {
                        runCatching { zoneRepository.refresh() }
                        startPolling()
                    } else {
                        pollingJob?.cancel()
                        _uiState.value = _uiState.value.copy(loading = false, members = emptyList())
                    }
                }
                .onFailure {
                    // Servidor inalcanzable (DNS, timeout, conexión rechazada...): no debe tirar
                    // la app abajo al arrancar, solo avisar y dejar reintentar.
                    _uiState.value = _uiState.value.copy(checkedGroup = true, loading = false, connectionError = true)
                }
        }
    }

    private fun startPolling() {
        if (testMode || pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (true) {
                runCatching { locationRepository.groupLatest() }
                    .onSuccess { _uiState.value = _uiState.value.copy(members = it, loading = false) }
                    .onFailure { _uiState.value = _uiState.value.copy(loading = false) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun setTestMode(active: Boolean) {
        testMode = active
        if (active) pollingJob?.cancel() else refresh()
    }

    /** Mueve a un miembro solo en el mapa de quien está probando: el arrastre no se guarda en
     * ningún sitio (ver AdminRepository.simulatePosition), así que la posición simulada solo
     * existe aquí hasta salir del modo. */
    fun moveMemberLocally(userId: String, lat: Double, lng: Double) {
        _uiState.value = _uiState.value.copy(
            members = _uiState.value.members.map {
                if (it.user_id == userId) it.copy(lat = lat, lng = lng) else it
            },
        )
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }
}
