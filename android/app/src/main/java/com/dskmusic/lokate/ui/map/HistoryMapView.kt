package com.dskmusic.lokate.ui.map

import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.dskmusic.lokate.data.local.LocationHistoryEntity
import com.dskmusic.lokate.util.MapStyle
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private const val DOT_SIZE_PX = 28
private const val FOCUSED_DOT_SIZE_PX = 44
private const val ROUTE_COLOR = 0xFF4A6FE3.toInt()
private const val FOCUSED_COLOR = 0xFFE0654F.toInt()
private const val START_COLOR = 0xFF3FA66A.toInt()

/** Distancia mínima entre puntos dibujados. Con el seguimiento en tiempo real llegan ~1200
 * posiciones por hora y casi todas caen encima de la anterior (el GPS baila unos metros aunque
 * el móvil esté quieto), así que quedarse con una cada 20 m dibuja el mismo recorrido con una
 * fracción de los puntos. Subir si el historial de un día sigue yendo lento, bajar si se pierde
 * detalle en trayectos cortos. */
private const val MIN_POINT_DISTANCE_METERS = 20.0

/** Margen de acierto al tocar la ruta, en dp (un dedo ocupa unos 16). */
private const val TAP_TOLERANCE_DP = 16f

/** Recorrido del historial: línea uniendo los puntos en orden cronológico, con marcador en la
 * salida, la llegada y el punto elegido. */
@Composable
fun HistoryMapView(
    points: List<LocationHistoryEntity>,
    focusedPoint: LocationHistoryEntity?,
    mapStyle: MapStyle,
    modifier: Modifier = Modifier,
    onMapReady: (MapView) -> Unit = {},
    onPointSelected: (LocationHistoryEntity) -> Unit = {},
) {
    val context = LocalContext.current
    val offlineFiles = rememberOfflineMapFiles()
    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(15.0)
        }
    }

    val drawn = remember(points) { simplify(points) }
    var hasAutoCentered by remember { mutableStateOf(false) }

    DisposableEffect(mapView) {
        onMapReady(mapView)
        onDispose { mapView.onDetach() }
    }

    // Sin conexión pero mirando una zona sin descargar -> mapa de internet, con aviso.
    val effectiveStyle = rememberEffectiveMapStyle(mapView, mapStyle, offlineFiles)

    TileRetryEffect(mapView)

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            view.applyMapStyle(effectiveStyle, offlineFiles)
            view.overlays.clear()

            if (drawn.isNotEmpty()) {
                val route = Polyline().apply {
                    setPoints(drawn.map { GeoPoint(it.lat, it.lng) })
                    outlinePaint.color = ROUTE_COLOR
                    outlinePaint.strokeWidth = 6f
                    // osmdroid mide la tolerancia de toque como strokeWidth * densidad * este
                    // multiplicador, y la densidad se queda en 1 al construir la Polyline sin
                    // MapView: sin esto el margen son 6 px y hay que clavar el dedo en la línea.
                    setDensityMultiplier(
                        view.context.resources.displayMetrics.density * TAP_TOLERANCE_DP / outlinePaint.strokeWidth,
                    )
                    setOnClickListener { _, _, tapped ->
                        nearestTo(drawn, tapped)?.let(onPointSelected)
                        true
                    }
                }
                view.overlays.add(route)

                // ponytail: tres marcadores (salida, llegada y el elegido) en vez de uno por
                // posición. Un Marker con su Bitmap por cada punto era lo que dejaba la pestaña
                // sin responder cuando alguien llevaba el seguimiento en vivo todo el día.
                addDot(view, drawn.first(), START_COLOR, DOT_SIZE_PX, onPointSelected)
                if (drawn.size > 1) addDot(view, drawn.last(), ROUTE_COLOR, DOT_SIZE_PX, onPointSelected)
                focusedPoint?.let { addDot(view, it, FOCUSED_COLOR, FOCUSED_DOT_SIZE_PX, onPointSelected) }

                if (!hasAutoCentered) {
                    view.controller.setCenter(GeoPoint(drawn.last().lat, drawn.last().lng))
                    hasAutoCentered = true
                }
            }

            view.invalidate()
        },
    )
}

/** Quita los puntos que caigan a menos de [MIN_POINT_DISTANCE_METERS] del último que se quedó.
 * Primero y último se conservan siempre para que la ruta empiece y acabe donde toca. */
private fun simplify(points: List<LocationHistoryEntity>): List<LocationHistoryEntity> {
    if (points.size < 3) return points
    val result = ArrayList<LocationHistoryEntity>()
    result.add(points.first())
    var last = GeoPoint(points.first().lat, points.first().lng)
    for (point in points.subList(1, points.size - 1)) {
        val current = GeoPoint(point.lat, point.lng)
        if (last.distanceToAsDouble(current) >= MIN_POINT_DISTANCE_METERS) {
            result.add(point)
            last = current
        }
    }
    result.add(points.last())
    return result
}

/** Metros recorridos: suma de los tramos de la ruta ya dibujada, no la distancia en
 * línea recta entre extremos. Se mide sobre los puntos simplificados a propósito: con las
 * posiciones en bruto, el baile del GPS con el móvil quieto sumaría kilómetros que nadie anduvo.
 * Con [visible] solo cuenta lo que cae dentro de ese trozo de mapa. */
fun routeDistanceMeters(points: List<LocationHistoryEntity>, visible: BoundingBox? = null): Double {
    val route = simplify(points)
    var total = 0.0
    for (i in 1 until route.size) {
        val from = GeoPoint(route[i - 1].lat, route[i - 1].lng)
        val to = GeoPoint(route[i].lat, route[i].lng)
        // ponytail: un tramo cuenta entero si se le ve alguna punta, sin recortarlo por el borde
        // de la pantalla. Recortar de verdad (Cohen-Sutherland y compañía) es mucho código para
        // una cifra informativa; con puntos cada pocos minutos el error es de un tramo por borde.
        if (visible != null && !visible.contains(from) && !visible.contains(to)) continue
        total += from.distanceToAsDouble(to)
    }
    return total
}

private fun nearestTo(points: List<LocationHistoryEntity>, target: GeoPoint): LocationHistoryEntity? =
    points.minByOrNull { GeoPoint(it.lat, it.lng).distanceToAsDouble(target) }

/** Al devolver `true` el listener también se come el bocadillo por defecto de osmdroid, que salía
 * vacío porque estos marcadores no tienen título ni descripción: las coordenadas se enseñan en la
 * tarjeta de la pantalla, desde donde se pueden copiar. */
private fun addDot(
    view: MapView,
    point: LocationHistoryEntity,
    color: Int,
    sizePx: Int,
    onClick: (LocationHistoryEntity) -> Unit,
) {
    val marker = Marker(view)
    marker.position = GeoPoint(point.lat, point.lng)
    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    marker.icon = BitmapDrawable(view.context.resources, MarkerIconFactory.dotBitmap(color, sizePx))
    marker.setOnMarkerClickListener { _, _ ->
        onClick(point)
        true
    }
    view.overlays.add(marker)
}
