package com.dskmusic.lokate.ui.zones

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.lokate.data.remote.dto.GroupMemberDto
import com.dskmusic.lokate.data.remote.dto.ZoneDto
import com.dskmusic.lokate.data.repository.GroupRepository
import com.dskmusic.lokate.data.repository.ZoneRepository
import com.dskmusic.lokate.ui.map.MapCameraMemory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ZoneEditUiState(
    val name: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val radiusM: Double = 50.0,
    val members: List<GroupMemberDto> = emptyList(),
    /** Quién dispara los avisos de esta zona. Se marca a todos al crearla. */
    val watchedIds: Set<String> = emptySet(),
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

class ZoneEditViewModel(
    private val zoneRepository: ZoneRepository,
    private val groupRepository: GroupRepository,
    private val existingZone: ZoneDto?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ZoneEditUiState(
            name = existingZone?.name.orEmpty(),
            // Zona nueva sin posición de partida propia: usa donde esté el mapa principal en
            // ese momento (recordado entre pestañas, ver MapCameraMemory) en vez de arrancar
            // vacío — si nunca se ha abierto el mapa en esta sesión, se queda en null igual
            // que antes y el usuario elige a mano (tocar el mapa o buscar dirección).
            lat = existingZone?.lat ?: MapCameraMemory.center?.latitude,
            lng = existingZone?.lng ?: MapCameraMemory.center?.longitude,
            radiusM = existingZone?.radius_m ?: 50.0,
        ),
    )
    val uiState: StateFlow<ZoneEditUiState> = _uiState

    init {
        viewModelScope.launch {
            val members = runCatching { groupRepository.members() }.getOrDefault(emptyList())
            // El servidor guarda "vacío = todo el grupo"; aquí se enseña con todos marcados.
            val saved = existingZone?.watched_user_ids.orEmpty()
            _uiState.value = _uiState.value.copy(
                members = members,
                watchedIds = if (saved.isEmpty()) members.map { it.id }.toSet() else saved.toSet(),
            )
        }
    }

    fun toggleWatched(userId: String) {
        val state = _uiState.value
        _uiState.value = state.copy(
            watchedIds = if (userId in state.watchedIds) state.watchedIds - userId else state.watchedIds + userId,
        )
    }

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
    }

    fun updateRadius(radius: Double) {
        _uiState.value = _uiState.value.copy(radiusM = radius)
    }

    fun pickLocation(lat: Double, lng: Double) {
        _uiState.value = _uiState.value.copy(lat = lat, lng = lng)
    }

    fun save() {
        val s = _uiState.value
        val lat = s.lat ?: return
        val lng = s.lng ?: return
        if (s.name.isBlank()) return

        // Todos marcados se guarda como lista vacía: así la zona sigue avisando de quien entre
        // en el grupo más adelante, en vez de quedarse congelada con los miembros de hoy.
        val watched = if (s.watchedIds.size == s.members.size) emptyList() else s.watchedIds.toList()

        _uiState.value = s.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching {
                if (existingZone != null) {
                    zoneRepository.updateZone(existingZone.id, s.name, lat, lng, s.radiusM, watched)
                } else {
                    zoneRepository.createZone(s.name, lat, lng, s.radiusM, watched)
                }
            }
                .onSuccess { _uiState.value = _uiState.value.copy(saving = false, saved = true) }
                .onFailure { _uiState.value = _uiState.value.copy(saving = false, error = it.message) }
        }
    }
}
