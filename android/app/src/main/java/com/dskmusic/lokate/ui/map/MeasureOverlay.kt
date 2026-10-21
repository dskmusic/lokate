package com.dskmusic.lokate.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dskmusic.lokate.R
import com.dskmusic.lokate.util.formatDistance
import com.dskmusic.lokate.util.formatDuration
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

/** Naranja, el mismo del punto elegido en el historial: no se confunde con el azul de las zonas
 * ni con los colores de los avatares. */
const val MEASURE_COLOR = 0xFFE0654F.toInt()

/** Lado del punto en dp. osmdroid mide el acierto del toque con el tamaño del icono y estos
 * puntos se tocan para borrarlos y se agarran para moverlos, así que es más grande de lo que
 * pide el dibujo — sin llegar a los 48 dp de un botón, que taparían media calle. */
internal const val MEASURE_DOT_DP = 22

/** Sitios que se le prueban a una medida antes de dejarla donde caiga (ver MeasureLabelsOverlay). */
private const val LABEL_ATTEMPTS = 8

/**
 * Velocidades con las que se estiman los tiempos. Son medias de trayecto puerta a puerta, no
 * la velocidad punta: andando se cuenta con semáforos y cuestas, y en coche con calles,
 * rotondas y algún atasco. La distancia medida es además en línea recta, así que el tiempo real
 * siempre saldrá algo mayor — es una estimación de servilleta, no un navegador.
 *
 * Son el mando de ajuste de esta función: si los tiempos salen sistemáticamente cortos o largos
 * para la zona donde se usa la app, se tocan estos dos números y ya está.
 */
private const val WALK_KMH = 4.8
private const val DRIVE_KMH = 45.0

/** Margen alrededor de la ruta en la foto, para que los puntos de las puntas no queden pegados
 * al borde. */
internal const val SNAPSHOT_PADDING_PX = 96

/** Lo que se espera a que lleguen las teselas del nuevo encuadre antes de disparar la foto.
 * ponytail: espera fija porque osmdroid no avisa de "ya está todo pintado". Si la imagen sale
 * con huecos grises, subir este número. */
internal const val SNAPSHOT_TILE_WAIT_MS = 1_200L

/** Metros de la ruta medida: suma de los tramos en línea recta, en el orden en que se marcaron. */
fun measureTotalMeters(points: List<GeoPoint>): Double {
    var total = 0.0
    for (i in 1 until points.size) total += points[i - 1].distanceToAsDouble(points[i])
    return total
}

fun walkMinutes(meters: Double): Int = (meters / 1_000.0 / WALK_KMH * 60.0).roundToInt()

fun driveMinutes(meters: Double): Int = (meters / 1_000.0 / DRIVE_KMH * 60.0).roundToInt()

/** "1,4 km · A pie 18 min · En coche 2 min": lo mismo que enseña la tarjeta, en una línea, para
 * el pie de la imagen exportada. */
private fun measureCaption(context: Context, meters: Double): String =
    formatDistance(meters.toFloat()) + " · " + context.getString(
        R.string.measure_times,
        formatDuration(walkMinutes(meters)),
        formatDuration(driveMinutes(meters)),
    )

/**
 * Guarda en Descargas una foto de la ruta entera con la distancia y los tiempos al pie, o la manda
 * al diálogo de compartir del sistema con [share] — es la misma foto, solo cambia dónde acaba.
 *
 * Con [fitRoute] reencuadra el mapa sobre todos los puntos antes de disparar, que es lo normal:
 * "la ruta completa" no es el trozo que se estuviera mirando. El encuadre se queda puesto a
 * propósito — el usuario ve lo mismo que acaba de guardar. Sin él se dispara tal y como está la
 * pantalla ahora mismo, para quien haya buscado a mano el zoom que quiere en la foto.
 *
 * [midPoints] y [segmentDistances] dejan fuera de la foto los puntos de en medio y las medidas de
 * cada tramo: en una ruta corta, o con muchos puntos seguidos, se amontonan unos sobre otros y no
 * hay quien lea nada. Lo que sobra se esconde y se vuelve a enseñar al acabar en vez de rehacerlo,
 * porque rehacerlo pasa por una recomposición que no llega a tiempo para la foto (ver
 * [MapControl.hideForMeasurePhoto]). El total siempre va al pie, así que quitar las medidas de
 * tramo no esconde la cifra que importa.
 */
suspend fun saveMeasureImage(
    context: Context,
    map: MapControl,
    points: List<GeoPoint>,
    midPoints: Boolean = true,
    segmentDistances: Boolean = true,
    fitRoute: Boolean = true,
    share: Boolean = false,
    size: SnapshotSize = SnapshotSize.SCREEN,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
): Boolean {
    if (points.size < 2) return false
    val restore = map.hideForMeasurePhoto(midPoints, segmentDistances)
    try {
        if (fitRoute) {
            map.framePoints(points, SNAPSHOT_PADDING_PX)
            // Solo hay teselas nuevas que esperar si el mapa se ha movido; disparando sobre lo
            // que ya se está viendo, ya están todas pintadas.
            delay(SNAPSHOT_TILE_WAIT_MS)
        }
        val caption = measureCaption(context, measureTotalMeters(points))
        val bitmap = map.snapshotAtSize(caption, SNAPSHOT_TILE_WAIT_MS, size, onProgress) ?: return false
        return saveSnapshotPng(context, bitmap, "lokate-ruta", share)
    } finally {
        restore()
    }
}

/** Las capas que no salen en la foto de la regla, apagadas; devuelve con qué encenderlas otra
 * vez. Es la mitad de osmdroid de [MapControl.hideForMeasurePhoto]. */
internal fun MapView.hideMeasureExtras(midPoints: Boolean, segmentDistances: Boolean): () -> Unit {
    val last = overlays.filterIsInstance<MeasureDotMarker>().maxOfOrNull { it.index } ?: 0
    val hidden = overlays.filter { overlay ->
        when (overlay) {
            is MeasureLabelsOverlay -> !segmentDistances
            // Las puntas se quedan siempre: sin ellas no se ve dónde empieza ni dónde acaba.
            is MeasureDotMarker -> !midPoints && overlay.index != 0 && overlay.index != last
            else -> false
        }
    }
    hidden.forEach { it.isEnabled = false }
    return {
        hidden.forEach { it.isEnabled = true }
        invalidate()
    }
}

/** Punto de la ruta medida. Lleva su sitio en la lista porque la foto puede dejar fuera los de
 * en medio (ver [saveMeasureImage]) y hay que saber cuáles son. */
private class MeasureDotMarker(map: MapView, val index: Int) : Marker(map)

/**
 * Línea, distancias y puntos del modo medir. La llama [OsmMapView] al rehacer las capas, la
 * última de todas: osmdroid prueba las capas de la más nueva a la más vieja, así que estando al
 * final son estos puntos los que se quedan el toque (tocar = borrar) y no el marcador de alguien
 * o el círculo de una zona que caiga debajo.
 *
 * Todo va en coordenadas, no en píxeles, así que el zoom y el desplazamiento del mapa salen
 * gratis: la ruta se queda donde se marcó y se puede seguir añadiendo puntos a cualquier escala.
 */
fun MapView.addMeasureOverlays(
    points: List<GeoPoint>,
    onDelete: (Int) -> Unit,
    onMoved: (Int, GeoPoint) -> Unit,
    /** Toque sobre la línea: un punto nuevo EN MEDIO, en esa posición de la lista. */
    onInsert: (Int, GeoPoint) -> Unit,
    /** Se acaba de agarrar un punto (para el tic de radar de la pantalla). */
    onGrab: (GeoPoint) -> Unit,
    /** Metros mientras dura el arrastre, null al soltar. */
    onLiveTotal: (Double?) -> Unit,
) {
    if (points.isEmpty()) return
    val density = resources.displayMetrics.density

    // Copia de trabajo del arrastre: mientras el dedo se mueve, el punto cambia AQUÍ y la línea
    // se rehace contra esta lista, sin tocar el estado de Compose. Rehacer todas las capas del
    // mapa sesenta veces por segundo no iría a ninguna velocidad. Las medidas leen esta misma
    // lista en cada repintado, así que se arrastran solas.
    val live = points.toMutableList()
    var line: Polyline? = null

    if (points.size > 1) {
        line = Polyline().apply {
            setPoints(live)
            outlinePaint.color = MEASURE_COLOR
            outlinePaint.strokeWidth = 4f * density
            // osmdroid mide la tolerancia del toque como grosor * densidad * este multiplicador,
            // y la densidad se queda en 1 al construir la Polyline sin MapView (ver HistoryMapView).
            setDensityMultiplier(density * TAP_TOLERANCE_DP / outlinePaint.strokeWidth)
            setOnClickListener { _, _, tapped ->
                val (index, onLine) = nearestSegmentPoint(live, tapped)
                onInsert(index, onLine)
                true
            }
        }
        overlays.add(line)
    }

    val dot = BitmapDrawable(resources, MarkerIconFactory.dotBitmap(MEASURE_COLOR, (MEASURE_DOT_DP * density).toInt()))
    points.forEachIndexed { index, point ->
        val marker = MeasureDotMarker(this, index)
        marker.position = point
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        marker.icon = dot
        marker.setOnMarkerClickListener { _, _ ->
            onDelete(index)
            true
        }
        marker.isDraggable = true
        marker.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
            override fun onMarkerDragStart(m: Marker) {
                // Vibración corta del sistema (la misma de cualquier pulsación larga de Android,
                // así que respeta que el usuario tenga la háptica apagada) más el tic de radar
                // de la pantalla: sin eso no se distingue "ya lo tengo cogido" de "no me hace caso".
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onGrab(m.position)
            }

            override fun onMarkerDrag(m: Marker) {
                // El marcador lo mueve osmdroid solo; lo que hay que arrastrar con él es la
                // línea, o el punto se iría del sitio dejando la ruta atrás. Las medidas van
                // contra esta misma lista, así que no hay que tocarlas.
                live[index] = GeoPoint(m.position.latitude, m.position.longitude)
                line?.setPoints(live)
                if (live.size > 1) onLiveTotal(measureTotalMeters(live))
                invalidate()
            }

            override fun onMarkerDragEnd(m: Marker) {
                onLiveTotal(null)
                onMoved(index, m.position)
            }
        })
        overlays.add(marker)
    }

    // Cuánto mide cada tramo. Con varios puntos el total de la tarjeta ya no contesta "¿y de
    // este a este?", que es la pregunta del modo medir. Encima de todo para que ningún punto
    // tape una medida; no se queda los toques (Overlay no los coge si no se le dice).
    if (points.size > 1) overlays.add(MeasureLabelsOverlay(live, density))
}

/**
 * Las medidas de cada tramo, apartadas de la ruta y unidas a ella por una raya.
 *
 * Va pintada a mano y no con marcadores de osmdroid porque el problema es de píxeles, no de
 * coordenadas: dos puntos a diez metros caen encima el uno del otro con el mapa alejado y bien
 * separados con el mapa cerca, así que dónde cabe cada etiqueta solo se sabe en el momento de
 * dibujar y cambia con cada zoom. Un marcador está clavado a una coordenada; esto se recoloca
 * en cada repintado.
 *
 * Además lee [points] en vivo, así que arrastrar un punto mueve sus medidas gratis.
 *
 * El reparto es a lo bruto y por orden: cada etiqueta se prueba a un lado y a otro de su tramo,
 * cada vez más lejos, y se queda en el primer hueco libre. ponytail: con rutas de decenas de
 * puntos sobra; si alguna vez se miden cientos, esto es un O(n²) por fotograma y habría que
 * meterlas en una rejilla.
 */
private class MeasureLabelsOverlay(
    private val points: List<GeoPoint>,
    density: Float,
) : Overlay() {

    /** Separación mínima entre la raya de la ruta y la etiqueta. */
    private val offset = 22f * density

    /** Lo que se aleja en cada intento cuando el sitio ya está cogido. */
    private val step = 15f * density
    private val padH = 5f * density
    private val padV = 3f * density
    private val corner = 4f * density

    /** Aire entre etiquetas: pegadas pero sin tocarse ya se leen mal. */
    private val gap = 3f * density

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MEASURE_COLOR }
    private val leaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MEASURE_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
        isFakeBoldText = true
    }

    private val startPx = Point()
    private val endPx = Point()
    private val box = RectF()
    private val taken = mutableListOf<RectF>()

    override fun draw(canvas: Canvas, projection: Projection) {
        if (points.size < 2) return
        taken.clear()
        val metrics = textPaint.fontMetrics
        val height = metrics.descent - metrics.ascent + 2 * padV
        val baselineShift = -(metrics.ascent + metrics.descent) / 2

        for (i in 1 until points.size) {
            projection.toPixels(points[i - 1], startPx)
            projection.toPixels(points[i], endPx)
            val dx = (endPx.x - startPx.x).toFloat()
            val dy = (endPx.y - startPx.y).toFloat()
            val length = hypot(dx, dy)
            // Tramo de menos de un píxel: no hay dónde poner la etiqueta ni nada que leer.
            if (length < 1f) continue

            val midX = (startPx.x + endPx.x) / 2f
            val midY = (startPx.y + endPx.y) / 2f
            val text = formatDistance(points[i - 1].distanceToAsDouble(points[i]).toFloat())
            val width = textPaint.measureText(text) + 2 * padH
            // Perpendicular al tramo: la etiqueta se aparta de lado, nunca a lo largo, para que
            // la raya que la une salga corta y se vea de qué tramo cuelga.
            val normalX = -dy / length
            val normalY = dx / length

            var x = midX
            var y = midY
            for (attempt in 0 until LABEL_ATTEMPTS) {
                // Lados alternos (par a un lado, impar al otro) y cada dos intentos, más lejos.
                val side = if (attempt % 2 == 0) 1f else -1f
                val away = (offset + step * (attempt / 2)) * side
                x = midX + normalX * away
                y = midY + normalY * away
                box.set(x - width / 2, y - height / 2, x + width / 2, y + height / 2)
                if (taken.none { RectF.intersects(it, box) }) break
            }

            canvas.drawLine(midX, midY, x, y, leaderPaint)
            canvas.drawRoundRect(box, corner, corner, boxPaint)
            canvas.drawText(text, x, y + baselineShift, textPaint)
            taken.add(RectF(box.left - gap, box.top - gap, box.right + gap, box.bottom + gap))
        }
    }
}

/**
 * Dónde encaja un toque dado sobre la línea: en qué posición de la lista va el punto nuevo
 * (entre 1 y n-1) y el punto ya pegado al tramo, para que insertar no deje un codo justo donde
 * el dedo se desvió un pelo de la línea.
 *
 * La cuenta va en grados con la longitud encogida por cos(lat), que es lo que hace que un grado
 * mida lo mismo en los dos ejes; a la escala de una pantalla de mapa el mundo es plano de sobra
 * para esto, y comparar cuadrados de distancia evita hasta la raíz.
 */
internal fun nearestSegmentPoint(points: List<GeoPoint>, tap: GeoPoint): Pair<Int, GeoPoint> {
    val kx = cos(Math.toRadians(tap.latitude)).coerceAtLeast(1e-6)
    val px = tap.longitude * kx
    val py = tap.latitude
    var bestIndex = 1
    var bestPoint = points[1]
    var bestDistance = Double.MAX_VALUE
    for (i in 1 until points.size) {
        val ax = points[i - 1].longitude * kx
        val ay = points[i - 1].latitude
        val dx = points[i].longitude * kx - ax
        val dy = points[i].latitude - ay
        val lengthSq = dx * dx + dy * dy
        // Tramo de largo cero (dos puntos encima del otro): no tiene dirección, vale la punta.
        val t = if (lengthSq == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
        val cx = ax + dx * t
        val cy = ay + dy * t
        val distance = (px - cx) * (px - cx) + (py - cy) * (py - cy)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = i
            bestPoint = GeoPoint(cy, cx / kx)
        }
    }
    return bestIndex to bestPoint
}

/**
 * Tarjeta flotante del modo medir: el total de la ruta, cuánto se tarda a pie y en coche, cómo
 * se usa y los botones de guardar y compartir la imagen. Ocupa el sitio del buscador de sitios
 * mientras dura la medición.
 *
 * [liveMeters] es el total mientras un punto va colgado del dedo: en ese rato [points] todavía
 * tiene la posición vieja (solo se confirma al soltar), así que si viene manda él.
 */
@Composable
fun MeasureInfoCard(
    points: List<GeoPoint>,
    liveMeters: Double?,
    onSave: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                val meters = liveMeters ?: measureTotalMeters(points)
                val hasRoute = points.size > 1 || liveMeters != null
                Text(
                    if (hasRoute) {
                        stringResource(R.string.measure_total, formatDistance(meters.toFloat()))
                    } else {
                        stringResource(R.string.measure_title)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (hasRoute) {
                    Text(
                        stringResource(
                            R.string.measure_times,
                            formatDuration(walkMinutes(meters)),
                            formatDuration(driveMinutes(meters)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    stringResource(
                        if (points.isEmpty()) R.string.measure_hint_start else R.string.measure_hint_edit,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Las dos salidas de la foto, una debajo de otra: a Descargas y al diálogo de
            // compartir del sistema. Las dos pasan por las mismas opciones de la imagen.
            Column {
                IconButton(onClick = onSave, enabled = points.size > 1) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.measure_save))
                }
                IconButton(onClick = onShare, enabled = points.size > 1) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.measure_share))
                }
            }
        }
    }
}

/**
 * Qué entra en la foto, antes de dispararla. Con la ruta corta o con muchos puntos seguidos, los
 * puntos y las medidas de cada tramo se pisan unos a otros; desde aquí se quitan. Y el encuadre:
 * o la ruta entera, o lo que se esté viendo ahora mismo.
 *
 * Vale para las dos cosas: con [share] solo cambia lo que dice el botón, porque elegir qué sale en
 * la foto es la misma pregunta se vaya a guardar o a mandar.
 */
@Composable
fun MeasureSaveOptionsDialog(
    share: Boolean,
    midPoints: Boolean,
    segmentDistances: Boolean,
    fitRoute: Boolean,
    onMidPointsChange: (Boolean) -> Unit,
    onSegmentDistancesChange: (Boolean) -> Unit,
    onFitRouteChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (share) R.string.measure_share else R.string.measure_save)) },
        text = {
            Column {
                MeasureOptionRow(
                    stringResource(R.string.measure_save_mid_points),
                    midPoints,
                    onMidPointsChange,
                )
                MeasureOptionRow(
                    stringResource(R.string.measure_save_segment_distances),
                    segmentDistances,
                    onSegmentDistancesChange,
                )
                MeasureOptionRow(
                    stringResource(R.string.measure_save_fit_route),
                    fitRoute,
                    onFitRouteChange,
                )
                Text(
                    stringResource(
                        if (fitRoute) R.string.measure_save_fit_route_hint
                        else R.string.measure_save_current_view_hint,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(if (share) R.string.measure_share_confirm else R.string.measure_save_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun MeasureOptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
