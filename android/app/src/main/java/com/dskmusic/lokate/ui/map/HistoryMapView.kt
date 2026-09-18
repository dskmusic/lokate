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
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private const val DOT_SIZE_PX = 28
private const val FOCUSED_DOT_SIZE_PX = 44
private const val ROUTE_COLOR = 0xFF4A6FE3.toInt()
private const val FOCUSED_COLOR = 0xFFE0654F.toInt()

/** Recorrido del historial: línea uniendo los puntos en orden cronológico + un punto por cada posición. */
@Composable
fun HistoryMapView(
    points: List<LocationHistoryEntity>,
    focusedPoint: LocationHistoryEntity?,
    modifier: Modifier = Modifier,
    onMapReady: (MapView) -> Unit = {},
) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(15.0)
        }
    }

    var hasAutoCentered by remember { mutableStateOf(false) }

    DisposableEffect(mapView) {
        onMapReady(mapView)
        onDispose { mapView.onDetach() }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            view.overlays.clear()

            if (points.isNotEmpty()) {
                val route = Polyline().apply {
                    setPoints(points.map { GeoPoint(it.lat, it.lng) })
                    outlinePaint.color = ROUTE_COLOR
                    outlinePaint.strokeWidth = 6f
                }
                view.overlays.add(route)

                points.forEach { point ->
                    val isFocused = focusedPoint?.id == point.id
                    val marker = Marker(view)
                    marker.position = GeoPoint(point.lat, point.lng)
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    val size = if (isFocused) FOCUSED_DOT_SIZE_PX else DOT_SIZE_PX
                    val color = if (isFocused) FOCUSED_COLOR else ROUTE_COLOR
                    marker.icon = BitmapDrawable(context.resources, MarkerIconFactory.dotBitmap(color, size))
                    view.overlays.add(marker)
                }

                if (!hasAutoCentered) {
                    view.controller.setCenter(GeoPoint(points.last().lat, points.last().lng))
                    hasAutoCentered = true
                }
            }

            view.invalidate()
        },
    )
}
