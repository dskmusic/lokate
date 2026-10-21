package com.dskmusic.lokate.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.data.repository.LocationRepository
import com.dskmusic.lokate.util.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

/**
 * El color de cada anillo: cerca verde, medio ámbar, lejos naranja — el de un mapa de calor, que
 * es lo que esto es. Va por minutos y no por su sitio en la lista para que los tres colores
 * signifiquen siempre lo mismo aunque algún día el servidor mande otros tiempos
 * (backend routing.ISO_MINUTES).
 */
internal fun isochroneColor(minutes: Int): Int = when {
    minutes <= 5 -> 0xFF2E7D32.toInt()
    minutes <= 10 -> 0xFFF9A825.toInt()
    else -> 0xFFD84315.toInt()
}

/** Margen al encuadrar los anillos, para que el de 15 minutos no quede pegado al borde. */
internal const val ISOCHRONE_PADDING_PX = 64

/** Hasta dónde se llega en [minutes] minutos, ya en coordenadas y listo para pintar. */
data class IsoRing(val minutes: Int, val points: List<GeoPoint>)

/**
 * "Hasta dónde llega esta persona": los anillos de 5, 10 y 15 minutos andando o en coche.
 *
 * Una petición por pulsación y ninguna automática: cada una cuesta cuota de la cuenta de
 * isocronas, y nadie necesita esto refrescándose solo mientras mira el mapa.
 *
 * Si no se puede —sin servicio, sin cuota, o en medio del campo sin una calle cerca— se dice y
 * no se dibuja nada. Aquí no hay suplente que valga: una mancha inventada a vuelo de pájaro
 * sobre un mapa diría que se cruzan ríos y autopistas andando.
 */
class IsochroneState(
    private val scope: CoroutineScope,
    private val locations: LocationRepository,
) {
    /** Los anillos en pantalla. Vacío = no hay nada dibujado. */
    var rings by mutableStateOf<List<IsoRing>>(emptyList())
        private set

    /** De quién son (para el título de la tarjeta). null = no hay nada en marcha. */
    var member by mutableStateOf<LocationDto?>(null)
        private set

    var mode by mutableStateOf(TravelMode.FOOT)
        private set

    var busy by mutableStateOf(false)
        private set

    /** El servidor no ha podido: se enseña en la tarjeta hasta que se cierra o se reintenta. */
    var failed by mutableStateOf(false)
        private set

    private var job: Job? = null

    /** Lo pide y lo deja dibujado. Con [who] ya pedido antes y el mismo modo, lo vuelve a pedir:
     * es lo que hace el botón de "a pie / en coche" de la tarjeta, y también reintentar. */
    fun show(who: LocationDto, travel: TravelMode = mode) {
        job?.cancel()
        member = who
        mode = travel
        rings = emptyList()
        failed = false
        busy = true
        job = scope.launch {
            val dto = locations.isochrone(who.lat, who.lng, travel.api)
            busy = false
            // Un anillo de menos de tres puntos no es un polígono: no se dibuja.
            val pintables = dto?.rings.orEmpty().map { anillo ->
                IsoRing(anillo.minutes, anillo.points.mapNotNull { it.toGeoPoint() })
            }.filter { it.points.size >= 3 }
            if (pintables.isEmpty()) failed = true else rings = pintables
        }
    }

    fun clear() {
        job?.cancel()
        job = null
        member = null
        rings = emptyList()
        busy = false
        failed = false
    }
}

/** [lat, lng] del servidor a GeoPoint; null si viene cortado. */
private fun List<Double>.toGeoPoint(): GeoPoint? =
    if (size >= 2) GeoPoint(this[0], this[1]) else null

@Composable
fun rememberIsochrone(locations: LocationRepository): IsochroneState {
    val scope = rememberCoroutineScope()
    return remember { IsochroneState(scope, locations) }
}

/**
 * La tarjeta de los anillos: de quién son, qué significa cada color, y el botón de cambiar a pie
 * por en coche (que pide otros anillos). Ocupa el mismo sitio que la tarjeta de la ruta.
 */
@Composable
fun IsochroneCard(state: IsochroneState, modifier: Modifier = Modifier) {
    val who = state.member ?: return
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
                    stringResource(R.string.isochrone_title, who.display_name),
                    style = MaterialTheme.typography.titleMedium,
                )
                when {
                    state.busy -> Text(
                        stringResource(R.string.isochrone_loading),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.failed -> Text(
                        stringResource(R.string.isochrone_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // De menos a más minutos, al revés que al dibujarlos: la leyenda se lee
                        // como lo que significa (lo cerca primero).
                        state.rings.sortedBy { it.minutes }.forEach { ring ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    modifier = Modifier.size(10.dp),
                                    shape = CircleShape,
                                    color = Color(isochroneColor(ring.minutes)),
                                ) {}
                                Text(
                                    " " + formatDuration(ring.minutes),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            } else {
                // El otro modo: pulsarlo pide los anillos de ese modo (otra petición, a propósito
                // — el usuario la pide igual que la primera).
                val otro = if (state.mode == TravelMode.FOOT) TravelMode.CAR else TravelMode.FOOT
                IconButton(onClick = { state.show(who, otro) }) {
                    Icon(otro.icon, contentDescription = stringResource(otro.label))
                }
            }
            IconButton(onClick = state::clear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
            }
        }
    }
}
