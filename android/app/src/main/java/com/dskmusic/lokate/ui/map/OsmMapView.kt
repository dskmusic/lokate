package com.dskmusic.lokate.ui.map

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.data.remote.dto.ZoneDto
import com.dskmusic.lokate.util.MapStyle
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

private const val MARKER_SIZE_PX = 120

/** Recuerda el estado del mapa PRINCIPAL entre cambios de pestaña, mientras la app siga en
 * memoria (no se persiste en disco: se pierde si se mata el proceso, y eso está bien, es un
 * recuerdo de sesión, no un ajuste). La posición/zoom solo la usa [OsmMapView] cuando se le
 * pide explícitamente con `rememberCamera = true` — las demás pantallas con mapa (crear zona,
 * etc.) no la tocan ni la leen, cada una sigue centrando como le convenga. [followUserId] lo
 * lee y escribe directamente [com.dskmusic.lokate.ui.map.MapScreen], para que "seguir en vivo"
 * tampoco se pierda al cambiar de pestaña. */
object MapCameraMemory {
    var center: GeoPoint? = null
    var zoom: Double? = null
    var followUserId: String? = null

    /** Se llama al iniciar/cerrar sesión (ver AuthRepository) — sin esto, si un usuario cierra
     * sesión y otro entra en el mismo proceso de la app (p. ej. probando varias cuentas sin
     * matar la app del todo), heredaría la posición/seguimiento del usuario anterior en vez de
     * arrancar centrado en su propia ubicación. */
    fun reset() {
        center = null
        zoom = null
        followUserId = null
    }
}

/** Modo oscuro del mapa: en vez de depender de un proveedor de teselas oscuras de terceros
 * (CARTO exige clave y, aun con clave válida, su CDN sirve teselas cacheadas de otros
 * usuarios/claves para coordenadas populares, ignorando la propia — nada fiable), se invierten
 * los colores de las mismas teselas estándar de OSM que ya funcionan. Escala de grises primero
 * para que no queden colores raros (verde/azul invertidos a magenta/naranja), luego invertido. */
private val DARK_MODE_FILTER = ColorMatrixColorFilter(
    ColorMatrix().apply {
        setSaturation(0f)
        postConcat(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    },
)

@Composable
fun OsmMapView(
    members: List<LocationDto>,
    zones: List<ZoneDto>,
    modifier: Modifier = Modifier,
    /** Foto de perfil ya descargada por usuario (ver [rememberAvatarBitmaps]); sin entrada -> icono con inicial. */
    avatarBitmaps: Map<String, Bitmap> = emptyMap(),
    onMemberClick: (LocationDto) -> Unit = {},
    onMapTap: ((GeoPoint) -> Unit)? = null,
    /** Entrega la instancia real de MapView una vez lista, para poder centrarla bajo demanda (botón "Dónde estoy"). */
    onMapReady: (MapView) -> Unit = {},
    initialZoom: Double = 20.0,
    mapStyle: MapStyle = MapStyle.STANDARD,
    /** true en el mapa principal: al volver de otra pestaña, restaura la posición/zoom donde
     * se dejó el mapa en vez de recentrar solo. false (por defecto) en el resto de mapas de la
     * app (crear/editar zona, ...), que ya se centran a su manera. */
    rememberCamera: Boolean = false,
) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            // El rocker +/- nativo de osmdroid se solapaba con los FAB de Compose (pellizcar
            // para hacer zoom ya cubre lo mismo).
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)

            val savedCenter = MapCameraMemory.center.takeIf { rememberCamera }
            val savedZoom = MapCameraMemory.zoom.takeIf { rememberCamera }
            if (savedCenter != null && savedZoom != null) {
                controller.setCenter(savedCenter)
                controller.setZoom(savedZoom)
            } else {
                controller.setZoom(initialZoom)
            }

            if (rememberCamera) {
                addMapListener(object : MapListener {
                    override fun onScroll(event: ScrollEvent?): Boolean {
                        MapCameraMemory.center = GeoPoint(mapCenter.latitude, mapCenter.longitude)
                        MapCameraMemory.zoom = zoomLevelDouble
                        return false
                    }

                    override fun onZoom(event: ZoomEvent?): Boolean {
                        MapCameraMemory.center = GeoPoint(mapCenter.latitude, mapCenter.longitude)
                        MapCameraMemory.zoom = zoomLevelDouble
                        return false
                    }
                })
            }
        }
    }
    // Solo centramos la cámara automáticamente la primera vez que hay datos (y solo si no se ha
    // restaurado ya una posición recordada) — si lo hiciéramos en cada actualización (cada 15s
    // por el sondeo) se pelearía con que el usuario mueva el mapa a mano. El centrado explícito
    // lo maneja onMapReady + el botón.
    var hasAutoCentered by remember { mutableStateOf(rememberCamera && MapCameraMemory.center != null) }

    DisposableEffect(mapView) {
        onMapReady(mapView)
        onDispose { mapView.onDetach() }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            view.setTileSource(
                when (mapStyle) {
                    MapStyle.SATELLITE -> SatelliteTileSource
                    MapStyle.DARK, MapStyle.STANDARD -> TileSourceFactory.MAPNIK
                },
            )
            view.mapOverlay?.setColorFilter(if (mapStyle == MapStyle.DARK) DARK_MODE_FILTER else null)
            view.overlays.clear()

            if (onMapTap != null) {
                val receiver = object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                        onMapTap(p)
                        return true
                    }

                    override fun longPressHelper(p: GeoPoint): Boolean = false
                }
                view.overlays.add(MapEventsOverlay(receiver))
            }

            zones.forEach { zone ->
                val circle = buildCirclePolygon(GeoPoint(zone.lat, zone.lng), zone.radius_m)
                circle.title = zone.name
                view.overlays.add(circle)
            }

            fun addMemberMarker(member: LocationDto, position: GeoPoint) {
                val marker = Marker(view)
                marker.position = position
                marker.title = member.display_name
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                val avatarBitmap = avatarBitmaps[member.user_id]
                val iconBitmap = if (avatarBitmap != null) {
                    MarkerIconFactory.circularAvatarBitmap(avatarBitmap, MARKER_SIZE_PX)
                } else {
                    MarkerIconFactory.initialsBitmap(member.display_name, member.user_id, MARKER_SIZE_PX)
                }
                marker.icon = BitmapDrawable(context.resources, iconBitmap)
                marker.setOnMarkerClickListener { _, _ -> onMemberClick(member); true }
                view.overlays.add(marker)
            }

            // Agrupa a quienes están (casi) en el mismo punto para separarlos un poco en
            // círculo alrededor del centro — si no, sus marcadores se tapan unos a otros y
            // solo se puede tocar el de arriba del todo.
            members.groupBy { "%.5f,%.5f".format(it.lat, it.lng) }.values.forEach { group ->
                if (group.size == 1) {
                    val member = group[0]
                    addMemberMarker(member, GeoPoint(member.lat, member.lng))
                } else {
                    val center = GeoPoint(group[0].lat, group[0].lng)
                    val offsetMeters = 10.0
                    group.forEachIndexed { index, member ->
                        val bearing = (360.0 / group.size) * index
                        addMemberMarker(member, center.destinationPoint(offsetMeters, bearing))
                    }
                }
            }

            if (!hasAutoCentered) {
                members.firstOrNull()?.let {
                    view.controller.setCenter(GeoPoint(it.lat, it.lng))
                    hasAutoCentered = true
                }
            }

            view.invalidate()
        },
    )
}

private fun buildCirclePolygon(center: GeoPoint, radiusMeters: Double, points: Int = 64): Polygon {
    val polygon = Polygon()
    val geoPoints = (0 until points).map { i ->
        center.destinationPoint(radiusMeters, (360.0 / points * i))
    }
    polygon.points = geoPoints
    polygon.fillColor = 0x334A6FE3
    polygon.strokeColor = 0xFF4A6FE3.toInt()
    polygon.strokeWidth = 2f
    return polygon
}
