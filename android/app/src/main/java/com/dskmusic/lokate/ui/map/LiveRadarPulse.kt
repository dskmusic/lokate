package com.dskmusic.lokate.ui.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.dskmusic.lokate.data.remote.dto.LocationDto
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/** Lo que dura un tic. Por debajo del ritmo del tiempo real (3 s), para que se apague antes de
 * que llegue el siguiente y se vea latir y no un anillo fijo. */
private const val PULSE_MS = 900

private val PULSE_START_RADIUS = 18.dp
private val PULSE_END_RADIUS = 46.dp

/**
 * Tic de radar sobre el marcador de quien se está siguiendo en vivo: late una vez por cada
 * posición que llega de su móvil, aunque venga en el mismo sitio que la anterior.
 *
 * Va dibujado encima del mapa y no en el marcador de osmdroid a propósito: rehacer el icono
 * del marcador (recortando otra vez su avatar) sesenta veces por segundo para animarlo sería
 * carísimo, y aquí solo se pinta un círculo.
 *
 * ponytail: la posición se proyecta a píxeles en cada fotograma, así que mover el mapa
 * mientras late no descoloca el tic; entre tic y tic no se dibuja nada.
 */
@Composable
fun LiveRadarPulse(mapView: MapView?, location: LocationDto?, modifier: Modifier = Modifier) {
    if (mapView == null || location == null) return

    val progress = remember { Animatable(1f) }
    // El disparo es el timestamp y no la posición: es lo único que cambia en CADA ping, que es
    // justo lo que este tic tiene que demostrar que sigue pasando.
    LaunchedEffect(location.timestamp) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = PULSE_MS, easing = LinearEasing))
    }

    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val fraction = progress.value
        if (fraction >= 1f) return@Canvas
        val pixel = mapView.projection.toPixels(GeoPoint(location.lat, location.lng), null)
        val radius = PULSE_START_RADIUS.toPx() + (PULSE_END_RADIUS.toPx() - PULSE_START_RADIUS.toPx()) * fraction
        drawCircle(
            color = color.copy(alpha = 0.6f * (1f - fraction)),
            radius = radius,
            center = Offset(pixel.x.toFloat(), pixel.y.toFloat()),
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}
