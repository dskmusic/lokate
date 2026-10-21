package com.dskmusic.lokate.ui.map

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dskmusic.lokate.R
import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.data.remote.dto.RouteStepDto
import com.dskmusic.lokate.data.repository.LocationRepository
import com.dskmusic.lokate.ui.common.cabe
import com.dskmusic.lokate.util.distanceMeters
import com.dskmusic.lokate.util.formatDistance
import com.dskmusic.lokate.util.formatDuration
import com.dskmusic.lokate.util.formatRelativeTime
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/** Morado: ni el naranja de la regla, ni el azul de las zonas, ni ninguno de los colores que se
 * pueden elegir para los círculos de precisión. Y se ve igual de bien sobre satélite que sobre
 * el mapa oscuro, que es donde el verde desaparece. */
internal const val DIRECTIONS_COLOR = 0xFF7C4DFF.toInt()

/** Cada cuánto se le pregunta al servidor si el móvil del otro ya ha mandado su posición, y
 * cuánto se espera antes de darlo por perdido. Los mismos que la ficha de un miembro, que hace
 * esto exacto desde su botón de pedir ubicación. */
private const val POSITION_POLL_MS = 2_500L
private const val POSITION_TIMEOUT_MS = 20_000L

/** Cuánto se espera callado antes de ofrecer la última posición conocida. Con el GPS encendido
 * la respuesta llega en el primer sondeo; cuando no llega, esperar veinte segundos mirando una
 * ruedecita para que al final pregunte lo mismo es tiempo tirado. Se pregunta a los seis y se
 * sigue sondeando por detrás: si contesta mientras tanto, se traza con la buena y el cuadro se
 * va solo. */
private const val STALE_OFFER_MS = 6_000L

enum class TravelMode(val api: String, val label: Int, val icon: ImageVector) {
    FOOT("foot", R.string.route_mode_foot, Icons.Filled.DirectionsWalk),
    CAR("car", R.string.route_mode_car, Icons.Filled.DirectionsCar),
}

/** Una ruta ya trazada y lista para pintar. [staleAt] es la hora de la posición de la que sale
 * cuando no se ha podido conseguir una fresca: se enseña en la tarjeta, porque una ruta que
 * empieza donde alguien estaba hace dos horas no es la ruta que parece. */
data class PlannedRoute(
    val points: List<GeoPoint>,
    val meters: Double,
    val seconds: Double,
    val memberName: String,
    val mode: TravelMode,
    val staleAt: String? = null,
    /** Quién hace el camino. Hace falta para una cosa: la navegación por voz solo se ofrece si
     * ese alguien es quien tiene el móvil en la mano (ver RouteStepsDialog). Guiar por voz a
     * otro desde aquí no tiene ningún sentido. */
    val memberId: String = "",
    /** Las maniobras. Vacía = se puede ver y compartir, pero no navegar (ver [RouteStepDto]). */
    val steps: List<RouteStepDto> = emptyList(),
    /** Quién la ha trazado: el principal o el suplente. Vacío = servidor de antes de que esto
     * se mandara, y entonces no se dice nada. */
    val provider: String = "",
)

/**
 * El "cómo llegar": quién va, a pie o en coche, y la ruta dibujada en el mapa.
 *
 * Antes de trazar nada le pide la posición de verdad al móvil de quien va a hacer el camino
 * (push silencioso, igual que el botón de la ficha). Si ese móvil no contesta no se traza a la
 * callada: se pregunta si vale la última posición conocida, con su antigüedad delante. Y si no
 * hay ruta —sin cobertura, sin cuota o sin camino entre los dos puntos— se dice que no hay. No
 * se dibuja una línea recta: una recta que cruza un río no es un camino.
 */
/**
 * Por qué no se ha podido trazar. Tres motivos y tres avisos, porque lo que puede hacer el
 * usuario es distinto en cada uno: buscar otro destino, esperar un rato, o encender los datos.
 * Antes era un sí/no y el aviso lo decía todo a la vez, que es lo mismo que no decir nada.
 */
enum class RouteFailure(val title: Int, val body: Int) {
    /** 404: ORS dice que entre esos dos puntos no hay camino, o no hay calle cerca de uno. */
    NO_PATH(R.string.route_failed_title, R.string.route_failed_body),

    /** 502: el servidor de rutas no está disponible (ORS caído, sin clave, cuota agotada). */
    UNAVAILABLE(R.string.route_unavailable_title, R.string.route_unavailable_body),

    /** No se ha llegado ni al servidor: este móvil no tiene internet. */
    OFFLINE(R.string.route_offline_title, R.string.error_no_connection),
}

/** Con qué se ha caído la petición → qué aviso toca. */
private fun routeFailure(error: Throwable?): RouteFailure = when {
    error is HttpException && error.code() == 404 -> RouteFailure.NO_PATH
    // IOException de Retrofit es "no he llegado al servidor": sin datos, sin wifi, DNS caído.
    error is IOException -> RouteFailure.OFFLINE
    // Sin excepción y sin puntos: ORS contestó una ruta de menos de dos puntos, que pasa con el
    // origen y el destino en el mismo portal. No es una avería.
    error == null -> RouteFailure.NO_PATH
    // 502 nuestro, y cualquier otra cosa rara.
    else -> RouteFailure.UNAVAILABLE
}

class RoutePlanner(
    private val scope: CoroutineScope,
    private val locations: LocationRepository,
) {
    /** A dónde se quiere llegar. No null = el planificador está abierto. */
    var destination by mutableStateOf<GeoPoint?>(null)
        private set

    /** El grupo ordenado por cercanía a [destination], lo más cerca arriba. */
    var candidates by mutableStateOf<List<LocationDto>>(emptyList())
        private set

    /** Lo que tarda cada uno en llegar de verdad, por user_id, cuando el servidor lo ha podido
     * decir (ver backend routing.matrix). Vacío = la lista va por línea recta, como antes. */
    var travelSeconds by mutableStateOf<Map<String, Double>>(emptyMap())
        private set

    /** Elegido, esperando a que diga a pie o en coche. */
    var chosen by mutableStateOf<LocationDto?>(null)
        private set

    /** Texto del "espere" mientras se pide la posición o se traza. null = nada en marcha. */
    var busy by mutableStateOf<Int?>(null)
        private set

    /** Su móvil no ha contestado: esta es la última posición que hay, y se pregunta si vale. */
    var stale by mutableStateOf<LocationDto?>(null)
        private set

    /** Por qué no hay ruta. null = no ha fallado nada. */
    var failure by mutableStateOf<RouteFailure?>(null)
        private set

    /** La ruta dibujada. Mientras no sea null, la línea y la tarjeta están en pantalla. */
    var route by mutableStateOf<PlannedRoute?>(null)
        private set

    private var mode = TravelMode.FOOT
    private var job: Job? = null

    /** Abre la lista de quién va, ordenada por cercanía al punto que se ha pulsado. */
    fun start(point: GeoPoint, members: List<LocationDto>) {
        clear()
        destination = point
        candidates = members.sortedBy {
            distanceMeters(it.lat, it.lng, point.latitude, point.longitude)
        }
        // La lista se enseña YA, por línea recta, y se recoloca sola un momento después con los
        // minutos de verdad. Al revés —esperar al servidor para pintar la lista— sería hacer
        // esperar a todo el mundo mirando una ruedecita solo por el orden.
        job = scope.launch { ordenarPorTiempo(point, candidates) }
    }

    /**
     * Reordena la lista por lo que se tarda de verdad en llegar, no por la recta: el que está al
     * otro lado del río sale detrás del que está más lejos pero con puente.
     *
     * Una sola petición para todos (una matriz, ver backend routing.matrix). Si no se puede, no
     * pasa nada: la lista se queda como estaba, que es como estaba antes de esto.
     *
     * ponytail: se mide con el último modo usado ([mode], a pie la primera vez), porque aquí
     * todavía no se ha elegido a pie o en coche — eso se pregunta después de elegir a quién. Los
     * dos ordenan casi igual (los dos van por calles); lo que arregla esto es la recta, no la
     * diferencia entre andar y conducir.
     */
    private suspend fun ordenarPorTiempo(to: GeoPoint, lista: List<LocationDto>) {
        if (lista.size < 2) return
        val tiempos = locations.travelTimes(
            lista.map { it.lat to it.lng }, to.latitude, to.longitude, mode.api,
        )?.durations_s ?: return
        if (tiempos.size != lista.size || destination != to) return
        // Los null son los que no llegan (otra isla, otro continente): se quedan al final de la
        // lista, que no es lo mismo que tardar cero.
        val porId = lista.indices.mapNotNull { i -> tiempos[i]?.let { lista[i].user_id to it } }.toMap()
        if (porId.isEmpty()) return
        travelSeconds = porId
        candidates = lista.sortedBy { porId[it.user_id] ?: Double.MAX_VALUE }
    }

    fun choose(member: LocationDto) {
        chosen = member
    }

    /** Ya está todo elegido: se le pide su posición de ahora y, con ella, la ruta. */
    fun go(travelMode: TravelMode) {
        val member = chosen ?: return
        val to = destination ?: return
        mode = travelMode
        chosen = null
        busy = R.string.route_requesting_position
        job = scope.launch {
            // Sin esperar al final: a los seis segundos se pregunta ya si vale la última conocida,
            // mientras el sondeo sigue. Aquí no se decide por nadie — una ruta que sale de donde
            // alguien ya no está no es la ruta que parece —, solo se pregunta antes.
            val oferta = launch {
                delay(STALE_OFFER_MS)
                busy = null
                stale = member
            }
            val fresh = freshPosition(member)
            oferta.cancel()
            if (fresh == null) {
                busy = null
                stale = member
                return@launch
            }
            // Ha contestado: el cuadro de la última conocida, si estaba puesto, sobra.
            stale = null
            trace(fresh, to, staleAt = null)
        }
    }

    /** "Sí, usa la última conocida". */
    fun useLastKnown() {
        val member = stale ?: return
        val to = destination ?: return
        // El sondeo puede seguir vivo (se pregunta antes de que se agote): si no se para, una
        // respuesta tardía trazaría otra ruta encima de esta.
        job?.cancel()
        stale = null
        job = scope.launch { trace(member, to, staleAt = member.timestamp) }
    }

    /** Cierra lo que haya abierto y para lo que esté en marcha, sin borrar la ruta ya dibujada. */
    fun cancel() {
        job?.cancel()
        job = null
        destination = null
        candidates = emptyList()
        travelSeconds = emptyMap()
        chosen = null
        busy = null
        stale = null
        failure = null
    }

    /** Quita la ruta del mapa. */
    fun clear() {
        cancel()
        route = null
    }

    private suspend fun trace(from: LocationDto, to: GeoPoint, staleAt: String?) {
        busy = R.string.route_calculating
        val attempt = runCatching {
            locations.route(from.lat, from.lng, to.latitude, to.longitude, mode.api)
        }
        // runCatching se traga también la cancelación: sin esto, cerrar el planificador mientras
        // traza soltaba el aviso de error de una ruta que ya no interesa a nadie.
        (attempt.exceptionOrNull() as? CancellationException)?.let { throw it }
        busy = null
        val result = attempt.getOrNull()
        // Menos de dos puntos no es una línea: se trata igual que no haber podido trazarla.
        val points = result?.points?.takeIf { it.size > 1 }?.map { GeoPoint(it[0], it[1]) }
        if (points == null) {
            failure = routeFailure(attempt.exceptionOrNull())
            return
        }
        route = PlannedRoute(
            points = points,
            meters = result.distance_m,
            seconds = result.duration_s,
            memberName = from.display_name,
            mode = mode,
            staleAt = staleAt,
            provider = result.provider,
            memberId = from.user_id,
            steps = result.steps,
        )
        destination = null
        candidates = emptyList()
    }

    /**
     * Le pide por push una posición nueva a ese móvil y sondea hasta que llegue una con otra
     * hora, o hasta [POSITION_TIMEOUT_MS]. Devuelve null si no llega: sin cobertura, el móvil
     * apagado, o la app muerta allí.
     */
    private suspend fun freshPosition(member: LocationDto): LocationDto? {
        // ponytail: sondeo a secas. El servidor podría avisar por push al llegar la posición y
        // ahorrarse las vueltas, pero son dos o tres peticiones de nada y ya funciona.
        if (runCatching { locations.requestLocation(member.user_id) }.isFailure) return null
        val deadline = System.currentTimeMillis() + POSITION_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(POSITION_POLL_MS)
            val latest = runCatching { locations.memberLatest(member.user_id) }.getOrNull()
            if (latest != null && latest.timestamp != member.timestamp) return latest
        }
        return null
    }
}

@Composable
fun rememberRoutePlanner(locations: LocationRepository): RoutePlanner {
    val scope = rememberCoroutineScope()
    return remember { RoutePlanner(scope, locations) }
}

/** Los cuadros del planificador: a quién, cómo, el "espere", y lo que haya salido mal. */
@Composable
fun RoutePlannerDialogs(planner: RoutePlanner) {
    val busy = planner.busy
    val stale = planner.stale
    val chosen = planner.chosen

    if (busy != null) {
        AlertDialog(
            onDismissRequest = planner::cancel,
            title = { Text(stringResource(busy)) },
            text = { CircularProgressIndicator(Modifier.size(28.dp)) },
            // Pedirle la posición a otro móvil puede tardar veinte segundos: tiene que poder
            // cortarse sin esperar a que se agote.
            confirmButton = {
                TextButton(onClick = planner::cancel) { Text(stringResource(R.string.cancel)) }
            },
        )
        return
    }

    if (stale != null) {
        AlertDialog(
            onDismissRequest = planner::cancel,
            title = { Text(stringResource(R.string.route_stale_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.route_stale_body,
                        stale.display_name,
                        formatRelativeTime(stale.timestamp),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = planner::useLastKnown) {
                    Text(stringResource(R.string.route_stale_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = planner::cancel) { Text(stringResource(R.string.cancel)) }
            },
        )
        return
    }

    val failure = planner.failure
    if (failure != null) {
        AlertDialog(
            onDismissRequest = planner::cancel,
            title = { Text(stringResource(failure.title)) },
            text = { Text(stringResource(failure.body)) },
            confirmButton = {
                TextButton(onClick = planner::cancel) { Text(stringResource(R.string.close)) }
            },
        )
        return
    }

    val destination = planner.destination ?: return

    if (chosen != null) {
        AlertDialog(
            onDismissRequest = planner::cancel,
            title = { Text(stringResource(R.string.route_mode_title, chosen.display_name)) },
            text = {
                Column {
                    TravelMode.entries.forEach { mode ->
                        ListItem(
                            headlineContent = { Text(stringResource(mode.label)) },
                            leadingContent = { Icon(mode.icon, contentDescription = null) },
                            modifier = Modifier.clickable { planner.go(mode) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = planner::cancel) { Text(stringResource(R.string.cancel)) }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = planner::cancel,
        title = { Text(stringResource(R.string.route_pick_member)) },
        text = {
            if (planner.candidates.isEmpty()) {
                Text(stringResource(R.string.route_no_members))
            } else LazyColumn(Modifier.heightIn(max = cabe(320.dp))) {
                items(planner.candidates, key = { it.user_id }) { member ->
                    val meters = distanceMeters(
                        member.lat, member.lng, destination.latitude, destination.longitude,
                    )
                    // Los minutos de verdad hasta el destino, si el servidor los ha dicho
                    // (llegan un momento después de abrirse el cuadro y recolocan la lista).
                    val segundos = planner.travelSeconds[member.user_id]
                    ListItem(
                        headlineContent = { Text(member.display_name) },
                        // Lo que hace elegir a uno y no a otro: lo que hay hasta allí y lo vieja
                        // que es esa posición.
                        // Dos textos y no uno: sin los minutos esto es una distancia "en línea
                        // recta", y con ellos ya no lo es.
                        supportingContent = {
                            Text(
                                if (segundos == null) {
                                    stringResource(
                                        R.string.route_member_summary,
                                        formatDistance(meters),
                                        formatRelativeTime(member.timestamp),
                                    )
                                } else {
                                    stringResource(
                                        R.string.route_member_summary_time,
                                        formatDistance(meters),
                                        formatDuration((segundos / 60).roundToInt()),
                                        formatRelativeTime(member.timestamp),
                                    )
                                },
                            )
                        },
                        modifier = Modifier.clickable { planner.choose(member) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = planner::cancel) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Tarjeta de la ruta: quién, cómo, cuánto hay y cuánto se tarda, con los pasos, compartir y
 * cerrar. */
@Composable
fun RouteInfoCard(
    route: PlannedRoute,
    /** La foto se está armando: son cuatro capturas con sus esperas (ver [zoomedSnapshot]) y
     * mientras tanto el mapa se mueve solo. Sin esto parece que se ha colgado. */
    sharing: Boolean = false,
    /** Abre la lista de pasos, de donde se navega y se simula. null = esta ruta no trae
     * maniobras y no hay nada que listar. */
    onSteps: (() -> Unit)? = null,
    /** La foto de la ruta: true = compartirla, false = guardarla en Descargas. El tamaño lo
     * pregunta después quien la hace, que es el mismo diálogo de todas las fotos del mapa. */
    onPhoto: (share: Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var preguntarFoto by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.route_card_title, route.memberName),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(route.summary(), style = MaterialTheme.typography.bodySmall)
                // Solo cuando la ruta sale de una posición vieja. Si no se dice, la ruta parece
                // decir dónde está esa persona ahora, y no lo dice.
                route.staleAt?.let { at ->
                    Text(
                        stringResource(R.string.route_from_stale, formatRelativeTime(at)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            onSteps?.let { abrir ->
                IconButton(onClick = abrir) {
                    Icon(
                        Icons.Filled.Navigation,
                        contentDescription = stringResource(R.string.nav_steps),
                    )
                }
            }
            IconButton(onClick = { preguntarFoto = true }, enabled = !sharing) {
                if (sharing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.route_image))
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
            }
        }
    }

    // Guardar o compartir, las dos cosas con la misma foto. Un solo botón en la tarjeta y la
    // pregunta después: la tarjeta flota sobre el mapa y no da para cuatro iconos.
    if (preguntarFoto) {
        AlertDialog(
            onDismissRequest = { preguntarFoto = false },
            title = { Text(stringResource(R.string.route_image)) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.history_snapshot_save)) },
                        modifier = Modifier.clickable {
                            preguntarFoto = false
                            onPhoto(false)
                        },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.history_snapshot_share)) },
                        modifier = Modifier.clickable {
                            preguntarFoto = false
                            onPhoto(true)
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { preguntarFoto = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** "A pie · 2,4 km · 31 min". El tiempo es el que da el servicio de rutas, no una media sobre la
 * distancia: ya cuenta con las calles por las que se pasa. */
@Composable
fun PlannedRoute.summary(): String = stringResource(
    R.string.route_summary,
    stringResource(mode.label),
    formatDistance(meters.toFloat()),
    formatDuration((seconds / 60).roundToInt()),
)

/** La línea de la ruta. La llama [OsmMapView] al rehacer las capas, antes de los marcadores:
 * así los avatares quedan encima y se siguen pudiendo tocar. */
fun MapView.addRouteOverlay(points: List<GeoPoint>) {
    if (points.size < 2) return
    overlays.add(
        Polyline().apply {
            setPoints(points)
            outlinePaint.color = DIRECTIONS_COLOR
            outlinePaint.strokeWidth = 5f * resources.displayMetrics.density
        },
    )
}

/**
 * Encuadra la ruta entera dentro del hueco de mapa que de verdad se ve: [topInset] y
 * [bottomInset] son los píxeles que tapan el buscador de arriba y el cartel de la ruta de abajo,
 * que es por donde salía cortada. Después se amplía y se aleja a mano como siempre: esto solo
 * decide por dónde se empieza a mirar.
 */
fun MapView.frameRoute(points: List<GeoPoint>, topInset: Int = 0, bottomInset: Int = 0) {
    if (points.size < 2) return
    post {
        runCatching {
            // El encuadre de osmdroid solo entiende un margen igual por los cuatro lados: se le
            // da la media de los dos estorbos, que deja de alto exactamente el hueco libre, y
            // después se corre el mapa medio estorbo para centrar la ruta en ese hueco y no en
            // la pantalla. El tope es por si el cartel ocupa media vista: un margen mayor que la
            // mitad del mapa deja a osmdroid sin sitio donde encajar nada.
            val tope = (minOf(width, height) / 2 - 1).coerceAtLeast(0)
            val borde = (SNAPSHOT_PADDING_PX + (topInset + bottomInset) / 2).coerceAtMost(tope)
            zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(points), false, borde)
            // scrollBy mueve el mapa en píxeles sobre el zoom recién puesto — es lo mismo que usa
            // osmdroid al arrastrar con el dedo —, así que no hace falta convertir nada a grados.
            // Positivo = el contenido sube, que es lo que toca cuando abajo estorba más.
            val desplazar = (bottomInset - topInset) / 2
            if (desplazar != 0) scrollBy(0, desplazar)
        }
    }
}

/**
 * Manda la ruta como imagen por el diálogo de compartir del sistema: el mapa con la ruta
 * encuadrada entera y los datos en el pie, al tamaño [size].
 */
suspend fun exportRouteImage(
    context: Context,
    map: MapControl,
    route: PlannedRoute,
    summary: String,
    size: SnapshotSize = SnapshotSize.DOUBLE,
    /** true = al diálogo de compartir del sistema; false = a Descargas, como la foto del mapa. */
    share: Boolean = true,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
): Boolean {
    map.framePoints(route.points, SNAPSHOT_PADDING_PX)
    // Reencuadrar trae teselas nuevas, y una foto disparada antes de que lleguen sale a medio
    // pintar. Es la misma espera que la foto de la regla.
    delay(SNAPSHOT_TILE_WAIT_MS)
    val caption = context.getString(R.string.route_caption, route.memberName, summary)
    val bitmap = map.snapshotAtSize(caption, SNAPSHOT_TILE_WAIT_MS, size, onProgress) ?: return false
    return saveSnapshotPng(context, bitmap, "lokate-comollegar", share = share)
}
