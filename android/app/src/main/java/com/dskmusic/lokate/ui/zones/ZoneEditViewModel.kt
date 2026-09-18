package com.dskmusic.lokate.ui.zones

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.lokate.data.remote.NominatimService
import com.dskmusic.lokate.data.remote.dto.ZoneDto
import com.dskmusic.lokate.data.repository.ZoneRepository
import com.dskmusic.lokate.ui.map.MapCameraMemory
import com.dskmusic.lokate.util.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class SearchResult(val label: String, val lat: Double, val lng: Double)

data class ZoneEditUiState(
    val name: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val radiusM: Double = 50.0,
    val searchResults: List<SearchResult> = emptyList(),
    val searching: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

private const val MIN_QUERY_LENGTH = 3
private const val SEARCH_DEBOUNCE_MS = 500L

class ZoneEditViewModel(
    private val zoneRepository: ZoneRepository,
    private val nominatim: NominatimService,
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

    private var lastSearchAtMs = 0L
    private val searchQuery = MutableStateFlow("")

    init {
        // debounce + collectLatest: espera a que el usuario deje de teclear y cancela
        // cualquier búsqueda anterior todavía en curso (incluida su espera de rate-limit),
        // en vez de encolar una petición por cada carácter — eso era lo que daba sensación
        // de "colgado" al escribir rápido.
        viewModelScope.launch {
            searchQuery
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { query -> performSearch(query) }
        }
    }

    private suspend fun performSearch(query: String) {
        if (query.trim().length < MIN_QUERY_LENGTH) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList(), searching = false)
            return
        }

        _uiState.value = _uiState.value.copy(searching = true)

        // Nominatim exige respetar 1 req/seg — esperamos lo que falte desde la última llamada.
        val elapsed = System.currentTimeMillis() - lastSearchAtMs
        if (elapsed < Constants.NOMINATIM_MIN_INTERVAL_MS) {
            delay(Constants.NOMINATIM_MIN_INTERVAL_MS - elapsed)
        }
        lastSearchAtMs = System.currentTimeMillis()

        runCatching { nominatim.search(query) }
            .onSuccess { results ->
                _uiState.value = _uiState.value.copy(
                    searching = false,
                    searchResults = results.take(8).map {
                        SearchResult(it.display_name, it.lat.toDouble(), it.lon.toDouble())
                    },
                )
            }
            .onFailure { _uiState.value = _uiState.value.copy(searching = false, error = it.message) }
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

    fun searchAddress(query: String) {
        searchQuery.value = query
    }

    fun clearSearchResults() {
        _uiState.value = _uiState.value.copy(searchResults = emptyList())
    }

    fun selectSearchResult(result: SearchResult) {
        _uiState.value = _uiState.value.copy(lat = result.lat, lng = result.lng, searchResults = emptyList())
    }

    fun save() {
        val s = _uiState.value
        val lat = s.lat ?: return
        val lng = s.lng ?: return
        if (s.name.isBlank()) return

        _uiState.value = s.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching {
                if (existingZone != null) {
                    zoneRepository.updateZone(existingZone.id, s.name, lat, lng, s.radiusM)
                } else {
                    zoneRepository.createZone(s.name, lat, lng, s.radiusM)
                }
            }
                .onSuccess { _uiState.value = _uiState.value.copy(saving = false, saved = true) }
                .onFailure { _uiState.value = _uiState.value.copy(saving = false, error = it.message) }
        }
    }
}
