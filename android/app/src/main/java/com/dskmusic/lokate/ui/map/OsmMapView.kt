package com.dskmusic.lokate.ui.map

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.data.remote.dto.ZoneDto
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.util.MapStyle
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
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
 * etc.) no la tocan ni la leen, cada una sigue centrando como le convenga. A quién se está
 * siguiendo en vivo NO vive aquí: eso lo guarda MapViewModel, que sobrevive igual a los
 * cambios de pestaña y además es quien renueva la marca en el servidor. */
object MapCameraMemory {
    var center: GeoPoint? = null
    var zoom: Double? = null

    /** Modo prueba de los administradores (ver MapScreen): igual que [followUserId], vive aquí
     * para que cambiar de pestaña no lo apague sin querer — la pantalla del mapa y su ViewModel
     * se destruyen al salir de ella. */
    var testMode: Boolean = false

    /** Se llama al iniciar/cerrar sesión (ver AuthRepository) — sin esto, si un usuario cierra
     * sesión y otro entra en el mismo proceso de la app (p. ej. probando varias cuentas sin
     * matar la app del todo), heredaría la posición del usuario anterior en vez de arrancar
     * centrado en su propia ubicación. */
    fun reset() {
        resetView()
        testMode = false
    }

    /** Solo la posición/zoom recordados (lo que usa "Restablecer" en Ajustes): la próxima vez
     * que se abra el mapa vuelve a centrarse en tu ubicación con el zoom por defecto, sin
     * cancelar de paso el "seguir en vivo", que es otra cosa. */
    fun resetView() {
        center = null
        zoom = null
    }
}

@Composable
fun OsmMapView(
    members: List<LocationDto>,
    zones: List<ZoneDto>,
    modifier: Modifier = Modifier,
    /** Foto de perfil ya descargada por usuario (ver [rememberAvatarBitmaps]); sin entrada -> icono con inicial. */
    avatarBitmaps: Map<String, Bitmap> = emptyMap(),
    onMemberClick: (LocationDto) -> Unit = {},
    /** Miembros cuyo marcador se puede arrastrar (modo prueba de los admins): se agarra
     * manteniéndolo pulsado, como cualquier marcador arrastrable de osmdroid. */
    draggableUserIds: Set<String> = emptySet(),
    /** Solo al soltar: arrastrar dispara una comprobación de zonas en el servidor, no una por
     * cada píxel que se mueve el dedo. */
    onMemberDragEnd: (LocationDto, GeoPoint) -> Unit = { _, _ -> },
    onMapTap: ((GeoPoint) -> Unit)? = null,
    /** Entrega la instancia real de MapView una vez lista, para poder centrarla bajo demanda (botón "Dónde estoy"). */
    onMapReady: (MapView) -> Unit = {},
    initialZoom: Double = Constants.MAP_DEFAULT_ZOOM.toDouble(),
    mapStyle: MapStyle = MapStyle.STANDARD,
    /** true en el mapa principal: al volver de otra pestaña, restaura la posición/zoom donde
     * se dejó el mapa en vez de recentrar solo. false (por defecto) en el resto de mapas de la
     * app (crear/editar zona, ...), que ya se centran a su manera. */
    rememberCamera: Boolean = false,
) {
    val context = LocalContext.current
    val offlineFiles = rememberOfflineMapFiles()
    // Si hay cámara recordada manda ella y el zoom del ajuste no se toca (se calcula una sola
    // vez, antes de crear el MapView: en cuanto el usuario mueve el mapa MapCameraMemory ya
    // tiene valores y esto pasaría a ser true por accidente).
    val cameraRestored = remember {
        rememberCamera && MapCameraMemory.center != null && MapCameraMemory.zoom != null
    }
    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            // El rocker +/- nativo de osmdroid se solapaba con los FAB de Compose (pellizcar
            // para hacer zoom ya cubre lo mismo).
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)

            if (cameraRestored) {
                controller.setCenter(MapCameraMemory.center!!)
                controller.setZoom(MapCameraMemory.zoom!!)
            } else {
                controller.setZoom(initialZoom)
            }

            // osmdroid solo pide teselas al dibujarse y calcula qué pedir a partir del tamaño de
            // la vista; en la primera composición aún mide 0, así que el zoom/centro de arriba se
            // fijan sobre un área vacía y el mapa sale en blanco hasta que lo tocas. Al llegar el
            // primer layout con tamaño real, reaplicar el zoom recalcula el área visible y fuerza
            // a pedir las teselas — pasa igual al abrir la app, al cambiar de estilo y al volver
            // de otra pestaña, porque en los tres casos se crea un MapView nuevo.
            addOnFirstLayoutListener { _, _, _, _, _ ->
                controller.setZoom(zoomLevelDouble)
                invalidate()
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
    var hasAutoCentered by remember { mutableStateOf(cameraRestored) }

    // initialZoom viene de DataStore (asíncrono): en la primera composición todavía es el valor
    // por defecto y el guardado llega un instante después, así que hay que volver a aplicarlo.
    // Sin esto el ajuste se respetaba o no según lo que tardase DataStore ("a veces" abría con
    // otro zoom) y, si abría más cerca de lo pedido, encima cargaba teselas de más para nada.
    LaunchedEffect(initialZoom) {
        if (!cameraRestored) mapView.controller.setZoom(initialZoom)
    }

    DisposableEffect(mapView) {
        onMapReady(mapView)
        onDispose { mapView.onDetach() }
    }

    // El sondeo trae la lista de miembros entera cada ~15s aunque nadie se haya movido, y este
    // bloque se ejecuta en cada recomposición: sin la firma se rehacían todos los marcadores
    // (recortando de nuevo el bitmap de cada avatar) y se repintaba el mapa para nada.
    val overlaySignature = buildString {
        append(mapStyle).append('#').append(onMapTap != null).append('#')
        // La batería entra en la firma porque el anillo del marcador la pinta: si no, el
        // marcador se quedaría con el anillo del primer sondeo para siempre.
        members.forEach {
            append(it.user_id).append(it.lat).append(',').append(it.lng).append('@').append(it.battery_level).append(';')
        }
        append('#')
        zones.forEach { append(it.id).append(it.lat).append(',').append(it.lng).append(it.radius_m).append(';') }
        append('#')
        avatarBitmaps.keys.sorted().forEach { append(it).append(';') }
        append('#')
        draggableUserIds.sorted().forEach { append(it).append(';') }
    }
    // Array de 1 y no mutableStateOf a propósito: apuntar qué se dibujó no debe disparar otra
    // recomposición.
    val drawnSignature = remember { arrayOfNulls<String>(1) }

    // Sin conexión pero mirando una zona sin descargar -> mapa de internet, con aviso.
    val effectiveStyle = rememberEffectiveMapStyle(mapView, mapStyle, offlineFiles)

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            view.applyMapStyle(effectiveStyle, offlineFiles)

            if (drawnSignature[0] != overlaySignature) {
                drawnSignature[0] = overlaySignature
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
                        MarkerIconFactory.circularAvatarBitmap(avatarBitmap, MARKER_SIZE_PX, member.battery_level)
                    } else {
                        MarkerIconFactory.initialsBitmap(
                            member.display_name, member.user_id, MARKER_SIZE_PX, member.battery_level,
                        )
                    }
                    marker.icon = BitmapDrawable(context.resources, iconBitmap)
                    marker.setOnMarkerClickListener { _, _ -> onMemberClick(member); true }
                    if (member.user_id in draggableUserIds) {
                        marker.isDraggable = true
                        marker.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                            override fun onMarkerDragStart(m: Marker) = Unit
                            override fun onMarkerDrag(m: Marker) = Unit
                            override fun onMarkerDragEnd(m: Marker) = onMemberDragEnd(member, m.position)
                        })
                    }
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

                view.invalidate()
            }

            if (!hasAutoCentered) {
                members.firstOrNull()?.let {
                    view.controller.setCenter(GeoPoint(it.lat, it.lng))
                    hasAutoCentered = true
                }
            }
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
