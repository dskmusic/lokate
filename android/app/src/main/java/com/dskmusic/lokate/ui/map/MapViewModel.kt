package com.dskmusic.lokate.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.lokate.data.remote.dto.GroupDto
import com.dskmusic.lokate.data.remote.dto.GroupMemberDto
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.data.repository.GroupRepository
import com.dskmusic.lokate.data.repository.LocationRepository
import com.dskmusic.lokate.data.repository.ZoneRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    /** Se agotó la espera sin que el otro móvil diera señales (GPS apagado, push perdido, el
     * sistema no dejó arrancar el servicio...). Se sigue intentando, pero hay que enseñarlo:
     * un botón encendido mientras no llega nada es peor que no tener botón. */
    val followUnconfirmed: Boolean = false,
    /** Se le ha pedido a todo el grupo una ubicacion fresca y aun se esperan respuestas. */
    val refreshingAll: Boolean = false,
) {
    /** El seguido ya está en tiempo real de verdad (lo confirma el servidor en cada sondeo),
     * no solo "le hemos dado al botón". */
    val followLive: Boolean
        get() = followUserId != null && members.any { it.user_id == followUserId && it.live_seconds > 0 }

    val followState: FollowState
        get() = when {
            followUserId == null -> FollowState.OFF
            followLive -> FollowState.LIVE
            followUnconfirmed -> FollowState.FAILED
            else -> FollowState.PENDING
        }
}

/** OFF = no se sigue a nadie. PENDING = orden mandada, esperando a que el otro móvil conteste.
 * LIVE = está mandando posición en tiempo real. FAILED = no contesta (se sigue intentando). */
enum class FollowState { OFF, PENDING, LIVE, FAILED }

private const val POLL_INTERVAL_MS = 15_000L

/** Siguiendo a alguien su móvil manda posición cada 3 s: de poco sirve mirarlo cada 15. */
private const val FOLLOW_POLL_INTERVAL_MS = 3_000L

/** Cada cuánto se renueva la marca de seguimiento en el servidor. Muy por debajo de lo que dura
 * allí (3 min), para que un renovado perdido no corte el tiempo real. Y es además cada cuánto
 * el servidor puede repetir el push al seguido si sigue sin dar señales, así que bajarlo
 * acelera el arranque cuando el primer push se pierde. */
private const val LIVE_RENEW_MS = 30_000L

/** Techo de un seguimiento en vivo. Es el gasto más caro que esta app puede provocar (GPS fino
 * cada 3 s en el móvil del OTRO), así que no depende de que uno se acuerde de soltarlo: se suelta
 * solo y se vuelve a pulsar si hace falta. Irse de la app ya lo suelta antes (ver
 * [AppForeground]); esto cubre dejarla abierta y olvidarse. */
const val FOLLOW_MAX_MS = 30 * 60_000L

/** Lo que hay que contarle a quien está siguiendo: si prendió allí, si no contesta, o si se ha
 * soltado solo por llevar demasiado rato. */
enum class FollowFeedback { CONFIRMED, UNCONFIRMED, AUTO_STOPPED }

/** Lo que esperamos a que el móvil seguido conteste antes de avisar de que no lo ha cogido. Da
 * de sobra para el push, levantar el servicio y el primer fix; pasado esto, o el push se perdió
 * o el sistema no dejó arrancar nada. Se sigue intentando igual, solo es el aviso. */
private const val FOLLOW_CONFIRM_TIMEOUT_MS = 20_000L

/** Ritmo y tope del sondeo tras pedirle a todo el grupo una ubicacion fresca: los moviles
 * contestan de uno en uno, asi que la lista se refresca segun van llegando. */
private const val REFRESH_ALL_POLL_MS = 2_500L
private const val REFRESH_ALL_POLLS = 8

/**
 * Si la app se está viendo. La pone MainActivity en su onStart/onStop y la mira quien tenga algo
 * caro en marcha que no tenga sentido con el móvil en el bolsillo.
 *
 * A nivel de proceso y no desde la pantalla a propósito: el composable del mapa se destruye al
 * cambiar de pestaña, así que un observador puesto desde allí no se entera de que la app se va —
 * y el mapa es justo quien puede dejar a OTRO móvil en tiempo real. Seguir a alguien, entrar en
 * su ficha y bloquear el móvil dejaba su GPS a tope toda la noche.
 *
 * ponytail: un StateFlow suelto en vez de ProcessLifecycleOwner, que pide otra dependencia para
 * contestar exactamente esto. Si algún día hacen falta más estados, esa es la sustitución.
 */
object AppForeground {
    val visible = MutableStateFlow(true)
}

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
    private val _followFeedback = MutableSharedFlow<FollowFeedback>(extraBufferCapacity = 1)
    val followFeedback: SharedFlow<FollowFeedback> = _followFeedback

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
        // Se queda escuchando mientras viva el ViewModel: es lo que hace que un seguimiento se
        // suelte aunque la pantalla del mapa ya no exista.
        viewModelScope.launch {
            AppForeground.visible.collect { if (!it) onAppHidden() }
        }
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
     * La pantalla deja de verse: se corta el sondeo. No lo hace el sistema por nosotros — con el
     * servicio de ubicación en marcha el proceso nunca se congela, así que este bucle seguía
     * pidiendo el grupo cada 15 s (cada 3 s siguiendo a alguien) con el móvil en el bolsillo y
     * la pantalla apagada: 5.760 peticiones al día despertando la radio para nadie.
     *
     * El seguimiento en vivo NO se toca aquí a propósito: cambiar de pestaña no puede cortarlo.
     */
    fun pausePolling() {
        pollingJob?.cancel()
    }

    /**
     * La posición de una persona AHORA, sin esperar al siguiente sondeo. Devuelve null si la
     * petición falla o si esa persona todavía no tiene ninguna posición.
     *
     * Es la misma llamada que hace el bucle (pedir a uno cuesta lo mismo que pedir a todos), así
     * que de paso refresca a todo el grupo. Hace falta porque al volver de una ficha el sondeo
     * llevaba parado desde que se salió del mapa: lo que hay en memoria puede ser de hace rato.
     */
    suspend fun fetchLatest(userId: String): LocationDto? =
        runCatching { locationRepository.groupLatest() }
            .onSuccess { _uiState.value = _uiState.value.copy(members = it, loading = false) }
            .getOrNull()
            ?.find { it.user_id == userId }

    /** Vuelve a verse. El bucle pide el grupo nada más entrar, así que esto ya trae lo fresco. */
    fun resumePolling() {
        if (_uiState.value.group != null) startPolling()
    }

    /** La app entera se va a segundo plano. Además de dejar de sondear se suelta a quien se
     * estuviera siguiendo: nadie está mirando el mapa y al otro lo tenemos en tiempo real, que
     * es lo más caro que se le puede hacer a la batería de un móvil. */
    fun onAppHidden() {
        setFollowing(null)
        pausePolling()
    }

    /**
     * Seguir a alguien en el mapa le pone el móvil en tiempo real mientras dure, y lo devuelve a
     * su ritmo al soltarlo. La marca caduca sola en el servidor: si esta app se va sin avisar (o
     * el aviso se pierde), el otro móvil vuelve a lo suyo en un par de minutos igualmente.
     */
    fun setFollowing(userId: String?) {
        if (userId == followUserId) return
        val previous = followUserId
        _uiState.value = _uiState.value.copy(followUserId = userId, followUnconfirmed = false)
        followJob?.cancel()
        followJob = viewModelScope.launch {
            if (previous != null) runCatching { locationRepository.setLiveTracking(previous, false) }
            if (userId == null) return@launch
            // Vigilancia de ida y vuelta: avisa cuando prende y también si deja de llegar
            // (apagar el GPS a mitad de un seguimiento es exactamente el caso a cubrir). Se
            // cancela con el propio followJob al dejar de seguir.
            launch {
                var announced: Boolean? = null
                while (true) {
                    val live = if (announced != true) {
                        withTimeoutOrNull(FOLLOW_CONFIRM_TIMEOUT_MS) { _uiState.first { it.followLive } } != null
                    } else {
                        _uiState.first { !it.followLive }
                        false
                    }
                    // Soltar a quien se seguía (o cambiar de seguido) también apaga followLive, y
                    // eso llegaba aquí como "ha dejado de contestar": el aviso de fallo salía al
                    // DESCONECTAR, que es justo cuando todo ha ido bien. Este job se cancela al
                    // dejar de seguir, pero StateFlow reanuda al de abajo en el acto (mismo hilo),
                    // antes de que la cancelación llegue.
                    if (followUserId != userId) return@launch
                    if (live == announced) continue
                    announced = live
                    _uiState.value = _uiState.value.copy(followUnconfirmed = !live)
                    _followFeedback.tryEmit(if (live) FollowFeedback.CONFIRMED else FollowFeedback.UNCONFIRMED)
                }
            }
            var running = 0L
            while (true) {
                runCatching { locationRepository.setLiveTracking(userId, true) }
                delay(LIVE_RENEW_MS)
                running += LIVE_RENEW_MS
                if (running >= FOLLOW_MAX_MS) {
                    // El aviso antes de soltar: setFollowing cancela ESTE job, y tryEmit no
                    // suspende, así que sale entero antes de que la cancelación llegue.
                    _followFeedback.tryEmit(FollowFeedback.AUTO_STOPPED)
                    setFollowing(null)
                    return@launch
                }
            }
        }
        startPolling(restart = true)
    }

    /** Pide a todo el grupo una ubicacion fresca de golpe (el mismo push silencioso que el
     * boton de la ficha, pero para todos) y va refrescando la lista segun contestan. No se
     * espera a que contesten todos: se sondea un rato fijo, o se esperaria siempre al mas
     * lento. */
    fun refreshAll() {
        if (_uiState.value.refreshingAll) return
        _uiState.value = _uiState.value.copy(refreshingAll = true)
        viewModelScope.launch {
            val ids = _uiState.value.groupMembers.map { it.id }
                .ifEmpty { _uiState.value.members.map { it.user_id } }
            ids.map { id -> async { runCatching { locationRepository.requestLocation(id) } } }.awaitAll()
            repeat(REFRESH_ALL_POLLS) {
                delay(REFRESH_ALL_POLL_MS)
                runCatching { locationRepository.groupLatest() }
                    .onSuccess { _uiState.value = _uiState.value.copy(members = it) }
            }
            _uiState.value = _uiState.value.copy(refreshingAll = false)
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
        // El "deja de seguir" no se manda aquí a propósito: para cuando esto ocurre (cerrar
        // sesión, matar la app) puede que ya no haya ni red ni scope donde mandarlo. La marca
        // del servidor caduca sola en 3 min sin renovaciones, que es justo el seguro que tiene.
        followJob?.cancel()
        super.onCleared()
    }
}

/**
 * Sondear solo mientras la pantalla se ve. El ciclo de vida de aquí dentro es el de la entrada
 * del NavHost, o sea "ya no me estás mirando" — irse de la app lo lleva [AppForeground], que es
 * otra cosa: cambiar de pestaña no puede cortar un seguimiento en vivo.
 *
 * addObserver reproduce los eventos que falten, así que registrarse con la pantalla ya visible
 * llama solo a resumePolling; no hace falta arrancar el sondeo a mano.
 */
@Composable
fun PollWhileVisible(viewModel: MapViewModel) {
    val screen = LocalLifecycleOwner.current
    DisposableEffect(screen) {
        val onScreen = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.resumePolling()
                Lifecycle.Event.ON_STOP -> viewModel.pausePolling()
                else -> Unit
            }
        }
        screen.lifecycle.addObserver(onScreen)
        onDispose {
            screen.lifecycle.removeObserver(onScreen)
            // Y pausar también aquí: al navegar fuera, el ON_STOP de la entrada y la destrucción
            // del composable caen en el mismo fotograma sin orden garantizado. Si gana la
            // destrucción, sin esto el bucle se quedaba sondeando para siempre.
            viewModel.pausePolling()
        }
    }
}
