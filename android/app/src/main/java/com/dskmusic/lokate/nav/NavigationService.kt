package com.dskmusic.lokate.nav

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.VibrationEffect
import androidx.core.app.ServiceCompat
import com.dskmusic.lokate.R
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.push.NotificationHelper
import com.dskmusic.lokate.ui.map.PlannedRoute
import com.dskmusic.lokate.ui.map.TravelMode
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.util.PermissionUtils
import com.dskmusic.lokate.util.formatDistance
import com.dskmusic.lokate.util.formatDuration
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

/** Cada cuánto se pide posición navegando. Un segundo es lo que hace falta para que un "gire
 * ahora" caiga en el cruce y no después; es mucho gasto de batería, y por eso esto solo corre
 * mientras se navega y se apaga al llegar. */
private const val FIX_INTERVAL_MS = 1_000L

/** El reloj de la simulación y las velocidades que finge: andando y en coche por ciudad. */
private const val SIM_TICK_MS = 1_000L
private const val FOOT_SPEED_MS = 1.4
private const val CAR_SPEED_MS = 13.9

/** Lo menos que se espera entre dos recálculos. Sin esto, un callejón sin salida con mala
 * cobertura se come la cuota de rutas del día en un minuto. */
private const val REROUTE_MIN_GAP_MS = 30_000L

/** Lo que se queda vivo el servicio después de llegar: lo justo para que termine de decir "ha
 * llegado a su destino" en vez de cortarse a media palabra. */
private const val ARRIVED_CLOSE_MS = 8_000L

/**
 * La navegación por voz mientras dura el viaje.
 *
 * Servicio en primer plano por lo de siempre: con la pantalla apagada y el móvil en el bolsillo
 * o en el soporte, una pantalla de Compose no existe. Aquí dentro está lo que tiene que seguir
 * corriendo —el GPS a 1 Hz, el motor de decisiones y la voz— y nada más: la pantalla lee el
 * estado de [NavSession] y se pinta sola cuando la hay.
 *
 * No toca [com.dskmusic.lokate.location.LocationForegroundService] ni se mezcla con él: ese
 * manda posiciones a la familia a su ritmo y con su lógica de ahorro, y esto necesita justo lo
 * contrario. Los dos conviven; se suman dos notificaciones y ya está.
 */
class NavigationService : Service() {

    private lateinit var locator: ServiceLocator
    private lateinit var fusedClient: FusedLocationProviderClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var voice: NavVoice? = null

    /** Paquete del motor con el que se levantó [voice]. El motor no se puede cambiar en caliente:
     * si el usuario elige otro hay que tirar la instancia y hacer otra. */
    private var voiceEngine: String? = null
    private var guide: NavGuide? = null
    private var muted = false
    private var simulating = false
    private var simSpeed = 1
    private var simJob: Job? = null
    private var rerouteJob: Job? = null
    private var lastRerouteMs = 0L
    private var closing = false
    private var paused = false

    /** Por dónde va la simulación. Es campo y no variable del bucle para que la pausa pueda
     * cortar el reloj y luego seguir desde el mismo metro. */
    private var simMeters = 0.0

    /** Lo último que se publicó, para repintar el aviso al pausar: en pausa no llega ninguna
     * posición que lo refresque. */
    private var lastState: NavState? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val fix = result.lastLocation ?: return
            feed(fix.latitude, fix.longitude)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locator = ServiceLocator.getInstance(this)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        NotificationHelper.createChannels(this)
        // El motor de voz tarda en levantarse (hay que enlazar con otra app). Se arranca ya, para
        // que la primera indicación no se pierda por llegar antes que él.
        scope.launch { rebuildVoice(locator.settings.navVoiceEngine.first()) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // El botón de pausa del aviso vuelve a arrancar este mismo servicio con su acción. La
        // navegación ya está montada: aquí no se prepara nada, solo se para o se sigue el viaje.
        if (intent?.action == Constants.ACTION_NAV_PAUSE && guide != null) {
            togglePause()
            return START_NOT_STICKY
        }
        val route = NavSession.route
        try {
            ServiceCompat.startForeground(
                this,
                Constants.NAV_NOTIFICATION_ID,
                NotificationHelper.buildNavNotification(this, navTitle(null), navBody(null), paused),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: SecurityException) {
            // Sin permiso de ubicación no hay servicio de tipo "location" que valga. Pasa si se
            // ha revocado el permiso con la app abierta: no es para petar, es para no navegar.
            stopNav()
            return START_NOT_STICKY
        }
        if (route == null) {
            stopNav()
            return START_NOT_STICKY
        }
        // Un segundo arranque con la navegación ya en marcha no la reinicia: se perderían los
        // avisos ya dados y el móvil repetiría media ruta.
        if (guide == null) scope.launch { begin(route) }
        // START_NOT_STICKY: si el sistema mata el proceso, la ruta se ha ido con él. Resucitar
        // el servicio sin ruta solo deja una notificación muerta en la barra.
        return START_NOT_STICKY
    }

    private suspend fun begin(route: PlannedRoute) {
        val settings = locator.settings
        muted = !settings.navVoiceEnabled.first()
        simSpeed = settings.navSimulationSpeed.first().coerceIn(1, 4)
        simulating = NavSession.simulating
        val motor = NavGuide(route, settings.navVerbosity.first())
        guide = motor
        applyVoice()

        // La verbosidad y el silencio se pueden cambiar a mitad de ruta desde la pantalla, y
        // tienen que notarse en la siguiente indicación, no en el siguiente viaje.
        scope.launch { settings.navVerbosity.collect { guide?.verbosity = it } }
        scope.launch { settings.navVoiceEnabled.collect { muted = !it } }
        // Y el motor igual: se elige en el mismo cuadro, que se abre a mitad de ruta. La primera
        // emisión es la que ya está puesta y no hace nada.
        scope.launch { settings.navVoiceEngine.collect { rebuildVoice(it) } }

        if (simulating) startSimulation(motor) else startLocation()
    }

    /**
     * Pausa o reanuda el viaje, sea de verdad o simulado.
     *
     * En pausa se corta de donde viene la posición —el reloj de la simulación o el GPS— y con
     * eso se para todo lo demás solo: sin posiciones no hay indicaciones que decir, ni recálculo,
     * ni muñeco que mover. Lo que NO se toca es el motor: la ruta y lo ya avisado siguen donde
     * estaban, así que reanudar no repite media ruta.
     */
    private fun togglePause() {
        paused = !paused
        NavSession.setPaused(paused)
        if (paused) {
            simJob?.cancel()
            simJob = null
            runCatching { fusedClient.removeLocationUpdates(callback) }
        } else {
            val motor = guide
            if (motor != null) {
                if (simulating) startSimulation(motor) else startLocation()
            }
        }
        // Por startForeground y no por notify: el botón ha arrancado el servicio otra vez
        // (getForegroundService) y el sistema exige volver a declararse en primer plano.
        runCatching {
            ServiceCompat.startForeground(
                this,
                Constants.NAV_NOTIFICATION_ID,
                NotificationHelper.buildNavNotification(this, navTitle(lastState), navBody(lastState), paused),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        }
    }

    private fun rebuildVoice(pkg: String) {
        if (voice != null && voiceEngine == pkg) return
        voice?.shutdown()
        voiceEngine = pkg
        voice = NavVoice(this, pkg) { scope.launch { applyVoice() } }
    }

    private suspend fun applyVoice() {
        val settings = locator.settings
        voice?.configure(
            settings.navVoiceName.first(),
            settings.navSpeechRate.first(),
            settings.navSpeechPitch.first(),
        )
    }

    private fun startLocation() {
        if (!PermissionUtils.hasForegroundLocationPermission(this)) {
            stopNav()
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, FIX_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FIX_INTERVAL_MS / 2)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, callback, mainLooper)
        } catch (e: SecurityException) {
            stopNav()
        }
    }

    /**
     * El viaje de mentira: avanza por la línea de la ruta a la velocidad que tocaría y le da
     * esas posiciones al mismo motor que las de verdad.
     *
     * Nunca pide GPS ni recalcula. Es la forma de comprobar una ruta entera —que las
     * indicaciones llegan cuando tienen que llegar, en el orden bueno y una sola vez— sentado en
     * el sofá, que es la única manera de probar esto sin conducir cuarenta kilómetros.
     */
    private fun startSimulation(motor: NavGuide) {
        simJob = scope.launch {
            val speed = if (motor.route.mode == TravelMode.CAR) CAR_SPEED_MS else FOOT_SPEED_MS
            while (true) {
                val point = motor.simulatedPoint(simMeters) ?: motor.route.points.last()
                feed(point.latitude, point.longitude)
                if (simMeters >= motor.totalMeters) return@launch
                delay(SIM_TICK_MS)
                simMeters += speed * simSpeed * SIM_TICK_MS / 1000.0
            }
        }
    }

    /** Una posición, de donde venga: el motor decide, aquí solo se habla, se pinta y se obedece. */
    private fun feed(lat: Double, lng: Double) {
        val motor = guide ?: return
        // En pausa se ignora: el GPS se ha soltado, pero una posición ya en vuelo puede llegar
        // justo después y no es plan de cantar una maniobra con el viaje parado.
        if (paused) return
        val update = motor.update(lat, lng, System.currentTimeMillis(), simulating, muted)
        NavSession.publish(update.state)
        if (!muted) update.speech.forEach(::say)
        update.speech.forEach(::vibrate)
        updateNotification(update.state)
        if (update.reroute) reroute(lat, lng)
        if (update.state.arrived) finishSoon()
    }

    /**
     * El aviso para el bolsillo: un toque cuando la maniobra es ya, dos al llegar.
     *
     * Se llama aparte de [say] y no desde dentro a propósito: en silencio es justo cuando más
     * falta hace, porque entonces es lo único que avisa sin mirar la pantalla.
     */
    private fun vibrate(speech: NavSpeech) {
        val patron = when {
            speech is NavSpeech.Maneuver && speech.now -> longArrayOf(0, 150)
            speech is NavSpeech.Arrived -> longArrayOf(0, 120, 100, 120)
            else -> return
        }
        NotificationHelper.systemVibrator(this)
            ?.vibrate(VibrationEffect.createWaveform(patron, -1))
    }

    private fun say(speech: NavSpeech) {
        val v = voice ?: return
        when (speech) {
            is NavSpeech.Start -> v.say(
                getString(
                    R.string.nav_say_start,
                    speech.instruction,
                    spokenDistance(speech.meters),
                    speech.minutes,
                ),
            )
            is NavSpeech.Maneuver -> if (speech.now) {
                v.say(speech.instruction, urgent = true)
            } else {
                v.say(getString(R.string.nav_say_maneuver, spokenDistance(speech.meters), speech.instruction))
            }
            NavSpeech.Arrived -> v.say(getString(R.string.nav_say_arrived), urgent = true)
            NavSpeech.Rerouting -> v.say(getString(R.string.nav_say_rerouting), urgent = true)
        }
    }

    /** "300 metros", "1,2 kilómetros". Escrito con la palabra entera a propósito: "1,2 km" lo
     * lee cada motor de voz como le parece, y alguno dice "ka eme". */
    private fun spokenDistance(meters: Int): String = if (meters >= 1_000) {
        getString(R.string.nav_say_km, String.format(Locale.getDefault(), "%.1f", meters / 1_000.0))
    } else {
        getString(R.string.nav_say_meters, meters)
    }

    /** El titular del aviso. null = todavía no ha hablado el motor (o está en pausa y no hay
     * nada nuevo que contar). */
    private fun navTitle(state: NavState?): String = when {
        paused -> getString(R.string.nav_paused)
        state == null -> getString(R.string.nav_preparing)
        state.arrived -> getString(R.string.nav_arrived)
        state.offRoute -> getString(R.string.nav_off_route)
        else -> getString(
            R.string.nav_notification_title,
            formatDistance(state.metersToManeuver.toFloat()),
            state.instruction,
        )
    }

    private fun navBody(state: NavState?): String = if (state == null) {
        NavSession.route?.let { formatDistance(it.meters.toFloat()) }.orEmpty()
    } else {
        getString(
            R.string.nav_notification_body,
            formatDistance(state.remainingMeters.toFloat()),
            formatDuration(state.remainingSeconds / 60),
        )
    }

    private fun updateNotification(state: NavState) {
        lastState = state
        getSystemService(NotificationManager::class.java).notify(
            Constants.NAV_NOTIFICATION_ID,
            NotificationHelper.buildNavNotification(this, navTitle(state), navBody(state), paused),
        )
    }

    /**
     * Se ha salido de la ruta: se pide otra desde donde está hasta el mismo destino.
     *
     * Si no sale (sin cobertura, sin cuota) se sigue con la que había y punto: una ruta que ya no
     * cuadra guía mal, pero quedarse sin ninguna guía peor. Y se recalcula como mucho cada
     * [REROUTE_MIN_GAP_MS].
     */
    private fun reroute(lat: Double, lng: Double) {
        val motor = guide ?: return
        val ahora = System.currentTimeMillis()
        if (rerouteJob?.isActive == true || ahora - lastRerouteMs < REROUTE_MIN_GAP_MS) return
        lastRerouteMs = ahora
        motor.markRecalculating(true)
        if (!muted) say(NavSpeech.Rerouting)
        val vieja = motor.route
        val destino = vieja.points.last()
        rerouteJob = scope.launch {
            val result = runCatching {
                locator.locationRepository.route(
                    lat, lng, destino.latitude, destino.longitude, vieja.mode.api,
                )
            }.getOrNull()
            val points = result?.points?.takeIf { it.size > 1 }?.map { GeoPoint(it[0], it[1]) }
            if (points == null) {
                motor.markRecalculating(false)
                return@launch
            }
            val nueva = vieja.copy(
                points = points,
                meters = result.distance_m,
                seconds = result.duration_s,
                steps = result.steps,
                // La ruta nueva sale de donde estás ahora: lo de la posición vieja ya no aplica.
                staleAt = null,
            )
            NavSession.replaceRoute(nueva)
            guide = NavGuide(nueva, locator.settings.navVerbosity.first())
        }
    }

    private fun finishSoon() {
        if (closing) return
        closing = true
        scope.launch {
            delay(ARRIVED_CLOSE_MS)
            stopNav()
        }
    }

    private fun stopNav() {
        NavSession.finish()
        stopSelf()
    }

    override fun onDestroy() {
        simJob?.cancel()
        rerouteJob?.cancel()
        runCatching { fusedClient.removeLocationUpdates(callback) }
        voice?.shutdown()
        voice = null
        voiceEngine = null
        guide = null
        scope.cancel()
        NavSession.finish()
        super.onDestroy()
    }
}

/** El "Salir" del aviso de navegación. Receiver y no servicio porque se pulsa desde la barra con
 * la app en segundo plano, y desde ahí no hay pantalla a la que pedirle nada. */
class NavStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        NavSession.stop(context.applicationContext)
    }
}
