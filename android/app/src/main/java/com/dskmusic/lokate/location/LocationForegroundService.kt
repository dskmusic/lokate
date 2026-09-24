package com.dskmusic.lokate.location

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dskmusic.lokate.data.remote.dto.ZoneDto
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.push.NotificationHelper
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.util.DeviceStatusUtils
import com.dskmusic.lokate.util.LocationFrequency
import com.dskmusic.lokate.util.PermissionUtils
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * START_STICKY: si el sistema mata el proceso, Android intenta recrear el servicio.
 * LocationUpdateWorker es el respaldo por si ese reinicio automático no llega a producirse
 * (algunos fabricantes lo bloquean pese a START_STICKY).
 */
class LocationForegroundService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locator: ServiceLocator
    private val serviceScope = CoroutineScope(SupervisorJob())

    /** El intervalo que eligió el usuario en Ajustes, y el que está pedido ahora mismo al
     * sistema — no tienen por qué coincidir: ver [applyLocationRequest]. */
    private var configuredIntervalMs: Long = 0
    private var currentIntervalMs: Long = -1

    /** Última posición enviada al servidor, para no repetir pings estando parado (ver
     * [worthSending]). */
    private var lastSent: Location? = null

    /** Y la última que de verdad llegó (o quedó guardada para reintentarla). Se separan
     * porque [worthSending] marca la posición ANTES de intentar mandarla: si el envío se
     * pierde del todo, hay que volver aquí, o el móvil descartaría los siguientes fixes por
     * "no se ha movido" comparándolos con uno que el servidor nunca vio. */
    private var lastDelivered: Location? = null

    /** Cuándo se dio por bueno el último fix (epoch ms). Lo usa la válvula de escape del
     * filtro de precisión: ver [tooVague]. */
    private var lastSentAt = 0L

    /** El último fix que llegó, se haya mandado o no: lo usan el ritmo por velocidad y la
     * cercanía a zonas, que quieren saber dónde está el móvil aunque ese punto no valga la pena
     * mandarlo. */
    private var lastFix: Location? = null

    /** Velocidad del último fix en m/s (0 si el chip no la da). Ver [movingIntervalMs]. */
    private var lastSpeed = 0f

    /** Detector de quieto POR POSICIÓN: desde dónde y desde cuándo el móvil no cambia de sitio.
     * Es la tercera señal de reposo y la única que no depende de nada externo — ni del permiso
     * de actividad, ni de estar en un wifi marcado. Ver [stationary]. */
    private var stillAnchor: Location? = null
    private var stillAnchorAt = 0L

    /** La espera que vuelve a mirar si el móvil se ha parado. Ver [applyLocationRequest]. */
    private var stillCheckJob: Job? = null

    /** Zonas del grupo, cacheadas en Room. Solo se usan para decidir el ritmo: quien decide de
     * verdad si se entra o se sale sigue siendo el servidor. */
    private var zones: List<ZoneDto> = emptyList()

    /** Señales de que aquí no está pasando nada: el móvil lleva un rato sin moverse y/o está en
     * un wifi que el usuario marcó como sitio fijo. */
    private var isStill = false
    private var knownWifiSsids: Set<String> = emptySet()
    private var currentSsid: String? = null
    private var transitionsRegistered = false

    /** Alguien del grupo nos tiene en seguimiento en vivo ahora mismo. Manda sobre todo lo
     * demás: ni el reposo ni el wifi de casa lo bajan. */
    private val liveActive: Boolean get() = LiveTracking.until.value > System.currentTimeMillis()

    /** El resultado de combinar las dos señales de arriba: lo calcula [applyLocationRequest] y
     * lo consulta [worthSending], que se aplica a cada fix suelto. */
    private var idle = false

    /** El modo que el grupo YA sabe, para avisar solo cuando cambia. null = aún no se ha dicho
     * nada. Ver el ping de [applyLocationRequest]. */
    private var announcedMode: String? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // Con entrega en bloque (setMaxUpdateDelayMillis) aquí llegan varios fixes de golpe:
            // se mandan todos los que aporten algo, no solo el último, para no dejar huecos en el
            // historial. El filtrado va en el hilo del callback para que no se pisen dos tandas.
            result.locations.lastOrNull()?.let {
                lastFix = it
                lastSpeed = if (it.hasSpeed()) it.speed else 0f
                trackStillness(it)
            }
            val toSend = result.locations.filter(::worthSending)
            // Momento barato para enterarse de que se ha entrado o salido de un wifi conocido:
            // si nada ha cambiado, applyLocationRequest no hace nada.
            applyLocationRequest()
            if (toSend.isEmpty()) return
            serviceScope.launch {
                runCatching {
                    val status = DeviceStatusUtils.read(applicationContext)
                    val frequency = locator.settings.locationFrequency.first()
                    for (fix in toSend) {
                        val handled = locator.locationRepository.ping(
                            fix.latitude,
                            fix.longitude,
                            fix.accuracy,
                            status,
                            frequency,
                        )
                        // Ni entregado ni guardado: el resto del lote correría la misma suerte,
                        // y se deshace la marca para no descartar los fixes que vengan detrás.
                        if (!handled) {
                            lastSent = lastDelivered
                            break
                        }
                        lastDelivered = fix
                    }
                }
            }
        }
    }

    /**
     * Entrar y salir de quieto según el acelerómetro. Lo vigila el co-procesador de sensores del
     * móvil, que gasta microamperios y no despierta a la CPU para nada: sale muchísimo más
     * barato que el fix de GPS o de wifi que nos ahorra.
     */
    private val stillReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val result = ActivityTransitionResult.extractResult(intent) ?: return
            result.transitionEvents.forEach { event ->
                if (event.activityType == DetectedActivity.STILL) {
                    isStill = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
                }
            }
            applyLocationRequest()
        }
    }

    /**
     * Enchufar o quitar el cargador no mueve el móvil, así que no llega ningún fix y el grupo se
     * entera en el siguiente ping — que estando en casa cargando es justo el caso más lento (en
     * reposo, hasta 15 minutos). El aviso se manda con la última posición que YA se tenía: no se
     * enciende nada, solo viaja el estado del móvil pegado a ella.
     *
     * ponytail: eso deja un punto repetido en el historial por cada vez que se enchufa. Si
     * alguna vez molesta, el arreglo es un endpoint que mande solo estado, sin posición.
     */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            serviceScope.launch { pingDeviceStatus() }
        }
    }

    private suspend fun pingDeviceStatus() {
        val fix = lastFix ?: return
        runCatching {
            locator.locationRepository.ping(
                fix.latitude,
                fix.longitude,
                fix.accuracy,
                DeviceStatusUtils.read(applicationContext),
                locator.settings.locationFrequency.first(),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        locator = ServiceLocator.getInstance(applicationContext)
        // Dinámico y no del manifiesto: solo interesa mientras el servicio viva, y estas dos son
        // emisiones protegidas del sistema (por eso NOT_EXPORTED es lo correcto aquí).
        ContextCompat.registerReceiver(
            this,
            powerReceiver,
            IntentFilter(Intent.ACTION_POWER_CONNECTED).apply { addAction(Intent.ACTION_POWER_DISCONNECTED) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        serviceScope.launch {
            // Conectarse o salir de un wifi cambia el estado de reposo sin que llegue ninguna
            // ubicación: sin esto, salir de casa no se notaría hasta el siguiente fix (hasta 15
            // minutos después) en los móviles que denegaron el permiso de actividad.
            DeviceStatusUtils.wifiSsidFlow(applicationContext).collect {
                currentSsid = it
                applyLocationRequest()
            }
        }
        serviceScope.launch {
            locator.settings.knownWifiSsids.collect {
                knownWifiSsids = it
                applyLocationRequest()
            }
        }
        serviceScope.launch {
            // Crear o borrar una zona cambia el ritmo al instante: si no, quien acaba de crear
            // una zona en el colegio seguiría con el ritmo de antes hasta el siguiente fix.
            locator.zoneRepository.observeZones().collect {
                zones = it
                // Mismo momento para refrescar las geocercas del sistema: es la lista que
                // acaba de cambiar y mandarla de más no cuesta nada (ver ZoneGeofencing).
                ZoneGeofencing.refresh(applicationContext, it)
                applyLocationRequest()
            }
        }
        serviceScope.launch {
            // collectLatest: cada renovación cancela la espera anterior y vuelve a contar. El
            // corte lo pone también el propio móvil, para que un "deja de seguir" perdido no
            // deje a nadie en tiempo real para siempre.
            var wasLive = false
            LiveTracking.until.collectLatest { until ->
                val remaining = until - System.currentTimeMillis()
                applyLocationRequest()
                if (remaining <= 0) {
                    // En los modos que no mandan nada por su cuenta ("solo bajo demanda" y
                    // "deshabilitado") el servicio lo había levantado el propio seguimiento: al
                    // acabar no tiene nada que hacer aquí.
                    if (wasLive && configuredIntervalMs <= 0L) stopSelf()
                    wasLive = false
                    return@collectLatest
                }
                wasLive = true
                delay(remaining)
                LiveTracking.update(0)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationHelper.buildLocationServiceNotification(this)
        ServiceCompat.startForeground(
            this,
            Constants.LOCATION_SERVICE_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        serviceScope.launch {
            val frequency = locator.settings.locationFrequency.first()
            // El guardia va aquí y no en LocationServiceController.ensureStarted porque a este
            // servicio lo arrancan cinco sitios distintos (MainActivity, el NavHost, el
            // onboarding, el BootReceiver y el worker de respaldo cada 15 min): comprobarlo en el
            // propio servicio los cubre todos. La notificación asoma unos milisegundos antes de
            // pararse — startForeground tiene que salir ya, antes de leer el DataStore.
            // Cubre tanto "deshabilitado" como "solo bajo demanda": ninguno de los dos manda nada
            // por su cuenta, y el segundo responde desde el push, sin necesidad de servicio.
            // Salvo que alguien nos esté siguiendo en vivo: ahí el servicio hace falta aunque
            // el modo elegido no mande nada por su cuenta.
            if (!frequency.sendsPeriodicUpdates && !liveActive) {
                stopSelf()
                return@launch
            }
            configuredIntervalMs = frequency.intervalMs
            startActivityTransitions()
            applyLocationRequest()
        }
        return START_STICKY
    }

    /**
     * Quieto o en un wifi de los marcados como sitio fijo = la posición no está cambiando, así
     * que pedirla al ritmo elegido es tirar batería. Las dos señales van por separado a
     * propósito: quien deniegue el permiso de actividad sigue ahorrando por el wifi, y quien no
     * use wifi sigue ahorrando por el sensor.
     */
    private fun applyLocationRequest() {
        // Todo lo que decide el ritmo llega de fuera (un fix, un cambio de wifi, de zonas o de
        // seguimiento) menos una cosa: que NO pase nada. Un móvil encima de la mesa deja de
        // recibir fixes (los filtra el sistema por distancia), así que nadie volvería a entrar
        // aquí a darse cuenta de que lleva media hora parado. De ahí esta espera, que es lo que
        // trae el ahorro en el caso más común de todos. Es un delay, no una alarma: si el móvil
        // está en sueño profundo salta al despertar, y eso ya es bastante pronto.
        stillCheckJob?.cancel()
        if (liveActive) {
            idle = false
            // Sin ping de aviso: en vivo sale uno cada pocos segundos y el modo va en todos. Se
            // apunta para que al ACABAR el seguimiento se note el cambio y salga el aviso.
            announcedMode = UpdateMode.LIVE
            startLocationUpdates(LocationFrequency.REAL_TIME.intervalMs)
            return
        }
        if (configuredIntervalMs <= 0L) {
            // Sin modo periódico y sin seguimiento no hay nada que pedir: se llega aquí al
            // caducar el seguimiento de un móvil en "solo bajo demanda", justo antes de pararse.
            fusedClient.removeLocationUpdates(locationCallback)
            currentIntervalMs = -1
            return
        }
        val onKnownWifi = currentSsid?.let { it in knownWifiSsids } == true
        // Tres señales, cualquiera vale: el sensor de actividad, el wifi de casa y la posición
        // que no se mueve. Antes solo había dos, y un móvil con datos móviles cuyo dueño no
        // concedió el permiso de actividad (o simplemente lo va cogiendo cada poco) no entraba
        // nunca en reposo: se pasaba el día entero pidiendo posición al ritmo de moverse.
        val notMoving = isStill || stationary
        idle = notMoving || onKnownWifi
        UpdateMode.setIdle(still = notMoving, homeWifi = onKnownWifi)
        // El reposo deducido de la propia posición se despacha más corto que el confirmado por
        // el sensor o por el wifi: es el único que no puede enterarse EN EL ACTO de que el móvil
        // ha vuelto a arrancar (el sensor manda su transición de salida y el wifi avisa al
        // desconectarse; aquí hay que esperar al siguiente fix, y en reposo el siguiente fix
        // tarda). Cinco minutos acotan ese hueco y siguen siendo diez veces menos gasto.
        val idleInterval = if (isStill || onKnownWifi) IDLE_INTERVAL_MS else STATIONARY_INTERVAL_MS
        startLocationUpdates(if (idle) maxOf(configuredIntervalMs, idleInterval) else movingIntervalMs())
        // El modo viaja pegado a cada ping, y los cambios de modo son justo lo que deja de
        // mandar pings: sin este aviso el grupo se queda con el modo del último, viendo una hora
        // que no avanza — la pinta exacta de que algo se ha roto. Pasa al entrar en reposo y
        // también al SALIR de un seguimiento en vivo, que además esperaba pings cada pocos
        // segundos y pintaba "sin señal" al minuto. Va con la última posición conocida, no
        // enciende nada. El modo se calcula igual que [UpdateMode.forPing], que es quien lo
        // manda de verdad.
        val mode = when {
            onKnownWifi -> UpdateMode.HOME_WIFI
            notMoving -> UpdateMode.STILL
            else -> UpdateMode.MOVING
        }
        if (mode != announcedMode && lastFix != null) {
            announcedMode = mode
            serviceScope.launch { pingDeviceStatus() }
        }
        if (!idle) {
            stillCheckJob = serviceScope.launch {
                delay(STILL_AFTER_MS)
                applyLocationRequest()
            }
        }
    }

    /**
     * El ritmo de un móvil que se está moviendo, corregido por dos cosas que el reloj solo no
     * sabe: a qué velocidad va y si anda cerca del borde de una zona.
     *
     * Velocidad: pedir posición "cada 2 minutos" son 4 m andando y 2,5 km en coche. Lo que se
     * quiere de verdad es un punto cada tantos metros, así que el intervalo sale de dividir
     * [POINT_EVERY_METERS] entre la velocidad. Andando se estira, en coche se acorta, y el gasto
     * del día sale parecido porque en coche se pasa poco rato.
     *
     * Zonas: el GPS fino solo hace falta donde una posición mala cambia el resultado, que es el
     * borde de una zona (entrar/salir). Lejos de cualquier zona da igual fallar 100 m.
     */
    private fun movingIntervalMs(): Long {
        var interval = configuredIntervalMs
        // Nada de esto puede ir MÁS LENTO de lo que el usuario pidió en los ritmos rápidos
        // ("tiempo real", "cada 30 s"): ahí lo que se espera es ver el punto moverse, y
        // estirarlo sería desobedecer el ajuste. Solo se relaja de medio minuto en adelante.
        val relaxable = configuredIntervalMs >= MIN_MOVE_FROM_INTERVAL_MS
        if (relaxable && lastSpeed > MIN_SPEED_MPS) {
            val bySpeed = (POINT_EVERY_METERS / lastSpeed * 1000f).toLong()
            // El tope de arriba nunca pasa del reposo: por lento que vayas, el grupo ve hora
            // fresca cada cuarto de hora como mucho.
            interval = bySpeed.coerceIn(MIN_SPEED_INTERVAL_MS, minOf(configuredIntervalMs * 2, IDLE_INTERVAL_MS))
        }
        val toEdge = metersToNearestZoneEdge()
        if (toEdge != null) {
            // NEAR_ZONE_INTERVAL_MS no baja de MIN_MOVE_FROM_INTERVAL_MS a propósito: por debajo
            // de ese umbral el sistema enciende el GPS fino, deja de agrupar entregas y deja de
            // filtrar por distancia, las tres cosas a la vez. Y "cerca del borde" se mide en
            // valor absoluto, así que estar EN CASA cumple la condición — con 20 s aquí, un
            // móvil con una zona en casa se pasaba la tarde entera con el GPS fino encendido.
            if (toEdge <= NEAR_ZONE_METERS) {
                interval = minOf(interval, NEAR_ZONE_INTERVAL_MS)
            } else if (relaxable && toEdge >= FAR_ZONE_METERS) {
                // Lejos de todo se estira, pero nunca a más del doble de lo elegido: quien puso
                // "cada minuto" no espera enterarse cinco minutos después por estar en el campo.
                interval = maxOf(interval, minOf(FAR_ZONE_INTERVAL_MS, configuredIntervalMs * 2))
            }
        }
        return interval
    }

    /**
     * El móvil lleva [STILL_AFTER_MS] sin cambiar de sitio, lo diga o no el sensor de actividad.
     *
     * Es la señal de reposo de la que no se puede prescindir: el sensor necesita un permiso que
     * se puede denegar y se pierde en cuanto coges el móvil de la mesa, y el wifi de casa no
     * existe si se va con datos. Sin ninguna de las dos, "reposo" no se activaba nunca.
     */
    private val stationary: Boolean
        get() = stillAnchor != null && System.currentTimeMillis() - stillAnchorAt >= STILL_AFTER_MS

    /**
     * Mueve el ancla del detector solo cuando el móvil se ha ido de verdad: el listón es el
     * mismo ruido de fondo que ya se asume en reposo ([IDLE_MIN_MOVE_METERS]), porque ahí la
     * posición la ponen wifi y antenas y baila sola sin que nadie se mueva.
     *
     * Se recupera solo: en reposo el sistema ya no entrega nada hasta que el móvil se aleja
     * 100 m, y ese fix es justo el que mueve el ancla y devuelve el ritmo normal.
     *
     * Y el listón nunca baja del margen de error del propio fix: sin wifi la posición la ponen
     * las antenas y llega con cientos de metros de margen, así que un salto de 300 m entre dos
     * fixes de 800 m de error no es haberse movido, es la misma mesa vista desde otra antena.
     * Sin esto, un móvil con datos móviles no llegaba a declararse quieto NUNCA.
     */
    private fun trackStillness(fix: Location) {
        val anchor = stillAnchor
        if (anchor == null || fix.distanceTo(anchor) >= maxOf(IDLE_MIN_MOVE_METERS, fix.accuracy)) {
            stillAnchor = fix
            stillAnchorAt = System.currentTimeMillis()
        }
    }

    /**
     * Distancia al BORDE de zona más cercano, en valor absoluto: da igual estar 50 m fuera que
     * 50 m dentro, en los dos casos el siguiente paso puede ser una entrada o una salida. Estar
     * en el centro de una zona enorme cuenta como lejos, que es lo que es.
     *
     * Un grupo SIN zonas cuenta como lejos de todo, no como "no se sabe": si no hay ninguna
     * zona no hay ningún aviso que llegar tarde, así que merece el mismo ritmo relajado que
     * quien las tiene y está en el campo. Es el caso más común en un grupo recién creado.
     *
     * null = aún no ha llegado ningún fix con el que medir.
     */
    private fun metersToNearestZoneEdge(): Float? {
        if (zones.isEmpty()) return Float.MAX_VALUE
        val from = lastFix ?: return null
        val out = FloatArray(1)
        var best = Float.MAX_VALUE
        zones.forEach { zone ->
            Location.distanceBetween(from.latitude, from.longitude, zone.lat, zone.lng, out)
            val toEdge = kotlin.math.abs(out[0] - zone.radius_m.toFloat())
            if (toEdge < best) best = toEdge
        }
        return best
    }

    /**
     * Un móvil quieto encima de la mesa manda el mismo número de pings que uno cruzando la
     * ciudad, y cada ping deja la radio móvil en alta potencia unos segundos. Estando parado
     * basta con un latido de vez en cuando para que el grupo sepa que sigue ahí.
     *
     * El filtro solo se aplica de medio minuto en adelante: en tiempo real lo que se espera es
     * ver el punto moverse, aunque sea poco.
     */
    private fun worthSending(location: Location): Boolean {
        val previous = lastSent
        // En reposo la ubicación la ponen el wifi y las antenas, y eso baila solo hasta 100 m de
        // un fix al siguiente: con el listón en 25 m el mapa enseñaría paseos que no existen.
        val minMove = if (idle) IDLE_MIN_MOVE_METERS else MIN_MOVE_METERS
        val moved = previous == null || location.distanceTo(previous) >= minMove
        val worth = moved ||
            currentIntervalMs < MIN_MOVE_FROM_INTERVAL_MS ||
            location.time - previous.time >= IDLE_INTERVAL_MS
        if (!worth) return false
        if (tooVague(location)) return false
        if (implausibleJump(location)) return false
        lastSent = location
        lastSentAt = System.currentTimeMillis()
        // En los ritmos rápidos, entre un fix y el siguiente hay cuatro metros yendo
        // andando: ahí "no se ha movido" no significa nada y el reposo lo dice el sensor.
        if (currentIntervalMs >= MIN_MOVE_FROM_INTERVAL_MS) UpdateMode.setStationary(!moved)
        return true
    }

    /**
     * Un fix con cientos de metros de margen de error no se distingue en el mapa de uno bueno,
     * pero puede colocar a alguien en otro barrio — y si además dispara un aviso de zona, el
     * grupo recibe una entrada o salida que no ha ocurrido.
     *
     * El listón depende del modo: con el GPS en marcha, 200 m es un fix a medio cocer; en
     * reposo la posición la ponen wifi y antenas y 500 m es lo normal, así que ahí exigir más
     * sería no mandar nada nunca.
     */
    private fun tooVague(location: Location): Boolean {
        if (!location.hasAccuracy()) return false
        val limit = if (idle) MAX_ACCURACY_IDLE_METERS else MAX_ACCURACY_METERS
        if (location.accuracy <= limit) return false
        // Válvula de escape: tras un buen rato sin mandar nada (interior, sótano, mal día de
        // GPS), una posición mala es mejor que dejar al grupo con la hora congelada.
        return System.currentTimeMillis() - lastSentAt < IDLE_INTERVAL_MS
    }

    /**
     * Un fix de wifi mal resuelto puede plantar a alguien a kilómetros de donde está (un router
     * que cambió de ciudad, una antena mal ubicada en la base de datos) y encima declarar buena
     * precisión, así que [tooVague] no lo pilla. Lo que sí lo delata es que para llegar ahí
     * habría hecho falta ir a 300 km/h.
     *
     * No se puede quedar atascado: si el salto fuera real, el siguiente fix vendrá con más
     * segundos de diferencia y la velocidad que sale de la cuenta ya será normal.
     */
    private fun implausibleJump(location: Location): Boolean {
        val previous = lastSent ?: return false
        val seconds = (location.time - previous.time) / 1000.0
        if (seconds <= 0) return false
        val distance = location.distanceTo(previous)
        if (distance < MIN_JUMP_METERS) return false
        return distance / seconds > MAX_PLAUSIBLE_SPEED_MPS
    }

    private fun startLocationUpdates(intervalMs: Long) {
        if (!worthReRequesting(intervalMs)) return
        currentIntervalMs = intervalMs

        // Con fixes cada pocos segundos el GPS no llega a apagarse entre uno y otro, así que la
        // precisión máxima ya se está pagando igual. De medio minuto en adelante, ubicar por wifi
        // y antenas (~100 m) cuesta una fracción y sobra para ver dónde anda alguien.
        val priority = if (intervalMs < MIN_MOVE_FROM_INTERVAL_MS) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        // Cuanto se ha tenido que mover el móvil para que el sistema nos entregue un fix. Es el
        // mismo listón que aplica [worthSending] a mano, pero puesto donde de verdad ahorra: así
        // el proceso ni se despierta para los fixes que iba a tirar. El latido de "sigo aquí"
        // no se pierde por esto: lo manda LocationUpdateWorker cada 15 min.
        val minDistance = if (intervalMs >= MIN_MOVE_FROM_INTERVAL_MS) {
            if (idle) IDLE_MIN_MOVE_METERS else MIN_MOVE_METERS
        } else {
            0f
        }

        val request = LocationRequest.Builder(priority, intervalMs)
            // Si otra app (Maps, el navegador del coche) ya tiene el GPS encendido, el sistema
            // está calculando posiciones de todas formas: esto dice "si las hay hechas, dámelas",
            // y sale gratis porque el gasto ya lo está pagando la otra app. Sin esto se tiran.
            .setMinUpdateIntervalMillis(intervalMs / 3)
            .setMinUpdateDistanceMeters(minDistance)
            // El sistema acumula los fixes y los entrega en bloque: la radio móvil despierta una
            // vez en lugar de tres. El tope es fijo y corto ([MAX_BATCH_DELAY_MS]) y nunca pasa
            // de lo que falta para completar [IDLE_INTERVAL_MS] — en reposo, donde el intervalo
            // ya es de 15 min, el lote sale sobrando.
            .setMaxUpdateDelayMillis(
                if (intervalMs >= MIN_MOVE_FROM_INTERVAL_MS) {
                    minOf(MAX_BATCH_DELAY_MS, IDLE_INTERVAL_MS - intervalMs).coerceAtLeast(0L)
                } else {
                    0L
                },
            )
            .build()

        fusedClient.removeLocationUpdates(locationCallback)
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    /**
     * Rehacer la petición NO es gratis: [startLocationUpdates] retira la que había y pide otra,
     * y eso tira la sesión de satélites en marcha y reinicia el lote de entregas. El ritmo por
     * velocidad da un número distinto en casi cada fix ([movingIntervalMs]), así que yendo por
     * la calle se rehacía la petición cada vez que llegaba una posición: el receptor no llegaba
     * nunca a la parte barata del ciclo.
     *
     * Así que un cambio pequeño no se aplica, se espera al siguiente. Lo que sí se aplica
     * siempre es cruzar [MIN_MOVE_FROM_INTERVAL_MS], porque ahí no cambia solo el ritmo:
     * cambian la prioridad, el filtro de distancia y el lote.
     */
    private fun worthReRequesting(intervalMs: Long): Boolean {
        val current = currentIntervalMs
        if (current <= 0L) return true
        if (current == intervalMs) return false
        if ((current < MIN_MOVE_FROM_INTERVAL_MS) != (intervalMs < MIN_MOVE_FROM_INTERVAL_MS)) return true
        return maxOf(current, intervalMs).toFloat() / minOf(current, intervalMs) >= INTERVAL_CHANGE_RATIO
    }

    private fun startActivityTransitions() {
        if (transitionsRegistered || !PermissionUtils.hasActivityRecognitionPermission(this)) return

        // Receptor dinámico y no del manifiesto: solo tiene sentido mientras el servicio viva, y
        // así se va con él sin dejar nada registrado en el sistema.
        ContextCompat.registerReceiver(
            this,
            stillReceiver,
            IntentFilter(ACTION_STILL_TRANSITION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        transitionsRegistered = true

        val request = ActivityTransitionRequest(
            listOf(
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.STILL)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.STILL)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build(),
            ),
        )
        try {
            ActivityRecognition.getClient(this)
                .requestActivityTransitionUpdates(request, transitionPendingIntent())
        } catch (e: SecurityException) {
            // Permiso revocado entre la comprobación y esta línea: se sigue sin la optimización.
        }
    }

    /** MUTABLE porque Play Services escribe el resultado de la transición dentro del intent. */
    private fun transitionPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        0,
        Intent(ACTION_STILL_TRANSITION).setPackage(packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    override fun onDestroy() {
        fusedClient.removeLocationUpdates(locationCallback)
        runCatching { unregisterReceiver(powerReceiver) }
        if (transitionsRegistered) {
            runCatching {
                ActivityRecognition.getClient(this).removeActivityTransitionUpdates(transitionPendingIntent())
            }
            unregisterReceiver(stillReceiver)
            transitionsRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val ACTION_STILL_TRANSITION = "com.dskmusic.lokate.action.STILL_TRANSITION"

        /** Lo más que el sistema puede retrasar la entrega de un fix para juntarlo con los
         * siguientes. Fijo y corto a propósito: proporcional al intervalo (que es lo que hacía
         * antes) dejaba a quien tenía "cada 2 minutos" con hasta seis minutos de retraso yendo
         * por la calle, que es justo cuando la posición importa. */
        const val MAX_BATCH_DELAY_MS = 60_000L

        /** A partir de qué intervalo se considera modo ahorro: precisión equilibrada, entrega en
         * bloque y filtro de movimiento. */
        const val MIN_MOVE_FROM_INTERVAL_MS = 30_000L

        /** Cuánto hay que haberse movido para que un fix merezca un ping en modo ahorro. Por
         * debajo de esto suele ser ruido del propio GPS, no un desplazamiento real. */
        const val MIN_MOVE_METERS = 25f

        /** Lo mismo pero en reposo, donde la posición viene de wifi y antenas y el margen de
         * error propio ya ronda los 100 m. */
        const val IDLE_MIN_MOVE_METERS = 100f

        /** Margen de error a partir del cual un fix no se manda (ver [tooVague]). */
        const val MAX_ACCURACY_METERS = 200f
        const val MAX_ACCURACY_IDLE_METERS = 500f

        /** Velocidad por encima de la cual un salto entre dos fixes es imposible, y el salto
         * mínimo para molestarse en mirarlo (ver [implausibleJump]). 83 m/s = 300 km/h: por
         * encima de eso o es un fix falso o vas en avión, y en avión da igual. */
        const val MAX_PLAUSIBLE_SPEED_MPS = 83f
        const val MIN_JUMP_METERS = 1_000f

        /** Cada cuántos metros se quiere un punto cuando el móvil se mueve, y la velocidad por
         * debajo de la cual no merece la pena hacer la cuenta (0,8 m/s = paso muy lento). */
        const val POINT_EVERY_METERS = 100f
        const val MIN_SPEED_MPS = 0.8f

        /** Lo más rápido que el ritmo por velocidad puede pedir posición. No baja de
         * [MIN_MOVE_FROM_INTERVAL_MS]: por debajo se enciende el GPS fino sin agrupar entregas,
         * y eso en un trayecto largo en coche es media batería. El precio es un punto cada 30 s
         * en vez de cada 20 en carretera; para ver el punto moverse de verdad están "tiempo
         * real" y el seguimiento en vivo, que sí encienden el GPS fino. */
        const val MIN_SPEED_INTERVAL_MS = 30_000L

        /** Cerca del borde de una zona: ritmo corto (y con él, GPS fino). Lejos de todas:
         * ritmo largo, porque ahí fallar cien metros no cambia nada. */
        const val NEAR_ZONE_METERS = 300f
        const val NEAR_ZONE_INTERVAL_MS = 30_000L
        const val FAR_ZONE_METERS = 1_000f
        const val FAR_ZONE_INTERVAL_MS = 5 * 60_000L

        /** El ritmo de "aquí no pasa nada", que vale para tres cosas a la vez: cada cuánto se
         * pide ubicación con el móvil quieto o en casa, cada cuánto se manda un ping aunque no se
         * haya movido (para que el grupo vea hora fresca) y el tope de lo que se puede retrasar
         * un lote. Subirlo ahorra más y enseña posiciones más viejas. */
        const val IDLE_INTERVAL_MS = 15 * 60_000L

        /** Cuánto tiene que llevar el móvil en el mismo sitio para darlo por parado sin ayuda
         * del sensor de actividad (ver [stationary]). Cinco minutos = dos o tres fixes del ritmo
         * normal: suficiente para no confundir un semáforo en rojo con estar en casa, y bastante
         * antes de que el resto del grupo empiece a dar por perdido a un móvil que solo está
         * quieto (ese listón lo pone UpdateStatus.kt, que copia este número). */
        const val STILL_AFTER_MS = 5 * 60_000L

        /** El ritmo de ese reposo deducido, más corto que [IDLE_INTERVAL_MS] porque nadie va a
         * avisar de que el móvil ha echado a andar: es el propio fix el que tiene que notarlo. */
        const val STATIONARY_INTERVAL_MS = 5 * 60_000L

        /** Cuánto tiene que cambiar el intervalo para que compense rehacer la petición al
         * sistema (ver [worthReRequesting]). 1,5 = de 60 s a 90 s sí, de 60 a 75 no. */
        const val INTERVAL_CHANGE_RATIO = 1.5f
    }
}
