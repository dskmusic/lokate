package com.dskmusic.lokate.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.lokate.data.remote.dto.GroupDto
import com.dskmusic.lokate.data.remote.dto.GroupMemberDto
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.data.repository.GroupRepository
import com.dskmusic.lokate.data.repository.LocationRepository
import com.dskmusic.lokate.data.repository.ZoneRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class MapUiState(
    val members: List<LocationDto> = emptyList(),
    /** Todos los del grupo, hayan mandado ubicación o no: la pestaña Gente los necesita para
     * poder entrar en la ficha de quien aún no ha mandado nada y pedírsela. */
    val groupMembers: List<GroupMemberDto> = emptyList(),
    val loading: Boolean = true,
    val checkedGroup: Boolean = false,
    val group: GroupDto? = null,
    val connectionError: Boolean = false,
    /** A quién se está siguiendo en el mapa, null = a nadie. Vive en el ViewModel (y no en la
     * pantalla) para que cambiar de pestaña no lo apague: la pantalla se destruye, el
     * ViewModel no. */
    val followUserId: String? = null,
) {
    /** El seguido ya está en tiempo real de verdad (lo confirma el servidor en cada sondeo),
     * no solo "le hemos dado al botón". */
    val followLive: Boolean
        get() = followUserId != null && members.any { it.user_id == followUserId && it.live_seconds > 0 }
}

private const val POLL_INTERVAL_MS = 15_000L

/** Siguiendo a alguien su móvil manda posición cada 3 s: de poco sirve mirarlo cada 15. */
private const val FOLLOW_POLL_INTERVAL_MS = 3_000L

/** Cada cuánto se renueva la marca de seguimiento en el servidor. Muy por debajo de lo que dura
 * allí (3 min), para que un renovado perdido no corte el tiempo real. Y es además cada cuánto
 * el servidor puede repetir el push al seguido si sigue sin dar señales, así que bajarlo
 * acelera el arranque cuando el primer push se pierde. */
private const val LIVE_RENEW_MS = 30_000L

/** Lo que esperamos a que el móvil seguido conteste antes de avisar de que no lo ha cogido. Da
 * de sobra para el push, levantar el servicio y el primer fix; pasado esto, o el push se perdió
 * o el sistema no dejó arrancar nada. Se sigue intentando igual, solo es el aviso. */
private const val FOLLOW_CONFIRM_TIMEOUT_MS = 20_000L

class MapViewModel(
    private val locationRepository: LocationRepository,
    private val zoneRepository: ZoneRepository,
    private val groupRepository: GroupRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState

    /** true = el seguimiento prendió de verdad en el otro móvil; false = no contesta. Evento de
     * un solo uso (la pantalla lo pinta como aviso), y por eso la cuenta atrás vive aquí: cambiar
     * de pestaña mata la pantalla, no el ViewModel, y el aviso llega igual al volver. */
    private val _followFeedback = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val followFeedback: SharedFlow<Boolean> = _followFeedback

    val zones = zoneRepository.observeZones()

    private var pollingJob: Job? = null

    /** Modo prueba de los admins: con él activo el sondeo se para, para que un marcador que se
     * acaba de arrastrar no vuelva de golpe a su sitio a los 15 s. */
    private var testMode = false

    /** El bucle que mantiene viva la marca de seguimiento en el servidor. A quién se sigue va
     * en el propio [uiState], que es lo que pinta la pantalla. */
    private var followJob: Job? = null

    private val followUserId: String? get() = _uiState.value.followUserId

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
                        runCatching { groupRepository.members() }
                            .onSuccess { _uiState.value = _uiState.value.copy(groupMembers = it) }
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

    /** [restart] = tirar el sondeo en curso y empezar de nuevo, para que un cambio de ritmo se
     * note ya y no al terminar la espera en curso (hasta 15 s de mapa parado justo al pulsar
     * "seguir", que es cuando más se está mirando). */
    private fun startPolling(restart: Boolean = false) {
        if (testMode) return
        if (restart) pollingJob?.cancel() else if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (true) {
                runCatching { locationRepository.groupLatest() }
                    .onSuccess { _uiState.value = _uiState.value.copy(members = it, loading = false) }
                    .onFailure { _uiState.value = _uiState.value.copy(loading = false) }
                delay(if (followUserId != null) FOLLOW_POLL_INTERVAL_MS else POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Seguir a alguien en el mapa le pone el móvil en tiempo real mientras dure, y lo devuelve a
     * su ritmo al soltarlo. La marca caduca sola en el servidor: si esta app se va sin avisar (o
     * el aviso se pierde), el otro móvil vuelve a lo suyo en un par de minutos igualmente.
     */
    fun setFollowing(userId: String?) {
        if (userId == followUserId) return
        val previous = followUserId
        _uiState.value = _uiState.value.copy(followUserId = userId)
        followJob?.cancel()
        followJob = viewModelScope.launch {
            if (previous != null) runCatching { locationRepository.setLiveTracking(previous, false) }
            if (userId == null) return@launch
            launch {
                val confirmed = withTimeoutOrNull(FOLLOW_CONFIRM_TIMEOUT_MS) {
                    _uiState.first { it.followUserId == userId && it.followLive }
                }
                _followFeedback.tryEmit(confirmed != null)
            }
            while (true) {
                runCatching { locationRepository.setLiveTracking(userId, true) }
                delay(LIVE_RENEW_MS)
            }
        }
        startPolling(restart = true)
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
        // El "deja de seguir" no se manda aquí a propósito: para cuando esto ocurre (cerrar
        // sesión, matar la app) puede que ya no haya ni red ni scope donde mandarlo. La marca
        // del servidor caduca sola en 3 min sin renovaciones, que es justo el seguro que tiene.
        followJob?.cancel()
        super.onCleared()
    }
}
