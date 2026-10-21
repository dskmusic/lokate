package com.dskmusic.lokate.ui.zones

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.lokate.data.remote.dto.GroupMemberDto
import com.dskmusic.lokate.data.remote.dto.ZoneDto
import com.dskmusic.lokate.data.repository.GroupRepository
import com.dskmusic.lokate.data.repository.LocationRepository
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
    /** true = la ve todo el grupo (por defecto); false = privada, solo de quien la crea. */
    val isPublic: Boolean = true,
    val members: List<GroupMemberDto> = emptyList(),
    /** De quién quiero que me avisen en esta zona (solo mío). Se marca a todos al crearla. */
    val watchedIds: Set<String> = emptySet(),
    /** Wifis que, estando conectado a ellas, cuentan como estar dentro de esta zona. */
    val wifiSsids: Set<String> = emptySet(),
    /** Wifis que se pueden marcar: nombre de la red y quién está en ella ahora mismo (null si
     * solo viene de lo ya guardado o se escribió a mano). */
    val wifiOptions: List<Pair<String, String?>> = emptyList(),
    /** No se ha podido leer mi preferencia de avisos de esta zona (o la lista de miembros): se
     * enseña y, sobre todo, al guardar NO se toca. Ver [ZoneEditViewModel.prefLegible]. */
    val prefsUnavailable: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

class ZoneEditViewModel(
    private val zoneRepository: ZoneRepository,
    private val groupRepository: GroupRepository,
    private val locationRepository: LocationRepository,
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
            isPublic = existingZone?.is_public ?: true,
            wifiSsids = existingZone?.wifi_ssids.orEmpty().toSet(),
        ),
    )
    val uiState: StateFlow<ZoneEditUiState> = _uiState

    // Las otras dos casillas de MI preferencia de esta zona (avisar al entrar / al salir, que se
    // tocan en la lista de zonas): se guardan tal cual para no pisarlas al cambiar los vigilados.
    private var notifyOnEnter = false
    private var notifyOnExit = false

    // ¿Se pudo LEER entera mi preferencia de esta zona? Guardar la zona la reescribe, así que sin
    // haberla leído lo que se mandaba era el valor inicial de aquí arriba: "no avisar ni al
    // entrar ni al salir", y de paso la lista de vigilados ensanchada a todo el grupo. O sea que
    // tocar el radio de una zona podía apagarte tus propios avisos sin decir nada, y bastaba con
    // que fallara la red un segundo — o con darle a Guardar antes de que terminara esta carga,
    // que no esperaba a nadie. El 06/10/2026 una salida de zona no avisó a quien tocaba y costó
    // una mañana entera averiguar por qué. Lo que no se ha podido leer, no se escribe.
    private var prefLegible = false

    init {
        viewModelScope.launch {
            val miembros = runCatching { groupRepository.members() }
            val members = miembros.getOrDefault(emptyList())
            // Zona nueva: no hay preferencia previa que leer ni que destrozar.
            val prefs = existingZone?.let { runCatching { zoneRepository.notificationPrefs() } }
            val pref = existingZone?.let { zone ->
                prefs?.getOrNull()?.firstOrNull { it.zone_id == zone.id }
            }
            pref?.let {
                notifyOnEnter = it.notify_on_enter
                notifyOnExit = it.notify_on_exit
            }
            prefLegible = miembros.isSuccess && (prefs?.isSuccess ?: true)
            // Las wifis que se pueden marcar salen de donde está ahora mismo cada miembro (la
            // mía incluida): así se enlaza la wifi de la casa de otra persona sin estar allí.
            // Las ya guardadas van primero, aunque hoy nadie esté conectado a ellas.
            val vistas = runCatching { locationRepository.groupLatest() }.getOrDefault(emptyList())
                .mapNotNull { punto ->
                    punto.wifi_ssid?.takeIf { it.isNotBlank() }?.let { it to punto.display_name }
                }
            val guardadas: List<Pair<String, String?>> = _uiState.value.wifiSsids.map { it to null }
            // El servidor guarda "vacío = todo el grupo"; aquí se enseña con todos marcados.
            val saved = pref?.watched_user_ids.orEmpty()
            _uiState.value = _uiState.value.copy(
                members = members,
                watchedIds = if (saved.isEmpty()) members.map { it.id }.toSet() else saved.toSet(),
                wifiOptions = (guardadas + vistas).distinctBy { it.first },
                prefsUnavailable = !prefLegible,
            )
        }
    }

    fun toggleWatched(userId: String) {
        val state = _uiState.value
        _uiState.value = state.copy(
            watchedIds = if (userId in state.watchedIds) state.watchedIds - userId else state.watchedIds + userId,
        )
    }

    fun toggleSsid(ssid: String) {
        val state = _uiState.value
        _uiState.value = state.copy(
            wifiSsids = if (ssid in state.wifiSsids) state.wifiSsids - ssid else state.wifiSsids + ssid,
        )
    }

    /** Añade una wifi escrita a mano (la de un sitio donde no hay nadie ahora mismo). */
    fun addSsid(ssid: String) {
        val limpio = ssid.trim()
        if (limpio.isEmpty()) return
        val state = _uiState.value
        _uiState.value = state.copy(
            wifiSsids = state.wifiSsids + limpio,
            wifiOptions = (state.wifiOptions + (limpio to null)).distinctBy { it.first },
        )
    }

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
    }

    fun updateVisibility(isPublic: Boolean) {
        _uiState.value = _uiState.value.copy(isPublic = isPublic)
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

        // Todos marcados se guarda como lista vacía: así se sigue avisando de quien entre en el
        // grupo más adelante, en vez de quedarse congelada con los miembros de hoy.
        val watched = if (s.watchedIds.size == s.members.size) emptyList() else s.watchedIds.toList()

        _uiState.value = s.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching {
                val zone = if (existingZone != null) {
                    zoneRepository.updateZone(
                        existingZone.id, s.name, lat, lng, s.radiusM, s.isPublic, s.wifiSsids.toList(),
                    )
                } else {
                    zoneRepository.createZone(s.name, lat, lng, s.radiusM, s.isPublic, s.wifiSsids.toList())
                }
                // La zona (sitio y radio) la ve el grupo entero si es pública; a quién vigila,
                // solo este móvil. Y solo se reescribe si se pudo leer (ver prefLegible): guardar
                // una zona no puede apagarle los avisos a nadie por un fallo de red.
                if (prefLegible) {
                    zoneRepository.updateNotificationPref(zone.id, notifyOnEnter, notifyOnExit, watched)
                }
            }
                .onSuccess { _uiState.value = _uiState.value.copy(saving = false, saved = true) }
                .onFailure { _uiState.value = _uiState.value.copy(saving = false, error = it.message) }
        }
    }
}
