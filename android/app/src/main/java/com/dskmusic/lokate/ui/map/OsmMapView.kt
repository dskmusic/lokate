package com.dskmusic.lokate.ui.map

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.data.remote.dto.ZoneDto
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.util.MapStyle
import com.dskmusic.lokate.util.distanceMeters
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon

internal const val MARKER_SIZE_PX = 120

/** Lo que mide el punto de quien navega. Como el de medir distancias, que se ve bien sin tapar
 * la calle por la que se va. */
internal const val NAV_DOT_DP = 22

/** Lo que hay que haberse movido para recalcular el rumbo. Parado, dos posiciones seguidas se
 * diferencian solo en el ruido del GPS, y sin este mínimo el mapa se pondría a dar vueltas solo
 * en cada semáforo. */
internal const val NAV_BEARING_MIN_M = 12.0

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
    /** Dedo quieto [Constants.MAP_LONG_PRESS_MS] sobre un sitio del mapa. No se usa el
     * longPressHelper de osmdroid porque ese va con los ~500 ms del sistema y no se puede
     * alargar (ver [TouchOverlay]). */
    onMapLongPress: ((GeoPoint) -> Unit)? = null,
    /** El dedo ha arrastrado el mapa. Con esto quien mira dice "quédate aquí": lo usa
     * [MapScreen] para soltar el autocentrado de la navegación. Una vez por gesto. */
    onUserPan: () -> Unit = {},
    /** La ruta del "cómo llegar" ya trazada (ver [RoutePlanner]). Solo se pinta: no se toca
     * ni se edita, y va debajo de los marcadores para no robarles el toque. */
    routePoints: List<GeoPoint> = emptyList(),
    /** Hasta dónde llega alguien en 5, 10 y 15 minutos (ver [IsochroneState]). Se pinta debajo
     * de todo: son manchas grandes y taparían las zonas y a la gente. */
    isochroneRings: List<IsoRing> = emptyList(),
    /** Dónde va quien está navegando ahora mismo (ver [com.dskmusic.lokate.nav.NavSession]).
     * Tiene marcador propio porque se mueve cada segundo, y también porque navegando simulado
     * no coincide con la posición que el servidor tiene de nadie. */
    navPoint: GeoPoint? = null,
    /** Orienta el mapa hacia donde va [navPoint], como un navegador de coche. El rumbo sale de
     * por dónde se ha venido y no del GPS, así que vale igual para la ruta simulada. */
    navRotate: Boolean = false,
    /** El sitio que ha salido del buscador: una chincheta, para ver dónde cae exactamente. */
    searchPoint: GeoPoint? = null,
    searchLabel: String = "",
    /** Toque en esa chincheta — con esto se abre su ficha de dirección. */
    onSearchPointClick: () -> Unit = {},
    /** Puntos de la regla (modo medir de [MapScreen]): línea, distancia de cada tramo y un punto
     * agarrable por cada uno. Vacío = modo apagado, no se dibuja nada. */
    measurePoints: List<GeoPoint> = emptyList(),
    /** Toque en un punto de la regla: se borra. */
    onMeasurePointDelete: (Int) -> Unit = {},
    /** Punto de la regla soltado tras arrastrarlo. */
    onMeasurePointMoved: (Int, GeoPoint) -> Unit = { _, _ -> },
    /** Toque sobre la línea de la regla: un punto nuevo en esa posición de la lista. */
    onMeasurePointInsert: (Int, GeoPoint) -> Unit = { _, _ -> },
    /** Se acaba de agarrar un punto de la regla (para el tic de radar). */
    onMeasurePointGrab: (GeoPoint) -> Unit = {},
    /** Metros de la regla mientras un punto va colgado del dedo; null al soltar. */
    onMeasureLiveTotal: (Double?) -> Unit = {},
    /** Entrega la instancia real de MapView una vez lista, para poder centrarla bajo demanda (botón "Dónde estoy"). */
    onMapReady: (MapView) -> Unit = {},
    initialZoom: Double = Constants.MAP_DEFAULT_ZOOM.toDouble(),
    mapStyle: MapStyle = MapStyle.STANDARD,
    /** Pinta el margen de error de cada posición (ajuste "mostrar precisión"). */
    showAccuracy: Boolean = false,
    /** Lo marcado que se ve ese círculo, 10-100 (ver [buildAccuracyPolygon]). */
    accuracyIntensity: Int = Constants.MAP_ACCURACY_INTENSITY_DEFAULT,
    /** Color del círculo del margen de error y del nombre que lleva debajo. */
    accuracyColor: Int = Constants.MAP_ACCURACY_COLOR_DEFAULT,
    /** Escribe el nombre de cada persona junto a su círculo de precisión (ver [buildAccuracyLabel]). */
    showAccuracyLabels: Boolean = false,
    /** Pinta la franja del margen de seguridad de cada zona (ver [buildZoneMarginPolygon]). */
    showZoneMargin: Boolean = false,
    /** Color del anillo del margen de seguridad. */
    zoneMarginColor: Int = Constants.MAP_ZONE_MARGIN_COLOR_DEFAULT,
    /** Opacidad del relleno de las zonas, 0-100 (0 = solo el borde). */
    zoneFillIntensity: Int = Constants.MAP_ZONE_FILL_INTENSITY_DEFAULT,
    /** Color del borde, del relleno y del rótulo de las zonas. */
    zoneColor: Int = Constants.MAP_ZONE_COLOR_DEFAULT,
    /** true en el mapa principal: al volver de otra pestaña, restaura la posición/zoom donde
     * se dejó el mapa en vez de recentrar solo. false (por defecto) en el resto de mapas de la
     * app (crear/editar zona, ...), que ya se centran a su manera. */
    rememberCamera: Boolean = false,
    /** Con quién se abre el mapa la primera vez que llegan posiciones. Vacío = el primero de la
     * lista, como siempre. null = el ajuste todavía no ha llegado del disco, y entonces no se
     * centra en nadie: hacerlo sería abrir siempre en el primero por ganarle la carrera a
     * DataStore. */
    autoCenterUserId: String? = "",
) {
    val context = LocalContext.current
    val offlineFiles = rememberOfflineMapFiles()
    // Si hay cámara recordada manda ella y el zoom del ajuste no se toca (se calcula una sola
    // vez, antes de crear el MapView: en cuanto el usuario mueve el mapa MapCameraMemory ya
    // tiene valores y esto pasaría a ser true por accidente).
    val cameraRestored = remember {
        rememberCamera && MapCameraMemory.center != null && MapCameraMemory.zoom != null
    }
    // Los marcadores que se taparian se reparten en circulo y esa separacion se mide en
    // pixeles, asi que al cambiar el zoom hay que rehacerlos (ver [spreadOverlapping]). Se
    // guarda redondeado a medio nivel para no repintarlo todo por cada pellizco.
    val spreadZoom = remember { mutableStateOf(initialZoom) }
    // Se queda con la ultima devolucion de llamada sin recrear la capa: los overlays se borran
    // y se vuelven a anadir en cada repintado, y una capa nueva cada vez dejaria esperas de 1,5 s
    // colgadas de instancias que ya nadie mira.
    val longPress = rememberUpdatedState(onMapLongPress)
    val pan = rememberUpdatedState(onUserPan)
    val touchOverlay = remember {
        TouchOverlay({ point -> longPress.value?.invoke(point) }, { pan.value() })
    }
    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean = false

                override fun onZoom(event: ZoomEvent?): Boolean {
                    val rounded = (zoomLevelDouble * 2).roundToInt() / 2.0
                    if (rounded != spreadZoom.value) spreadZoom.value = rounded
                    return false
                }
            })
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

    TileRetryEffect(mapView)

    // El sondeo trae la lista de miembros entera cada ~15s aunque nadie se haya movido, y este
    // bloque se ejecuta en cada recomposición: sin la firma se rehacían todos los marcadores
    // (recortando de nuevo el bitmap de cada avatar) y se repintaba el mapa para nada.
    val overlaySignature = buildString {
        append(mapStyle).append('#').append(onMapTap != null).append('#')
        append(spreadZoom.value).append('#').append(showAccuracy).append(accuracyIntensity)
        append(showAccuracyLabels)
        append(accuracyColor).append(showZoneMargin).append(zoneMarginColor)
        append(zoneFillIntensity).append(zoneColor).append('#')
        // La batería entra en la firma porque el anillo del marcador la pinta: si no, el
        // marcador se quedaría con el anillo del primer sondeo para siempre.
        members.forEach {
            append(it.user_id).append(it.lat).append(',').append(it.lng)
            // El nombre se pinta (título del marcador y etiqueta del círculo): si no entra en la
            // firma, quien se cambie el nombre sigue saliendo con el viejo hasta reiniciar.
            append('@').append(it.display_name)
            append('@').append(it.battery_level).append(it.is_charging).append(';')
        }
        append('#')
        // El nombre entra en la firma porque se pinta como etiqueta: sin él, renombrar una
        // zona dejaba el rótulo viejo en el mapa.
        zones.forEach {
            append(it.id).append(it.lat).append(',').append(it.lng).append(it.radius_m)
            append('@').append(it.name).append(';')
        }
        append('#')
        // La foto en sí y no solo de quién es: al cambiarla, el mapa recibe otro bitmap para el
        // mismo usuario, y con la firma hecha solo de las claves el marcador no se repintaba.
        avatarBitmaps.entries.sortedBy { it.key }.forEach {
            append(it.key).append(System.identityHashCode(it.value)).append(';')
        }
        append('#')
        draggableUserIds.sorted().forEach { append(it).append(';') }
        append('#')
        measurePoints.forEach { append(it.latitude).append(',').append(it.longitude).append(';') }
        append('#')
        // Solo el tamano y las puntas: una ruta trae cientos de puntos y rehacer esta cadena
        // entera en cada repintado costaria mas que dibujarla. Dos rutas con el mismo numero de
        // puntos, el mismo principio y el mismo final son la misma ruta.
        append(routePoints.size).append(routePoints.firstOrNull()).append(routePoints.lastOrNull())
        isochroneRings.forEach { append(it.minutes).append(':').append(it.points.size).append(';') }
        append('#').append(searchPoint).append(searchLabel)
    }
    // Array de 1 y no mutableStateOf a propósito: apuntar qué se dibujó no debe disparar otra
    // recomposición.
    val drawnSignature = remember { arrayOfNulls<String>(1) }

    // El marcador de la navegación va aparte y NO entra en la firma de capas: navegando llega
    // una posición por segundo, y rehacer con cada una todas las capas —zonas, avatares, la
    // ruta entera— sería tirar el mapa a la basura y volver a construirlo sesenta veces por
    // minuto. Así solo se le cambia la posición a un marcador.
    // Desde dónde se midió el rumbo la última vez. Array de 1 por lo mismo que drawnSignature:
    // apuntarlo no debe disparar otra recomposición.
    val bearingAnchor = remember { arrayOfNulls<GeoPoint>(1) }
    val navMarker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            isDraggable = false
            // Sin globo: navegando, un toque sin querer no debe abrir un bocadillo encima de
            // la indicación.
            infoWindow = null
            // Y con icono propio: el pincho verde de osmdroid mide media pantalla y dibuja su
            // punta donde NO está la posición, así que anclado al centro parecía ir por la
            // acera de enfrente. Un punto redondo cae justo donde cae.
            val density = mapView.context.resources.displayMetrics.density
            icon = BitmapDrawable(
                mapView.context.resources,
                MarkerIconFactory.dotBitmap(DIRECTIONS_COLOR, (NAV_DOT_DP * density).toInt()),
            )
        }
    }

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
                view.overlays.add(touchOverlay)

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

                // Debajo de todo lo demás: las zonas, la ruta y la gente se tienen que seguir
                // viendo (y tocando) con los anillos puestos.
                isochroneRings.forEach { ring ->
                    val color = isochroneColor(ring.minutes)
                    val polygon = Polygon()
                    polygon.points = ring.points
                    polygon.fillColor = withAlpha(color, 0x3A)
                    polygon.strokeColor = color
                    polygon.strokeWidth = 2f
                    // Un polígono de osmdroid se queda el toque por defecto, y estos cubren media
                    // pantalla: sin esto no se podría tocar a nadie que esté dentro.
                    polygon.setOnClickListener { _, _, _ -> false }
                    view.overlays.add(polygon)
                }

                zones.forEach { zone ->
                    // El margen primero: es la capa de debajo, y así el borde de la zona queda
                    // nítido encima de la línea de puntos en vez de medio tapado por ella.
                    val center = GeoPoint(zone.lat, zone.lng)
                    if (showZoneMargin) {
                        view.overlays.add(buildHaloPolygon(center, zone.radius_m + Constants.ZONE_EXIT_MARGIN_METERS, dashed = true))
                        view.overlays.add(
                            buildZoneMarginPolygon(center, zone.radius_m, zoneFillIntensity, zoneMarginColor),
                        )
                    }
                    view.overlays.add(buildHaloPolygon(center, zone.radius_m, dashed = false))
                    val circle = buildZonePolygon(center, zone.radius_m, zoneFillIntensity, zoneColor)
                    circle.title = zone.name
                    // Un polígono de osmdroid se queda el toque por defecto (para enseñar su
                    // nombre). Cuando alguien espera los toques del mapa — medir, o elegir dónde
                    // va una zona — eso significa no poder tocar dentro de un círculo, así que
                    // ahí se le deja pasar.
                    if (onMapTap != null) circle.setOnClickListener { _, _, _ -> false }
                    view.overlays.add(circle)
                    if (zone.name.isNotBlank()) view.overlays.add(buildZoneLabel(view, zone, zoneColor))
                }

                fun addMemberMarker(member: LocationDto, position: GeoPoint) {
                    val marker = Marker(view)
                    marker.position = position
                    marker.title = member.display_name
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    val avatarBitmap = avatarBitmaps[member.user_id]
                    val iconBitmap = if (avatarBitmap != null) {
                        MarkerIconFactory.circularAvatarBitmap(
                            avatarBitmap, MARKER_SIZE_PX, member.battery_level, member.is_charging == true,
                        )
                    } else {
                        MarkerIconFactory.initialsBitmap(
                            member.display_name, member.user_id, MARKER_SIZE_PX,
                            member.battery_level, member.is_charging == true,
                        )
                    }
                    marker.icon = BitmapDrawable(context.resources, iconBitmap)
                    marker.setOnMarkerClickListener { _, _ ->
                        // Mismo motivo que los círculos de zona: midiendo, un avatar (que ocupa
                        // lo suyo) taparía un trozo de mapa donde no se podría poner un punto.
                        if (onMapTap != null) return@setOnMarkerClickListener false
                        onMemberClick(member)
                        true
                    }
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

                // Cuantos metros mide un pixel aqui y ahora: lo sabe la propia proyeccion del
                // mapa (tiene en cuenta el zoom, la latitud y el tamano de tesela).
                val metersPerPixel = 1.0 / view.projection.metersToPixels(1f).toDouble().coerceAtLeast(1e-9)
                val placed = spreadOverlapping(members, metersPerPixel)

                // TODOS los círculos de precisión antes que CUALQUIER marcador: osmdroid prueba
                // las capas de arriba a abajo, así que un círculo añadido después de un marcador
                // queda por encima de él y le roba el toque (el de quien esté al lado, incluso).
                // Se dibujan en la posición ya separada y no en la real: si no, el círculo se
                // vería despegado del marcador al que pertenece (ver [spreadOverlapping]).
                if (showAccuracy) {
                    placed.forEach { (member, position) ->
                        val accuracy = member.accuracy?.toDouble() ?: 0.0
                        if (accuracy > 0.0) {
                            view.overlays.add(
                                buildAccuracyPolygon(position, accuracy, accuracyIntensity, accuracyColor),
                            )
                            if (showAccuracyLabels && member.display_name.isNotBlank()) {
                                view.overlays.add(
                                    buildAccuracyLabel(view, position, member.display_name, accuracy, accuracyColor),
                                )
                            }
                        }
                    }
                }
                // Antes de la gente: la ruta pasa por debajo de los avatares, que siguen
                // siendo lo que se toca para abrir una ficha.
                view.addRouteOverlay(routePoints)

                // Antes que la gente, por lo mismo que la ruta: si la chincheta cae encima de
                // un avatar, manda el avatar.
                searchPoint?.let { punto ->
                    view.overlays.add(
                        Marker(view).apply {
                            position = punto
                            title = searchLabel
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            setOnMarkerClickListener { _, _ ->
                                // Midiendo no: ahí el toque es para poner puntos de la regla.
                                if (onMapTap != null) false else { onSearchPointClick(); true }
                            }
                        },
                    )
                }

                placed.forEach { (member, position) -> addMemberMarker(member, position) }

                // La última capa, para que sus puntos ganen el toque a todo lo demás.
                view.addMeasureOverlays(
                    measurePoints,
                    onMeasurePointDelete,
                    onMeasurePointMoved,
                    onMeasurePointInsert,
                    onMeasurePointGrab,
                    onMeasureLiveTotal,
                )

                view.invalidate()
            }

            // Después del bloque de arriba a propósito: si se han rehecho las capas, el
            // marcador de navegación se vuelve a poner aquí.
            if (navPoint == null) {
                if (view.overlays.remove(navMarker)) view.invalidate()
                // Al salir de la navegación, el norte vuelve arriba.
                if (view.mapOrientation != 0f) {
                    view.mapOrientation = 0f
                    bearingAnchor[0] = null
                    view.invalidate()
                }
            } else {
                navMarker.position = navPoint
                if (!view.overlays.contains(navMarker)) view.overlays.add(navMarker)
                if (navRotate) {
                    val previo = bearingAnchor[0]
                    if (previo == null || previo.distanceToAsDouble(navPoint) >= NAV_BEARING_MIN_M) {
                        // El primer punto solo sirve para medir desde él: con uno solo no hay
                        // rumbo que sacar.
                        if (previo != null) {
                            view.mapOrientation = -previo.bearingTo(navPoint).toFloat()
                        }
                        bearingAnchor[0] = navPoint
                    }
                } else if (view.mapOrientation != 0f) {
                    view.mapOrientation = 0f
                    bearingAnchor[0] = null
                }
                view.invalidate()
            }

            if (!hasAutoCentered && autoCenterUserId != null) {
                // El elegido en Ajustes si tiene posición; si no la tiene (nunca ha mandado una,
                // o ya no está en el grupo) se abre como siempre, que es mejor que abrir en el
                // mar.
                val elegido = members.firstOrNull { it.user_id == autoCenterUserId }
                    ?: members.firstOrNull()
                elegido?.let {
                    view.controller.setCenter(GeoPoint(it.lat, it.lng))
                    hasAutoCentered = true
                }
            }
        },
    )
}

/**
 * Reparte en circulo a quienes caen tan cerca que sus marcadores se taparian, y deja al
 * resto en su sitio. La separacion se calcula en pixeles y no en metros fijos: dos personas
 * a 10 m son dos marcadores pegados en un mapa de ciudad, asi que un desplazamiento fijo en
 * metros no separa nada al alejar el zoom (y separa de mas al acercarlo, mintiendo sobre
 * donde esta cada uno). El radio sale de la geometria del circulo: con [n] marcadores de
 * [MARKER_SIZE_PX] repartidos, la distancia entre vecinos es 2*r*sin(pi/n), asi que ese es
 * el radio minimo para que no se toquen.
 */
internal fun spreadOverlapping(
    members: List<LocationDto>,
    metersPerPixel: Double,
): List<Pair<LocationDto, GeoPoint>> {
    val clusterMeters = MARKER_SIZE_PX * metersPerPixel
    // ponytail: O(n^2) sobre los miembros del grupo. Son una familia, no un estadio.
    val clusters = mutableListOf<MutableList<LocationDto>>()
    members.forEach { member ->
        val near = clusters.firstOrNull {
            distanceMeters(it[0].lat, it[0].lng, member.lat, member.lng) <= clusterMeters
        }
        if (near != null) near.add(member) else clusters.add(mutableListOf(member))
    }
    return clusters.flatMap { group ->
        if (group.size == 1) {
            listOf(group[0] to GeoPoint(group[0].lat, group[0].lng))
        } else {
            val center = GeoPoint(group.map { it.lat }.average(), group.map { it.lng }.average())
            val radiusMeters = MARKER_SIZE_PX * 0.55 / sin(PI / group.size) * metersPerPixel
            group.sortedBy { it.user_id }.mapIndexed { index, member ->
                member to center.destinationPoint(radiusMeters, 360.0 / group.size * index)
            }
        }
    }
}

/** Negro al 45% por debajo de cada línea: sobre satélite, una línea de color sola se pierde en
 * cuanto cruza un tejado claro o una sombra, y era justo lo que pasaba antes. */
internal val MAP_HALO_BLACK = 0x73000000

/** Opacidad del relleno de una zona al 100% de intensidad. Tope bajo a propósito: el relleno es
 * para ubicar la zona de un vistazo, no para tapar el mapa que hay debajo. */
private const val ZONE_FILL_MAX_ALPHA = 77

internal const val ZONE_LABEL_SP = 12f

/** El nombre de una persona va más pequeño que el de una zona: es una aclaración de quién es
 * ese círculo, no un rótulo del mapa. */
internal const val ACCURACY_LABEL_SP = 9.5f
private const val LABEL_BACKGROUND_WHITE = 0xCCFFFFFF.toInt()
private const val ACCURACY_LABEL_BACKGROUND = 0x99FFFFFF.toInt()
internal const val LABEL_MAX_CHARS = 18

/** Lo claro que se le permite ser a la letra de una etiqueta sobre su recuadro blanquecino. Un
 * ámbar o un gris claro elegidos para el círculo no se leerían ahí tal cual. */
private const val LABEL_MAX_LUMINANCE = 0.40f

/** El mismo color con otra transparencia. */
private fun withAlpha(color: Int, alpha: Int): Int =
    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

/** El mismo color más oscuro ([factor] < 1), manteniendo su transparencia. */
private fun darken(color: Int, factor: Float): Int = Color.argb(
    Color.alpha(color),
    (Color.red(color) * factor).toInt(),
    (Color.green(color) * factor).toInt(),
    (Color.blue(color) * factor).toInt(),
)

/** Luminancia a ojo (los pesos de siempre del vídeo). ponytail: no hace falta sRGB de verdad
 * para decidir si un color se lee o no sobre un fondo claro. */
private fun roughLuminance(color: Int): Float =
    (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f

/** El color elegido, oscurecido solo si hace falta para que el texto se lea. */
internal fun labelTextColor(color: Int): Int {
    val luminance = roughLuminance(color)
    return if (luminance > LABEL_MAX_LUMINANCE) darken(color, LABEL_MAX_LUMINANCE / luminance) else color
}

/** Aclara u oscurece el relleno sin tocar el color: así el ámbar del margen puede ir más flojo
 * que el verde de la zona con el mismo ajuste del usuario. */
internal fun zoneFillColor(baseColor: Int, intensity: Int, scale: Float = 1f): Int =
    withAlpha(baseColor, (ZONE_FILL_MAX_ALPHA * scale * intensity.coerceIn(0, 100) / 100).toInt())

/** El margen de error de una posición: gris por defecto, para que no compita con las zonas, que
 * sí significan algo que el usuario ha creado. [intensity] (10-100) es cuánto se ve: el color que
 * se lee bien sobre el mapa estándar desaparece sobre el satélite, así que es un ajuste — y por lo
 * mismo [color] también se elige en Ajustes. El borde va algo más oscuro que el relleno. */
private fun buildAccuracyPolygon(center: GeoPoint, radiusMeters: Double, intensity: Int, color: Int): Polygon {
    val strength = intensity.coerceIn(10, 100) / 100f
    val polygon = buildCirclePolygon(center, radiusMeters, points = 32)
    polygon.fillColor = withAlpha(color, (90 * strength).toInt())
    polygon.strokeColor = withAlpha(darken(color, 0.82f), (255 * strength).toInt())
    polygon.strokeWidth = 1f + strength
    // Sin esto, el círculo se traga el toque destinado al marcador que hay dentro: osmdroid da
    // por consumido el evento en cuanto un polígono lo acepta, y el suyo lo acepta por defecto.
    polygon.setOnClickListener { _, _, _ -> false }
    return polygon
}

/** Sombra de un borde de zona, para dibujarla justo DEBAJO de él. */
private fun buildHaloPolygon(center: GeoPoint, radiusMeters: Double, dashed: Boolean): Polygon {
    val polygon = buildCirclePolygon(center, radiusMeters)
    polygon.fillColor = Color.TRANSPARENT
    polygon.strokeColor = MAP_HALO_BLACK
    polygon.strokeWidth = 6f
    if (dashed) polygon.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    polygon.setOnClickListener { _, _, _ -> false }
    return polygon
}

/** El círculo de una zona: borde grueso del color elegido y relleno del mismo color a la
 * opacidad que haya puesta ([fillIntensity], el ajuste "relleno de las zonas"). */
private fun buildZonePolygon(center: GeoPoint, radiusMeters: Double, fillIntensity: Int, color: Int): Polygon {
    val polygon = buildCirclePolygon(center, radiusMeters)
    polygon.fillColor = zoneFillColor(color, fillIntensity)
    polygon.strokeColor = color
    polygon.strokeWidth = 3f
    return polygon
}

/**
 * Un texto plantado en el mapa. Tamaño fijo en píxeles: es un bitmap de marcador, así que no
 * crece al acercar el zoom (que es lo que haría pintándolo dentro del polígono), y los textos
 * largos se cortan para que no tapen media pantalla.
 *
 * [anchorV] es qué borde del recuadro se clava en [position]: ANCHOR_BOTTOM lo deja por encima
 * del punto y ANCHOR_TOP por debajo.
 */
private fun buildTextLabel(
    map: MapView,
    position: GeoPoint,
    text: String,
    sizeSp: Float,
    foreground: Int,
    background: Int,
    anchorV: Float,
): Marker {
    val marker = Marker(map)
    marker.position = position
    marker.setTextLabelFontSize((sizeSp * map.resources.displayMetrics.density).toInt())
    marker.setTextLabelForegroundColor(foreground)
    marker.setTextLabelBackgroundColor(background)
    // setTextIcon antes del anchor, no después: monta el bitmap con el tamaño y los colores que
    // haya puestos ya, y de paso recoloca el anchor al centro, pisando lo que se le ponga antes.
    // Los espacios son el único relleno que tiene el recuadro (lo pinta pegado al texto).
    val clean = text.trim()
    marker.setTextIcon(" " + (if (clean.length > LABEL_MAX_CHARS) clean.take(LABEL_MAX_CHARS) + "…" else clean) + " ")
    marker.setAnchor(Marker.ANCHOR_CENTER, anchorV)
    // Un rótulo no es un botón: ni ventana de información ni toques robados al mapa.
    marker.setInfoWindow(null)
    marker.setOnMarkerClickListener { _, _ -> false }
    return marker
}

/** El nombre de la zona, sobre su borde de arriba y del color de la zona. */
private fun buildZoneLabel(map: MapView, zone: ZoneDto, color: Int): Marker = buildTextLabel(
    map,
    GeoPoint(zone.lat, zone.lng).destinationPoint(zone.radius_m, 0.0),
    zone.name, ZONE_LABEL_SP, labelTextColor(color), LABEL_BACKGROUND_WHITE, Marker.ANCHOR_BOTTOM,
)

/** De quién es ese círculo de precisión: debajo de su borde inferior, para no tapar al avatar
 * que está justo en el centro. */
private fun buildAccuracyLabel(
    map: MapView,
    center: GeoPoint,
    name: String,
    accuracyMeters: Double,
    color: Int,
): Marker = buildTextLabel(
    map, center.destinationPoint(accuracyMeters, 180.0), name,
    ACCURACY_LABEL_SP, labelTextColor(color), ACCURACY_LABEL_BACKGROUND, Marker.ANCHOR_TOP,
)

/**
 * El margen de seguridad de una zona: la franja de fuera que hay que pisar para que una salida
 * cuente (ver [Constants.ZONE_EXIT_MARGIN_METERS]). Línea discontinua y relleno aún más flojo
 * que el de la zona — se ve que es el borde de algo, sin pelearse con ella. Lleva su propio
 * [color] (ámbar por defecto) porque es otra cosa y se tiene que distinguir de la zona.
 *
 * Es un anillo de verdad (con agujero), no un círculo más grande: superponiendo otro disco se
 * sumarían las dos transparencias y el interior de la zona saldría más oscuro de lo que es.
 */
private fun buildZoneMarginPolygon(
    center: GeoPoint,
    radiusMeters: Double,
    fillIntensity: Int,
    color: Int,
): Polygon {
    val polygon = buildCirclePolygon(center, radiusMeters + Constants.ZONE_EXIT_MARGIN_METERS)
    // setHoles y no la propiedad: la firma de osmdroid lleva comodín (List<? extends List<...>>),
    // así que Kotlin no sintetiza un "holes" asignable.
    polygon.setHoles(listOf(circlePoints(center, radiusMeters)))
    polygon.fillColor = zoneFillColor(color, fillIntensity, scale = 0.6f)
    polygon.strokeColor = color
    polygon.strokeWidth = 2.5f
    polygon.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    // Decoración y nada más: ni se queda los toques del mapa ni los de quien ande por la franja.
    polygon.setOnClickListener { _, _, _ -> false }
    return polygon
}

/** El mismo anillo que usa el mapa de MapLibre (ver [circleRing]), en GeoPoint. */
private fun circlePoints(center: GeoPoint, radiusMeters: Double, points: Int = 64): List<GeoPoint> =
    circleRing(center.latitude, center.longitude, radiusMeters, points).map { GeoPoint(it.lat, it.lng) }

private fun buildCirclePolygon(center: GeoPoint, radiusMeters: Double, points: Int = 64): Polygon {
    val polygon = Polygon()
    polygon.points = circlePoints(center, radiusMeters, points)
    polygon.fillColor = 0x334A6FE3
    polygon.strokeColor = 0xFF4A6FE3.toInt()
    polygon.strokeWidth = 2f
    return polygon
}

/**
 * Encaja el círculo entero de una zona en el mapa, con margen para que no toque los bordes.
 * zoomToBoundingBox y no animateTo: el punto puede llegar casi a la vez que el mapa termina de
 * montarse, y encadenar animaciones ahí es lo que daba la sensación de que el mapa "se vuelve
 * loco" moviéndose solo.
 */
fun MapView.frameZone(lat: Double, lng: Double, radiusM: Double) {
    // Margen del 40% alrededor del radio.
    val deltaLat = (radiusM * 1.4) / 111_320.0
    val deltaLng = deltaLat / kotlin.math.max(kotlin.math.cos(Math.toRadians(lat)), 0.2)
    val box = org.osmdroid.util.BoundingBox(lat + deltaLat, lng + deltaLng, lat - deltaLat, lng - deltaLng)
    post {
        runCatching { zoomToBoundingBox(box, false) }
            .onFailure { controller.setCenter(GeoPoint(lat, lng)) }
    }
}


/**
 * Los dos gestos del mapa que osmdroid no da hechos: mantener pulsado un sitio con el tiempo que
 * queramos ([Constants.MAP_LONG_PRESS_MS]) en vez de los ~500 ms del sistema, y avisar de que el
 * dedo ha arrastrado el mapa ([onPan], una sola vez por gesto y solo con un dedo: el pellizco
 * del zoom no mueve el mapa de sitio y no tiene por qué contar).
 *
 * Avisa sin esperar a que se levante el dedo —es como se nota una pulsacion larga— y se cancela
 * en cuanto el dedo se mueve mas de lo que el sistema considera temblor o en cuanto aparece un
 * segundo dedo, para no pisar el arrastre ni el pellizco del mapa. Nunca se queda el gesto
 * (siempre devuelve false), asi que todo lo demas del mapa sigue funcionando igual.
 *
 * ponytail: no sabe si otra capa se ha quedado con el toque. Solo se nota manteniendo el dedo
 * completamente quieto sobre un marcador en modo prueba: salta el cuadro ademas de empezar a
 * arrastrarlo. Si llega a molestar, la salida es que MapScreen no pase devolucion de llamada
 * mientras el modo prueba este encendido, como ya hace con el de medir.
 */
private class TouchOverlay(
    private val onLongPress: (GeoPoint) -> Unit,
    private val onPan: () -> Unit,
) : Overlay() {

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var downX = 0f
    private var downY = 0f
    /** Si este gesto ya ha contado como arrastre: sin esto saldrían sesenta avisos por segundo. */
    private var panned = false

    /** Si en este gesto ha habido más de un dedo. Se queda puesto hasta que empieza el gesto
     * siguiente: soltar un dedo a mitad de pellizco deja el otro moviéndose, y eso sigue siendo
     * el zoom, no un arrastre. */
    private var pellizco = false

    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                panned = false
                pellizco = false
                // El punto se calcula AHORA: si se calculase al disparar, un mapa que se hubiera
                // movido entre medias daria la direccion de otro sitio.
                val point = mapView.projection.fromPixels(event.x.toInt(), event.y.toInt()) as GeoPoint
                cancel()
                pending = Runnable {
                    // El tic de siempre de una pulsacion larga: se dispara con el dedo aun
                    // puesto, y sin el no se sabe que ha contado hasta que aparece el cuadro.
                    mapView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLongPress(point)
                }.also {
                    handler.postDelayed(it, Constants.MAP_LONG_PRESS_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(mapView.context).scaledTouchSlop
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                    cancel()
                    if (!panned && !pellizco && event.pointerCount == 1) {
                        panned = true
                        onPan()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pellizco = true
                cancel()
            }
            else -> cancel()   // levantar el dedo o cancelar
        }
        return false
    }

    private fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }
}
