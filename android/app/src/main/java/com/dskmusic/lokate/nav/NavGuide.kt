package com.dskmusic.lokate.nav

import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.dto.RouteStepDto
import com.dskmusic.lokate.ui.map.PlannedRoute
import com.dskmusic.lokate.ui.map.TravelMode
import kotlin.math.roundToInt
import org.osmdroid.util.GeoPoint

/** Cuántas indicaciones se quieren oír. */
enum class NavVerbosity(val label: Int) {
    MINIMAL(R.string.nav_verbosity_minimal),
    NORMAL(R.string.nav_verbosity_normal),
    ABUNDANT(R.string.nav_verbosity_abundant),
    ;

    /**
     * A cuántos metros de cada maniobra se avisa, de lejos a cerca. En coche los avisos van
     * mucho más adelantados por lo obvio: a 90 km/h, 100 metros son cuatro segundos.
     *
     * Estos números son el mando de este asunto. Si una indicación llega tarde, lo que hay que
     * tocar es esta tabla y nada más.
     */
    fun thresholds(car: Boolean): IntArray = when (this) {
        // Las justas: un aviso por maniobra y punto.
        MINIMAL -> if (car) intArrayOf(200) else intArrayOf(30)
        NORMAL -> if (car) intArrayOf(500, 100) else intArrayOf(120, 25)
        ABUNDANT -> if (car) intArrayOf(1_000, 400, 150, 40) else intArrayOf(250, 80, 20)
    }
}

/** Por debajo de esto no se dice "dentro de X metros": se dice la maniobra sola, que es lo que
 * hace falta oír cuando ya la tienes encima. */
private fun nowMeters(car: Boolean) = if (car) 120 else 30

/** A cuánto de la línea se considera que te has salido, y cuánto se aguanta así antes de
 * recalcular. El aguante está para los bandazos del GPS entre edificios altos y para los túneles:
 * sin él, una ruta urbana se recalcularía sola cada dos manzanas. */
private const val OFF_ROUTE_CAR_M = 45.0
private const val OFF_ROUTE_FOOT_M = 30.0
private const val OFF_ROUTE_GRACE_MS = 8_000L

/** Pasada la maniobra por este margen, la siguiente pasa a ser la próxima. */
private const val STEP_PASSED_M = 8.0

/** Se da por llegado a esta distancia del final. El GPS no afina más que esto, y seguir
 * pidiendo precisión a un dato que no la tiene es cómo se consigue no anunciar nunca la
 * llegada. */
private const val ARRIVE_M = 20.0

/** Lo que se le cuenta a la pantalla en cada posición. */
data class NavState(
    val instruction: String,
    val street: String,
    /** Código de maniobra de ORS; decide la flecha (ver [maneuverIcon]). */
    val maneuver: Int,
    val metersToManeuver: Int,
    /** La maniobra de después, y solo con la de ahora encima: dos giros seguidos se toman de
     * otra manera si se sabe antes de hacer el primero. Vacía el resto del tiempo. */
    val nextInstruction: String,
    val remainingMeters: Int,
    val remainingSeconds: Int,
    /** Qué paso de la lista es el siguiente, para resaltarlo. */
    val stepIndex: Int,
    val offRoute: Boolean,
    val arrived: Boolean,
    val simulating: Boolean,
    val muted: Boolean,
    val recalculating: Boolean,
    val position: GeoPoint?,
)

/** Lo que hay que decir en voz alta. Sin texto montado a propósito: las palabras las pone quien
 * tiene el Context y los strings traducidos (ver [NavigationService]). */
sealed interface NavSpeech {
    /** Arranque: la primera indicación y de cuánto va el viaje. */
    data class Start(val instruction: String, val meters: Int, val minutes: Int) : NavSpeech

    /** Una maniobra. [now] = pégate ya, se dice la instrucción sin "dentro de". */
    data class Maneuver(val meters: Int, val instruction: String, val now: Boolean) : NavSpeech

    data object Arrived : NavSpeech

    data object Rerouting : NavSpeech
}

/** Lo que sale de cada posición: el estado para pintar, lo que haya que decir y si toca pedir
 * una ruta nueva. */
data class NavUpdate(
    val state: NavState,
    val speech: List<NavSpeech>,
    val reroute: Boolean,
)

/**
 * El motor de la navegación: come posiciones y suelta "dentro de 300 metros, gire a la derecha".
 *
 * No habla, no pinta y no pide rutas — solo decide. Así es lo único de la navegación que se
 * puede probar entero con el simulador, que es justamente lo que se hace: el simulador le da
 * posiciones falsas por la línea y de aquí tiene que salir, en orden y una sola vez cada una,
 * la misma retahíla que saldría conduciendo.
 */
class NavGuide(val route: PlannedRoute, var verbosity: NavVerbosity) {

    private val track = RouteTrack(route.points)
    private val steps: List<RouteStepDto> = route.steps
    private val car = route.mode == TravelMode.CAR

    /** La maniobra que toca ahora. */
    private var stepIndex = 0
    private var index = 0

    /** Avisos ya dados, como stepIndex * [THRESHOLD_SLOTS] + posición del umbral. Es lo que
     * evita que a 3 fixes por segundo el móvil repita "gire a la derecha" doce veces. */
    private val spoken = HashSet<Int>()
    private var offRouteSinceMs = 0L
    private var arrived = false
    private var started = false
    private var recalculating = false

    val totalMeters: Double get() = track.totalMeters

    init {
        // El último paso de ORS es siempre "ha llegado a su destino". Se queda en la lista para
        // pintarlo, pero sus avisos se dan por dados: la llegada la anuncia [NavSpeech.Arrived]
        // a los metros que toca, y si no se taparía con esto.
        if (steps.isNotEmpty()) silence(steps.lastIndex)
    }

    /** Lo que se está diciendo mientras se pide una ruta nueva, para que la pantalla lo cuente. */
    fun markRecalculating(active: Boolean) {
        recalculating = active
    }

    /** El punto donde está la simulación cuando lleva [meters] recorridos. */
    fun simulatedPoint(meters: Double): GeoPoint? = track.pointAt(meters)

    fun update(lat: Double, lng: Double, nowMs: Long, simulating: Boolean, muted: Boolean): NavUpdate {
        val progress = track.snap(lat, lng, index)
        index = progress.index

        // ¿Fuera de ruta? Solo cuenta si lleva un rato así (ver [OFF_ROUTE_GRACE_MS]).
        val limit = if (car) OFF_ROUTE_CAR_M else OFF_ROUTE_FOOT_M
        if (progress.offRouteMeters > limit) {
            if (offRouteSinceMs == 0L) offRouteSinceMs = nowMs
        } else {
            offRouteSinceMs = 0L
        }
        val lost = offRouteSinceMs != 0L && nowMs - offRouteSinceMs >= OFF_ROUTE_GRACE_MS

        // La maniobra de un paso se hace al llegar a su punto: pasado ese punto, la siguiente.
        while (stepIndex < steps.lastIndex &&
            progress.metersFromStart > track.metersTo(steps[stepIndex].point_index) + STEP_PASSED_M
        ) {
            stepIndex++
        }

        val step = steps.getOrNull(stepIndex)
        val toManeuver = step
            ?.let { (track.metersTo(it.point_index) - progress.metersFromStart).coerceAtLeast(0.0) }
            ?: 0.0
        val remaining = (track.totalMeters - progress.metersFromStart).coerceAtLeast(0.0)

        val speech = mutableListOf<NavSpeech>()

        // El arranque: la primera indicación y de cuánto va el viaje, una sola vez.
        if (!started) {
            started = true
            steps.firstOrNull()?.let { primero ->
                silence(0)
                speech += NavSpeech.Start(
                    primero.instruction,
                    track.totalMeters.roundToInt(),
                    (route.seconds / 60).roundToInt(),
                )
            }
        } else if (step != null && !arrived && !lost) {
            aviso(toManeuver)?.let { speech += it }
        }

        if (!arrived && remaining <= ARRIVE_M) {
            arrived = true
            speech += NavSpeech.Arrived
        }

        // La de después solo cuando la de ahora está encima, y nunca el "ha llegado" final:
        // ese ya lo canta la llegada y aquí sobraría.
        val luego = if (toManeuver > nowMeters(car) || stepIndex + 1 >= steps.lastIndex) "" else {
            steps[stepIndex + 1].instruction
        }

        val state = NavState(
            instruction = step?.instruction.orEmpty(),
            street = step?.name.orEmpty(),
            maneuver = step?.type ?: -1,
            metersToManeuver = toManeuver.roundToInt(),
            nextInstruction = luego,
            remainingMeters = remaining.roundToInt(),
            // El tiempo que falta, a la parte de la ruta que queda. El servicio de rutas ya
            // contó con las calles por las que se pasa; repartirlo proporcionalmente conserva
            // esa cuenta mejor que inventar una velocidad media.
            remainingSeconds = if (track.totalMeters <= 0) 0 else
                (route.seconds * remaining / track.totalMeters).roundToInt(),
            stepIndex = stepIndex,
            offRoute = lost,
            arrived = arrived,
            simulating = simulating,
            muted = muted,
            recalculating = recalculating,
            position = GeoPoint(lat, lng),
        )
        return NavUpdate(state, speech, reroute = lost && !simulating && !arrived)
    }

    /**
     * El aviso que toca a [toManeuver] metros de la maniobra, o null si ese ya se dio.
     *
     * Coge el umbral más pequeño de los que todavía quedan por delante y no el más grande
     * cumplido: si entre dos posiciones se pasa de 600 a 90 metros (un túnel, un semáforo en el
     * que el GPS se pierde), lo que hay que oír es "gire a la derecha" y no "dentro de 500
     * metros". Los umbrales mayores se marcan como dados para que no salgan después.
     */
    private fun aviso(toManeuver: Double): NavSpeech? {
        val thresholds = verbosity.thresholds(car)
        for (k in thresholds.indices.reversed()) {
            if (thresholds[k] < toManeuver) continue
            val key = stepIndex * THRESHOLD_SLOTS + k
            if (key in spoken) return null
            for (j in 0..k) spoken.add(stepIndex * THRESHOLD_SLOTS + j)
            val instruction = steps.getOrNull(stepIndex)?.instruction.orEmpty()
            // Los metros que se dicen son los de verdad, redondeados a algo pronunciable, no el
            // umbral: entre dos posiciones se avanza, y "dentro de 500" a 430 metros suena mal.
            return NavSpeech.Maneuver(
                meters = redondo(toManeuver),
                instruction = instruction,
                now = thresholds[k] <= nowMeters(car),
            )
        }
        return null
    }

    /** Da por dados todos los avisos de ese paso. */
    private fun silence(step: Int) {
        for (j in 0 until THRESHOLD_SLOTS) spoken.add(step * THRESHOLD_SLOTS + j)
    }

    private companion object {
        /** Huecos de umbral reservados por paso al codificar [spoken]. Más que los de la
         * verbosidad más charlatana, para que cambiar de verbosidad a mitad de ruta no haga que
         * dos pasos distintos compartan clave. */
        const val THRESHOLD_SLOTS = 8
    }
}

/** 420 → 400, 85 → 90, 30 → 30. Nadie dice "dentro de cuatrocientos veintitrés metros". */
private fun redondo(meters: Double): Int = when {
    meters >= 300 -> (meters / 100).roundToInt() * 100
    meters >= 50 -> (meters / 10).roundToInt() * 10
    else -> meters.roundToInt()
}
