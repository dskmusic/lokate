package com.dskmusic.lokate.nav

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.dskmusic.lokate.ui.map.PlannedRoute
import com.dskmusic.lokate.util.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * El tablón donde la navegación y la pantalla se ven: el servicio escribe el estado, el mapa lo
 * lee. Es un objeto de proceso, como [com.dskmusic.lokate.location.LiveTracking], y por el mismo
 * motivo: el servicio vive más que la pantalla (de eso va navegar con el móvil en el bolsillo),
 * así que el estado no puede ser de nadie que se muera al girar el teléfono.
 *
 * La ruta se pasa por aquí y no en los extras del Intent a propósito: son cientos de puntos y
 * sus maniobras, y meter eso en un Binder es pedir un TransactionTooLargeException el día que
 * alguien navegue cincuenta kilómetros.
 */
object NavSession {

    private val _state = MutableStateFlow<NavState?>(null)

    /** Lo último que ha dicho el motor. null = no se está navegando. */
    val state: StateFlow<NavState?> = _state.asStateFlow()

    /** La ruta que se está navegando, que el servicio recoge al arrancar. */
    var route: PlannedRoute? = null
        private set

    /** Arrancó como simulación: ni se pide GPS ni se recalcula nada. */
    var simulating = false
        private set

    private val _paused = MutableStateFlow(false)

    /** El viaje está en pausa: ni se habla, ni se pide GPS, ni corre el reloj de la simulación,
     * pero la ruta y lo ya avisado siguen aquí esperando. */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    val active: Boolean get() = route != null

    fun start(context: Context, planned: PlannedRoute, simulate: Boolean) {
        route = planned
        simulating = simulate
        _state.value = null
        _paused.value = false
        ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, NavigationService::class.java))
        finish()
    }

    /** Pausa o reanuda, lo mismo navegando de verdad que simulando. Lo hace el servicio porque
     * es quien tiene el GPS y el reloj de la simulación; aquí solo se le avisa. */
    fun togglePause(context: Context) {
        if (route == null) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, NavigationService::class.java).setAction(Constants.ACTION_NAV_PAUSE),
        )
    }

    internal fun setPaused(value: Boolean) {
        _paused.value = value
    }

    /** La ruta nueva de un recálculo, puesta donde estaba la otra: la navegación sigue sin
     * parar y la pantalla la pinta sola porque lee de aquí. */
    internal fun replaceRoute(planned: PlannedRoute) {
        route = planned
    }

    internal fun publish(state: NavState) {
        _state.value = state
    }

    internal fun finish() {
        route = null
        simulating = false
        _state.value = null
        _paused.value = false
    }
}
