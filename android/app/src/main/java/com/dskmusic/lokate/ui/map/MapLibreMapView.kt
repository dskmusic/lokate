package com.dskmusic.lokate.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled._3dRotation
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.data.remote.dto.ZoneDto
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.util.MapStyle
import com.dskmusic.lokate.util.formatDistance
import com.google.gson.JsonPrimitive
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.FillManager
import org.maplibre.android.plugins.annotation.FillOptions
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions
import org.maplibre.android.plugins.annotation.OnSymbolClickListener
import org.maplibre.android.plugins.annotation.OnSymbolDragListener
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.osmdroid.util.GeoPoint

/**
 * El mismo mapa de [OsmMapView] pintado por MapLibre: teselas vectoriales, letras rectas a
 * cualquier zoom y, a diferencia de osmdroid, una cámara que se puede inclinar (lo que hace
 * posible la vista de navegación). Se elige con los estilos [MapStyle.MAPLIBRE] y
 * [MapStyle.MAPLIBRE_DARK]; quién monta cuál lo decide [LokateMapView].
 *
 * Tiene la misma firma que [OsmMapView]: la regla de medir y las fotos del mapa funcionan
 * igual aquí, con anotaciones de MapLibre en vez de capas de osmdroid.
 *
 * Lo que NO trae y sigue siendo de osmdroid: el mapa guardado en el móvil (Mapsforge) y el
 * filtro de colores invertidos del modo oscuro — aquí el oscuro es un estilo de verdad.
 */
@Composable
fun MapLibreMapView(
    members: List<LocationDto>,
    zones: List<ZoneDto>,
    modifier: Modifier = Modifier,
    avatarBitmaps: Map<String, Bitmap> = emptyMap(),
    onMemberClick: (LocationDto) -> Unit = {},
    draggableUserIds: Set<String> = emptySet(),
    onMemberDragEnd: (LocationDto, GeoPoint) -> Unit = { _, _ -> },
    onMapTap: ((GeoPoint) -> Unit)? = null,
    onMapLongPress: ((GeoPoint) -> Unit)? = null,
    onUserPan: () -> Unit = {},
    routePoints: List<GeoPoint> = emptyList(),
    isochroneRings: List<IsoRing> = emptyList(),
    navPoint: GeoPoint? = null,
    navRotate: Boolean = false,
    searchPoint: GeoPoint? = null,
    searchLabel: String = "",
    onSearchPointClick: () -> Unit = {},
    measurePoints: List<GeoPoint> = emptyList(),
    onMeasurePointDelete: (Int) -> Unit = {},
    onMeasurePointMoved: (Int, GeoPoint) -> Unit = { _, _ -> },
    onMeasurePointInsert: (Int, GeoPoint) -> Unit = { _, _ -> },
    onMeasurePointGrab: (GeoPoint) -> Unit = {},
    onMeasureLiveTotal: (Double?) -> Unit = {},
    onMapControl: (MapControl) -> Unit = {},
    initialZoom: Double = Constants.MAP_DEFAULT_ZOOM.toDouble(),
    mapStyle: MapStyle = MapStyle.MAPLIBRE,
    showAccuracy: Boolean = false,
    accuracyIntensity: Int = Constants.MAP_ACCURACY_INTENSITY_DEFAULT,
    accuracyColor: Int = Constants.MAP_ACCURACY_COLOR_DEFAULT,
    showAccuracyLabels: Boolean = false,
    showZoneMargin: Boolean = false,
    zoneMarginColor: Int = Constants.MAP_ZONE_MARGIN_COLOR_DEFAULT,
    zoneFillIntensity: Int = Constants.MAP_ZONE_FILL_INTENSITY_DEFAULT,
    zoneColor: Int = Constants.MAP_ZONE_COLOR_DEFAULT,
    rememberCamera: Boolean = false,
    autoCenterUserId: String? = "",
    compassTopInsetPx: Int = 0,
    /** Hueco en píxeles que hay que dejarle abajo al botón de los 45° navegando: ahí arriba está
     * el cartel de la maniobra, así que el botón se va al pie del mapa, por encima de la barra
     * del viaje (lo que mide esa barra lo pasa MapScreen, que es quien la pinta). */
    tiltBottomInsetPx: Int = 0,
) {
    val context = LocalContext.current
    // Las devoluciones de llamada se leen desde listeners registrados UNA vez (al cargar el
    // estilo): sin esto se quedarían con las de la primera composición.
    val tap = rememberUpdatedState(onMapTap)
    val longPress = rememberUpdatedState(onMapLongPress)
    val pan = rememberUpdatedState(onUserPan)
    val memberClick = rememberUpdatedState(onMemberClick)
    val dragEnd = rememberUpdatedState(onMemberDragEnd)
    val searchClick = rememberUpdatedState(onSearchPointClick)
    val membersRef = rememberUpdatedState(members)
    val measureRef = rememberUpdatedState(measurePoints)
    val measure = rememberUpdatedState(
        MeasureCallbacks(
            onMeasurePointDelete, onMeasurePointMoved, onMeasurePointInsert,
            onMeasurePointGrab, onMeasureLiveTotal,
        ),
    )
    // Si el mapa está girado o inclinado: lo único que hace falta para enseñar u ocultar el
    // botón del norte. Es un booleano a propósito — la cámara se mueve en cada fotograma del
    // gesto y Compose solo recompone cuando este valor cambia de verdad.
    var straight by remember { mutableStateOf(true) }
    // Para el botón de los 45°: lo que de verdad tiene la cámara, no lo que se pidió.
    var tilted by remember { mutableStateOf(false) }

    // Si hay cámara recordada manda ella y el zoom del ajuste no se toca. Se calcula una sola
    // vez, antes de montar el mapa, igual que en [OsmMapView]: en cuanto el usuario mueve el
    // mapa, MapCameraMemory ya tiene valores y esto pasaría a ser true por accidente.
    val cameraRestored = remember {
        rememberCamera && MapCameraMemory.center != null && MapCameraMemory.zoom != null
    }
    val holder = remember { MapLibreHolder() }
    // Las cargas de MapLibre (el mapa y cada estilo) terminan en una devolución de llamada, no
    // en una recomposición: esta cuenta se lee en el cuerpo del composable para que Compose
    // vuelva a pasar por el bloque de dibujado en cuanto hay con qué dibujar.
    var generation by remember { mutableIntStateOf(0) }

    val mapView = remember {
        MapLibre.getInstance(context)
        // ponytail: textureMode porque este mapa también se usa dentro de diálogos (crear/editar
        // zona), y ahí una SurfaceView se pinta por debajo del diálogo. Cuesta algo de fluidez;
        // si alguna vez molesta, el camino es pasar un parámetro y dejarla en SurfaceView en el
        // mapa principal.
        val options = MapLibreMapOptions.createFromAttributes(context).textureMode(true)
        LokateMapLibreView(
            context, options,
            onLongPress = { point -> longPress.value?.invoke(point) },
            onPan = { pan.value() },
        )
    }

    // El ciclo de vida completo del MapView, que es quien tiene el contexto de GL: sin esto se
    // quedan hilos y memoria de GPU colgados al salir de la pantalla.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        // Al registrarse, el Lifecycle le pone al día al observador nuevo (ON_CREATE, ON_START,
        // ON_RESUME), así que el arranque del MapView entra por aquí igual que los cambios.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.destroyOnce()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.release()
            mapView.onStop()
            mapView.destroyOnce()
        }
        // ponytail: no se guarda onSaveInstanceState. Lo único que habría que restaurar es la
        // cámara, y de eso ya se encarga [MapCameraMemory] mientras la app siga en memoria; si
        // el sistema mata el proceso, el mapa se abre centrado en la ubicación actual, que es
        // justo lo que se quiere al volver.
    }

    // initialZoom viene de DataStore (asíncrono): en la primera composición todavía es el valor
    // por defecto y el guardado llega un instante después, así que hay que volver a aplicarlo
    // (mismo motivo que en [OsmMapView]).
    LaunchedEffect(initialZoom, generation) {
        if (!cameraRestored) {
            holder.map?.moveCamera(CameraUpdateFactory.zoomTo(initialZoom.toGlZoom()))
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                // La cuenta se lee AQUÍ, antes de cualquier salida de las de abajo. Compose solo
                // vuelve a entrar en este bloque si en la pasada anterior se leyó el estado que
                // luego cambia, y las pasadas que se van pronto (sin mapa todavía, estilo
                // cargando) no llegaban a la firma, que es donde se leía. Resultado: el
                // generation++ de la devolución de llamada del estilo no despertaba a nadie y el
                // mapa se quedaba con las capas montadas y vacías — se veía al volver al
                // vectorial desde osmdroid, que es cuando toda la cadena es asíncrona de golpe.
                val gen = generation
                val map = holder.map
                if (map == null) {
                    if (!holder.loadingMap) {
                        holder.loadingMap = true
                        view.getMapAsync { loaded ->
                            holder.map = loaded
                            view.boundMap = loaded
                            // Fuera el logotipo, la (i) y la brújula de MapLibre. Estorbaban
                            // abajo a la izquierda y, peor, se colaban en cada captura: en una
                            // foto de 4x4 salían dieciséis veces. El crédito de OpenStreetMap
                            // está en el menú de capas, y el botón del norte es el nuestro.
                            loaded.uiSettings.setAttributionEnabled(false)
                            loaded.uiSettings.setLogoEnabled(false)
                            loaded.uiSettings.setCompassEnabled(false)
                            // El norte vuelve con el gesto de dos dedos; inclinar a mano también,
                            // que es lo que este mapa sabe hacer y osmdroid no.
                            loaded.uiSettings.setRotateGesturesEnabled(true)
                            loaded.uiSettings.setTiltGesturesEnabled(true)
                            // El toque del mapa se registra con los gestores de anotaciones y no
                            // aquí, ver [MapLibreHolder.buildManagers].
                            loaded.addOnCameraMoveListener {
                                straight = loaded.isStraight()
                                tilted = loaded.cameraPosition.tilt > 0.5
                            }
                            if (rememberCamera) {
                                loaded.addOnCameraIdleListener {
                                    val position = loaded.cameraPosition
                                    MapCameraMemory.center = position.target?.toGeoPoint()
                                    MapCameraMemory.zoom = position.zoom.toOsmZoom()
                                }
                            }
                            holder.autoCentered = cameraRestored
                            loaded.moveCamera(
                                CameraUpdateFactory.newLatLngZoom(
                                    (if (cameraRestored) MapCameraMemory.center!! else GeoPoint(0.0, 0.0)).toLatLng(),
                                    (if (cameraRestored) MapCameraMemory.zoom!! else initialZoom).toGlZoom(),
                                ),
                            )
                            onMapControl(MapLibreControl(view, loaded, holder))
                            generation++
                        }
                    }
                    return@AndroidView
                }

                // Cambiar de estilo (claro <-> oscuro) tira capas y fuentes: los gestores de
                // anotaciones y las imágenes registradas hay que rehacerlos enteros.
                val styleUrl = mapStyle.styleUrl ?: MapStyle.MAPLIBRE.styleUrl!!
                if (holder.styleUrl != styleUrl) {
                    if (!holder.loadingStyle) {
                        holder.loadingStyle = true
                        holder.release()
                        map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                            holder.styleUrl = styleUrl
                            holder.loadingStyle = false
                            // Los gestores, aquí mismo y con el estilo que nos dan en la mano,
                            // sin esperar a la siguiente pasada del dibujado ni volver a
                            // preguntar por map.style. Si algo revienta, builtStyle se queda a
                            // null y el bloque de dibujado lo reintenta.
                            runCatching {
                                style.addBuildings3d()
                                holder.buildManagers(
                                    view, map, style, memberClick, dragEnd, searchClick,
                                    membersRef, tap, measure, measureRef,
                                )
                            }.onFailure { error ->
                                Log.w(TAG, "No se han podido montar las capas del estilo", error)
                            }
                            generation++
                        }
                    }
                    return@AndroidView
                }
                // Red de seguridad: los gestores de anotaciones (la gente, las zonas, la ruta,
                // la regla) cuelgan de capas del estilo, y tienen que estar montados sobre el
                // estilo que esté puesto AHORA. Normalmente ya lo están — se montan al cargarlo,
                // unas líneas más arriba — y esto es comparar dos referencias; pero si aquello
                // falló, o si MapLibre recarga el estilo por su cuenta, aquí se vuelve a montar
                // en vez de dejar el mapa sin nada para siempre.
                //
                // isFullyLoaded: el plugin de anotaciones lanza una excepción si el estilo aún no
                // está entero, y entonces no habría nada que dibujar.
                val mapStyleNow = map.style?.takeIf { it.isFullyLoaded } ?: return@AndroidView
                if (holder.builtStyle !== mapStyleNow) {
                    // Soltar las capas de un estilo que ya no está puede protestar; da lo mismo,
                    // se va entero. release() deja también la firma a null, así que lo de abajo
                    // vuelve a dibujarlo todo sobre los gestores nuevos.
                    runCatching { holder.release() }
                    mapStyleNow.addBuildings3d()
                    holder.buildManagers(
                        view, map, mapStyleNow, memberClick, dragEnd, searchClick, membersRef,
                        tap, measure, measureRef,
                    )
                }
                val symbols = holder.symbols ?: return@AndroidView

                val signature = buildString {
                    // La generación entra en la firma: al recargar el estilo los gestores son otros y
                    // hay que volver a dibujarlo todo desde cero.
                    append(gen).append('#').append(onMapTap != null).append('#')
                    append(showAccuracy).append(accuracyIntensity).append(accuracyColor)
                    append(showAccuracyLabels).append(showZoneMargin).append(zoneMarginColor)
                    append(zoneFillIntensity).append(zoneColor).append('#')
                    members.forEach {
                        append(it.user_id).append(it.lat).append(',').append(it.lng)
                        append('@').append(it.display_name).append('@').append(it.accuracy)
                        append('@').append(it.battery_level).append(it.is_charging).append(';')
                    }
                    append('#')
                    zones.forEach {
                        append(it.id).append(it.lat).append(',').append(it.lng).append(it.radius_m)
                        append('@').append(it.name).append(';')
                    }
                    append('#')
                    avatarBitmaps.entries.sortedBy { it.key }.forEach {
                        append(it.key).append(System.identityHashCode(it.value)).append(';')
                    }
                    append('#')
                    draggableUserIds.sorted().forEach { append(it).append(';') }
                    append('#')
                    append(routePoints.size).append(routePoints.firstOrNull()).append(routePoints.lastOrNull())
                    isochroneRings.forEach { append(it.minutes).append(':').append(it.points.size).append(';') }
                    append('#').append(searchPoint).append(searchLabel)
                    append('#')
                    measurePoints.forEach { append(it.latitude).append(',').append(it.longitude).append(';') }
                    // A diferencia de osmdroid no entra el zoom: aquí los marcadores se separan con
                    // los metros por píxel del momento, pero rehacer las capas en cada pellizco
                    // costaría más de lo que arregla. Se recolocan en la siguiente actualización.
                }
                if (holder.drawn != signature) {
                    holder.drawn = signature
                    holder.fills?.deleteAll()
                    holder.halos?.deleteAll()
                    holder.lines?.deleteAll()
                    holder.dashes?.deleteAll()
                    // El símbolo de la navegación cae con los demás y lo vuelve a crear el bloque de
                    // navegación de más abajo, que corre justo después en esta misma pasada.
                    symbols.deleteAll()
                    holder.navSymbol = null

                    val style = map.style
                    // Debajo de todo, igual que en osmdroid: lo primero que se crea queda abajo.
                    isochroneRings.forEach { ring ->
                        val color = isochroneColor(ring.minutes)
                        holder.fills?.create(
                            FillOptions()
                                .withLatLngs(listOf(ring.points.map { it.toLatLng() }))
                                .withFillColor(color.rgba())
                                .withFillOpacity(0.23f),
                        )
                        holder.lines?.create(
                            LineOptions().withLatLngs(ring.points.closedGeo())
                                .withLineColor(color.rgba()).withLineWidth(2f),
                        )
                    }
                    zones.forEach { zone ->
                        if (showZoneMargin) {
                            // Anillo de verdad (el agujero es la zona), por lo mismo que en osmdroid:
                            // dos discos superpuestos sumarían transparencias.
                            holder.fills?.create(
                                FillOptions()
                                    .withLatLngs(
                                        listOf(
                                            circleRing(zone.lat, zone.lng, zone.radius_m + Constants.ZONE_EXIT_MARGIN_METERS).toLatLngs(),
                                            circleRing(zone.lat, zone.lng, zone.radius_m).toLatLngs(),
                                        ),
                                    )
                                    .withFillColor(zoneMarginColor.rgba())
                                    .withFillOpacity(zoneFillColor(zoneMarginColor, zoneFillIntensity, scale = 0.6f).alphaFraction()),
                            )
                            holder.dashes?.create(
                                LineOptions()
                                    .withLatLngs(circleRing(zone.lat, zone.lng, zone.radius_m + Constants.ZONE_EXIT_MARGIN_METERS).closed())
                                    .withLineColor(zoneMarginColor.rgba())
                                    .withLineWidth(2.5f),
                            )
                        }
                        holder.fills?.create(
                            FillOptions()
                                .withLatLngs(listOf(circleRing(zone.lat, zone.lng, zone.radius_m).toLatLngs()))
                                .withFillColor(zoneColor.rgba())
                                .withFillOpacity(zoneFillColor(zoneColor, zoneFillIntensity).alphaFraction()),
                        )
                        // Sombra negra por debajo del borde y borde encima: el mismo truco de
                        // contraste que en osmdroid, por si el estilo tiene un suelo claro.
                        val ring = circleRing(zone.lat, zone.lng, zone.radius_m).closed()
                        holder.halos?.create(
                            LineOptions().withLatLngs(ring).withLineColor(MAP_HALO_BLACK.rgba())
                                .withLineOpacity(Color.alpha(MAP_HALO_BLACK) / 255f).withLineWidth(6f),
                        )
                        holder.lines?.create(
                            LineOptions().withLatLngs(ring).withLineColor(zoneColor.rgba()).withLineWidth(3f),
                        )
                        if (zone.name.isNotBlank()) {
                            symbols.create(
                                textOptions(
                                    destination(zone.lat, zone.lng, zone.radius_m, 0.0), zone.name,
                                    ZONE_LABEL_SP, labelTextColor(zoneColor), anchor = "bottom",
                                ),
                            )
                        }
                    }

                    // La ruta por debajo de la gente, igual que en osmdroid.
                    if (routePoints.size >= 2) {
                        holder.lines?.create(
                            LineOptions()
                                .withLatLngs(routePoints.map { it.toLatLng() })
                                .withLineColor(DIRECTIONS_COLOR.rgba())
                                .withLineWidth(6f),
                        )
                    }

                    // Cuántos metros mide un píxel aquí y ahora, para separar a quien se tape (lo
                    // mismo que hace osmdroid con su proyección).
                    val metersPerPixel = map.projection
                        .getMetersPerPixelAtLatitude(map.cameraPosition.target?.latitude ?: 0.0)
                    val placed = spreadOverlapping(members, metersPerPixel)

                    if (showAccuracy) {
                        val strength = accuracyIntensity.coerceIn(10, 100) / 100f
                        placed.forEach { (member, position) ->
                            val accuracy = member.accuracy?.toDouble() ?: 0.0
                            if (accuracy <= 0.0) return@forEach
                            holder.fills?.create(
                                FillOptions()
                                    .withLatLngs(listOf(circleRing(position.latitude, position.longitude, accuracy, points = 32).toLatLngs()))
                                    .withFillColor(accuracyColor.rgba())
                                    .withFillOpacity(90 * strength / 255f),
                            )
                            if (showAccuracyLabels && member.display_name.isNotBlank()) {
                                symbols.create(
                                    textOptions(
                                        destination(position.latitude, position.longitude, accuracy, 180.0),
                                        member.display_name, ACCURACY_LABEL_SP,
                                        labelTextColor(accuracyColor), anchor = "top",
                                    ),
                                )
                            }
                        }
                    }

                    searchPoint?.let { point ->
                        val id = "lokate-search-pin"
                        // La chincheta de osmdroid, la misma que marca el resultado en el otro
                        // mapa: un punto de 40 px no se veía (el avatar de quien busca su propia
                        // casa le cae encima) y además la marca tiene que ser la misma en los dos
                        // mapas. Clavada por la punta, que el ancla de MapLibre es el centro.
                        //
                        // Se le pregunta al estilo y no a holder.icons: el estilo es quien guarda
                        // las imágenes de verdad, y con un nombre fijo como este (los avatares
                        // llevan uno distinto cada vez) una lista aparte que crea tenerla deja la
                        // chincheta invisible para siempre.
                        if (style != null && style.getImage(id) == null) {
                            ContextCompat.getDrawable(view.context, org.osmdroid.library.R.drawable.marker_default)
                                ?.let { pin -> style.addImage(id, pin) }
                        }
                        symbols.create(
                            SymbolOptions().withLatLng(point.toLatLng()).withIconImage(id)
                                .withIconAnchor("bottom")
                                .withData(JsonPrimitive(SEARCH_SYMBOL_DATA)),
                        )
                        if (searchLabel.isNotBlank()) {
                            symbols.create(
                                textOptions(
                                    LatLon(point.latitude, point.longitude), searchLabel,
                                    ZONE_LABEL_SP, labelTextColor(SEARCH_PIN_COLOR), anchor = "top",
                                ),
                            )
                        }
                    }

                    placed.forEach { (member, position) ->
                        val avatar = avatarBitmaps[member.user_id]
                        val id = "lokate-m-${member.user_id}-${member.battery_level}-${member.is_charging}" +
                            "-${System.identityHashCode(avatar)}"
                        if (style != null && holder.icons.add(id)) {
                            val bitmap = if (avatar != null) {
                                MarkerIconFactory.circularAvatarBitmap(
                                    avatar, MARKER_SIZE_PX, member.battery_level, member.is_charging == true,
                                )
                            } else {
                                MarkerIconFactory.initialsBitmap(
                                    member.display_name, member.user_id, MARKER_SIZE_PX,
                                    member.battery_level, member.is_charging == true,
                                )
                            }
                            style.addImage(id, bitmap)
                        }
                        symbols.create(
                            SymbolOptions()
                                .withLatLng(LatLng(position.latitude, position.longitude))
                                .withIconImage(id)
                                .withDraggable(member.user_id in draggableUserIds)
                                .withData(JsonPrimitive(member.user_id)),
                        )
                    }

                    // La regla al final, como en osmdroid: sus puntos son los que se tocan
                    // (tocar = borrar) y no el avatar o el círculo de zona que caiga debajo.
                    holder.drawMeasure(style, view.context.resources.displayMetrics.density, measurePoints)
                }

                // Fuera de la firma a propósito: navegando llega una posición por segundo y rehacer
                // todas las capas sesenta veces por minuto sería tirar el mapa y volver a montarlo.
                if (navPoint == null) {
                    holder.navSymbol?.let { symbols.delete(it) }
                    holder.navSymbol = null
                    holder.bearingAnchor = null
                    holder.navBearing = null
                    // Fin del viaje: el norte arriba y el mapa plano otra vez, pero SOLO al
                    // salir de la navegación. Enderezándolo en cada pasada, inclinar el mapa a
                    // mano con dos dedos no duraba ni hasta la siguiente tanda de posiciones.
                    if (holder.navCamera) {
                        holder.navCamera = false
                        holder.tilt45 = false
                        map.straighten()
                    }
                } else {
                    val id = "lokate-nav-dot"
                    val style = map.style
                    if (style != null && holder.icons.add(id)) {
                        val density = view.context.resources.displayMetrics.density
                        style.addImage(
                            id,
                            MarkerIconFactory.dotBitmap(DIRECTIONS_COLOR, (NAV_DOT_DP * density).toInt()),
                        )
                    }
                    val existing = holder.navSymbol
                    if (existing == null) {
                        holder.navSymbol = symbols.create(
                            SymbolOptions().withLatLng(navPoint.toLatLng()).withIconImage(id),
                        )
                    } else {
                        existing.setLatLng(navPoint.toLatLng())
                        symbols.update(existing)
                    }
                    // Navegando, la cámara va inclinada como la de un navegador de coche: los
                    // 45° se ponen solos al empezar el viaje, haya giro con el rumbo o no, y el
                    // botón de la izquierda sigue mandando (se pueden quitar a medio camino).
                    // El centrado NO se toca aquí: lo lleva MapScreen con el autocentrado, que se
                    // suelta al arrastrar el mapa.
                    if (!holder.navCamera) holder.tilt45 = true
                    holder.navCamera = true
                    val turned = if (navRotate) {
                        // El rumbo sale de por dónde se ha venido (no del GPS), así que vale
                        // igual para la ruta simulada. Con un solo punto no hay rumbo que sacar.
                        val previous = holder.bearingAnchor
                        if (previous == null || previous.distanceToAsDouble(navPoint) >= NAV_BEARING_MIN_M) {
                            holder.bearingAnchor = navPoint
                            previous?.bearingTo(navPoint)
                        } else {
                            null
                        }
                    } else {
                        // Apagado el girar con el rumbo: el norte arriba, pero la inclinación se
                        // queda, que es lo que se ha pedido al navegar.
                        holder.bearingAnchor = null
                        0.0.takeIf { !map.isFacingNorth() }
                    }
                    if (turned != null) holder.navBearing = turned
                    // Una sola llamada a la cámara, y con el rumbo guardado: dos easeCamera
                    // seguidos se pisan (el último gana) y el centrado de MapScreen llega en el
                    // mismo instante, una vez por segundo. Repartido en tres llamadas, el giro
                    // hacia la marcha se quedaba siempre a medias.
                    if (turned != null || abs(map.cameraPosition.tilt - holder.wantedTilt) > 0.5) {
                        map.easeTo(null, holder.navBearing, holder.wantedTilt)
                    }
                }

                if (!holder.autoCentered && autoCenterUserId != null) {
                    val chosen = members.firstOrNull { it.user_id == autoCenterUserId } ?: members.firstOrNull()
                    chosen?.let {
                        map.moveCamera(CameraUpdateFactory.newLatLng(LatLng(it.lat, it.lng)))
                        holder.autoCentered = true
                    }
                }
            },
        )

        // ponytail: botón propio y no la brújula de MapLibre: la suya solo endereza el rumbo
        // (setFocalBearing) y no hay manera de interceptar su toque, así que con el mapa
        // inclinado a mano se quedaría inclinado. Este devuelve las dos cosas: norte y vista
        // cenital. Navegando no se enseña — ahí la cámara la lleva la ruta.
        if (!straight && navPoint == null) {
            FilledTonalIconButton(
                onClick = {
                    holder.tilt45 = false
                    holder.map?.straighten()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // Por debajo del buscador de sitios, que es lo que hay encima del mapa.
                    .padding(top = with(LocalDensity.current) { compassTopInsetPx.toDp() } + 8.dp, end = 8.dp)
                    .size(40.dp),
            ) {
                Icon(Icons.Filled.Explore, contentDescription = stringResource(R.string.map_reset_north))
            }
        }

        // Los 45°, a mano y siempre a la vista: inclinarse es lo único que este mapa sabe hacer y
        // osmdroid no, así que el botón vive aquí y no en la barra de MapScreen, que es común a
        // los dos. Navegando también vale: la navegación los pone al arrancar y desde aquí se
        // quitan (y se vuelven a poner) sin salir del viaje.
        // Navegando se baja al pie del mapa: arriba, el cartel de la maniobra crece con el
        // nombre de la calle y se le ponía encima.
        val hueco = with(LocalDensity.current) {
            (if (navPoint != null) tiltBottomInsetPx else compassTopInsetPx).toDp()
        } + 8.dp
        FilledTonalIconButton(
            onClick = {
                // Contra lo que tiene la cámara y no contra lo que se pidió la última vez: con el
                // mapa inclinado a mano (dos dedos), el botón lo endereza en vez de volver a
                // pedir 45°.
                holder.tilt45 = !tilted
                holder.map?.easeTo(null, null, holder.wantedTilt)
            },
            modifier = Modifier
                .align(if (navPoint != null) Alignment.BottomStart else Alignment.TopStart)
                // Sin navegar, a la misma altura que el del norte (por debajo del buscador de
                // sitios); navegando, por encima de la barra del viaje.
                .padding(
                    start = 8.dp,
                    top = if (navPoint != null) 0.dp else hueco,
                    bottom = if (navPoint != null) hueco else 0.dp,
                )
                .size(40.dp),
        ) {
            Icon(
                Icons.Filled._3dRotation,
                contentDescription = stringResource(R.string.map_tilt_45),
                tint = if (tilted) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
    }
}

/** Lo inclinado que va el mapa navegando. 45° es lo que usan los navegadores: se ve la calle
 * por la que se va y un par de manzanas por delante. */
private const val NAV_TILT = 45.0

/** Lo que tardan los movimientos de cámara de la navegación. Corto, pero animado: saltar de
 * golpe en cada rumbo nuevo marea. */
private const val NAV_CAMERA_MS = 600

/** La chincheta del buscador. Rojo porque es lo único del mapa que no es ni gente ni zona. */
private const val SEARCH_PIN_COLOR = 0xFFD32F2F.toInt()

/** Marca en los datos del símbolo del buscador, para distinguir su toque del de una persona. */
private const val SEARCH_SYMBOL_DATA = "lokate-search"

/**
 * Los gestores de anotaciones, las imágenes ya registradas y lo último dibujado. Fuera de
 * Compose: nada de esto debe disparar una recomposición al cambiar.
 */
private class MapLibreHolder {
    var map: MapLibreMap? = null
    var loadingMap = false
    var loadingStyle = false
    var styleUrl: String? = null

    /** El orden de creación es el orden de pintado (cada gestor es una capa): rellenos abajo,
     * luego las sombras, los bordes, las líneas de puntos y los símbolos arriba del todo, que
     * son los que se tocan. */
    var fills: FillManager? = null
    var halos: LineManager? = null
    var lines: LineManager? = null
    var dashes: LineManager? = null
    var symbols: SymbolManager? = null

    /** Las tres capas de la regla, por encima de la gente: la línea, los puntos que se tocan y
     * las medidas de cada tramo. */
    var measureLines: LineManager? = null
    var measureSymbols: SymbolManager? = null
    var measureLabels: SymbolManager? = null

    val icons = mutableSetOf<String>()
    var drawn: String? = null
    var navSymbol: Symbol? = null
    var bearingAnchor: GeoPoint? = null

    /** Si el mapa va inclinado porque se ha pedido: el botón de los 45° y, al empezar a navegar,
     * la propia navegación. Lo miran los dos sitios que mueven la cámara. */
    var tilt45 = false

    /** La inclinación que toca ahora mismo. */
    val wantedTilt: Double get() = if (tilt45) NAV_TILT else 0.0

    /** El rumbo que le toca a la cámara navegando. Lo leen el bloque de navegación y el centrado
     * de [MapControl.moveTo], que pueden coincidir en la misma pasada: si cada uno pidiera lo
     * suyo, el que llegase último desharía al otro. */
    var navBearing: Double? = null
    var autoCentered = false

    /** La cámara la lleva la navegación (inclinada y girada con el rumbo). Mientras sea true,
     * salir de la navegación es lo que endereza el mapa; con false no se le toca la cámara, que
     * puede estar inclinada a mano. */
    var navCamera = false

    /** El toque del mapa. Se guarda para poder quitarlo al soltar el estilo: se registra con los
     * gestores y tiene que irse con ellos. */
    var mapClick: MapLibreMap.OnMapClickListener? = null

    /** Copia de trabajo de la regla: mientras un punto va colgado del dedo, la línea y las
     * medidas se rehacen contra esta lista sin pasar por Compose, igual que en osmdroid. */
    var measureLive = mutableListOf<GeoPoint>()

    /** Qué entra en la foto de la regla (ver [MapControl.hideForMeasurePhoto]). */
    var measureMidDots = true
    var measureSegmentLabels = true

    /** El estilo sobre el que están montados los gestores, para no montarlos dos veces sobre el
     * mismo ni dejarlos colgando de uno que ya no está. */
    var builtStyle: Style? = null

    fun buildManagers(
        view: MapView,
        map: MapLibreMap,
        style: Style,
        memberClick: androidx.compose.runtime.State<(LocationDto) -> Unit>,
        dragEnd: androidx.compose.runtime.State<(LocationDto, GeoPoint) -> Unit>,
        searchClick: androidx.compose.runtime.State<() -> Unit>,
        membersRef: androidx.compose.runtime.State<List<LocationDto>>,
        tap: androidx.compose.runtime.State<((GeoPoint) -> Unit)?>,
        measure: androidx.compose.runtime.State<MeasureCallbacks>,
        measurePoints: androidx.compose.runtime.State<List<GeoPoint>>,
    ) {
        fills = FillManager(view, map, style)
        halos = LineManager(view, map, style)
        lines = LineManager(view, map, style)
        dashes = LineManager(view, map, style).apply {
            // Es propiedad de la capa entera y no de cada línea: de ahí que el margen de las
            // zonas tenga gestor propio.
            setLineDasharray(arrayOf(1.5f, 1.25f))
        }
        symbols = SymbolManager(view, map, style).apply {
            // Un avatar se enseña siempre, aunque le caiga encima un rótulo del mapa.
            setIconAllowOverlap(true)
            setTextAllowOverlap(true)
        }
        measureLines = LineManager(view, map, style)
        measureSymbols = SymbolManager(view, map, style).apply { setIconAllowOverlap(true) }
        // ponytail: las medidas de tramo sí se dejan chocar, y MapLibre esconde la que no cabe.
        // En osmdroid se reparten a mano buscando hueco; aquí eso habría que hacerlo en cada
        // fotograma y por ahora no vale la pena: el total está en la tarjeta y en el pie de la
        // foto, y acercando el mapa aparecen todas.
        measureLabels = SymbolManager(view, map, style)
        icons.clear()
        drawn = null
        navSymbol = null

        symbols?.addClickListener(
            object : OnSymbolClickListener {
                override fun onAnnotationClick(symbol: Symbol): Boolean {
                    // Igual que en osmdroid: si alguien está esperando los toques del mapa
                    // (elegir dónde va una zona), un marcador no se los queda.
                    if (tap.value != null) return false
                    val data = symbol.data?.asString ?: return false
                    if (data == SEARCH_SYMBOL_DATA) {
                        searchClick.value()
                        return true
                    }
                    val member = membersRef.value.find { it.user_id == data } ?: return false
                    memberClick.value(member)
                    return true
                }
            },
        )
        symbols?.addDragListener(
            object : OnSymbolDragListener {
                override fun onAnnotationDragStarted(annotation: Symbol) = Unit
                override fun onAnnotationDrag(annotation: Symbol) = Unit

                /** Solo al soltar, como en osmdroid: arrastrar dispara una comprobación de
                 * zonas en el servidor, no una por cada píxel. */
                override fun onAnnotationDragFinished(annotation: Symbol) {
                    val data = annotation.data?.asString ?: return
                    val member = membersRef.value.find { it.user_id == data } ?: return
                    dragEnd.value(member, annotation.latLng.toGeoPoint())
                }
            },
        )

        measureSymbols?.addClickListener(
            object : OnSymbolClickListener {
                /** Tocar un punto de la regla lo borra, como en osmdroid. */
                override fun onAnnotationClick(symbol: Symbol): Boolean {
                    val index = symbol.measureIndex() ?: return false
                    measure.value.delete(index)
                    return true
                }
            },
        )
        measureSymbols?.addDragListener(
            object : OnSymbolDragListener {
                override fun onAnnotationDragStarted(annotation: Symbol) {
                    // La vibración corta del sistema (respeta tenerla apagada) más el tic de
                    // radar de la pantalla: sin eso no se distingue "ya lo tengo cogido" de "no
                    // me hace caso".
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    measure.value.grab(annotation.latLng.toGeoPoint())
                }

                /** El punto lo mueve MapLibre solo; lo que hay que arrastrar con él es la línea
                 * y las medidas, o el punto se iría del sitio dejando la ruta atrás. */
                override fun onAnnotationDrag(annotation: Symbol) {
                    val index = annotation.measureIndex() ?: return
                    if (index !in measureLive.indices) return
                    measureLive[index] = annotation.latLng.toGeoPoint()
                    drawMeasureShape(measureLive)
                    if (measureLive.size > 1) measure.value.liveTotal(measureTotalMeters(measureLive))
                }

                override fun onAnnotationDragFinished(annotation: Symbol) {
                    measure.value.liveTotal(null)
                    val index = annotation.measureIndex() ?: return
                    measure.value.moved(index, annotation.latLng.toGeoPoint())
                }
            },
        )

        // El toque del mapa, DESPUÉS de los gestores: MapLibre reparte los toques por orden de
        // registro y el primero que diga que es suyo se lo queda. Registrado antes (como estaba),
        // con la regla puesta no se podría ni borrar un punto: todo caería en "añadir punto".
        val listener = MapLibreMap.OnMapClickListener { latLng ->
            val callback = tap.value
            if (callback == null) {
                false
            } else {
                val point = latLng.toGeoPoint()
                val points = measurePoints.value
                val near = if (points.size > 1) nearestSegmentPoint(points, point) else null
                // Tolerancia en metros, porque la comparación es en coordenadas: los mismos
                // píxeles de margen que en osmdroid, traducidos al zoom de ahora.
                val tolerance = TAP_TOLERANCE_DP * view.resources.displayMetrics.density *
                    map.projection.getMetersPerPixelAtLatitude(latLng.latitude)
                if (near != null && near.second.distanceToAsDouble(point) <= tolerance) {
                    // Toque sobre la línea: un punto nuevo EN MEDIO, en esa posición de la lista.
                    measure.value.insert(near.first, near.second)
                } else {
                    callback(point)
                }
                true
            }
        }
        mapClick = listener
        map.addOnMapClickListener(listener)
        // Lo último: si el montaje se va a medias, la siguiente pasada lo repite entero.
        builtStyle = style
    }

    /**
     * La regla entera: la línea, los puntos que se tocan y las medidas de cada tramo. La llama
     * el bloque de dibujado, que ya ha borrado las demás capas.
     */
    fun drawMeasure(style: Style?, density: Float, points: List<GeoPoint>) {
        measureLive = points.toMutableList()
        measureSymbols?.deleteAll()
        drawMeasureShape(points)
        if (points.isEmpty()) return
        if (style != null && icons.add(MEASURE_DOT_ICON)) {
            style.addImage(
                MEASURE_DOT_ICON,
                MarkerIconFactory.dotBitmap(MEASURE_COLOR, (MEASURE_DOT_DP * density).toInt()),
            )
        }
        points.forEachIndexed { index, point ->
            // Las puntas se quedan siempre: sin ellas no se ve dónde empieza ni dónde acaba.
            if (!measureMidDots && index != 0 && index != points.lastIndex) return@forEachIndexed
            measureSymbols?.create(
                SymbolOptions()
                    .withLatLng(point.toLatLng())
                    .withIconImage(MEASURE_DOT_ICON)
                    .withDraggable(true)
                    .withData(JsonPrimitive(MEASURE_SYMBOL_DATA + index)),
            )
        }
    }

    /** La línea y las medidas de cada tramo. Aparte del resto porque es lo que se rehace solo
     * mientras un punto va colgado del dedo. */
    fun drawMeasureShape(points: List<GeoPoint>) {
        measureLines?.deleteAll()
        measureLabels?.deleteAll()
        if (points.size < 2) return
        measureLines?.create(
            LineOptions()
                .withLatLngs(points.map { it.toLatLng() })
                .withLineColor(MEASURE_COLOR.rgba())
                .withLineWidth(4f),
        )
        if (!measureSegmentLabels) return
        for (i in 1 until points.size) {
            val from = points[i - 1]
            val to = points[i]
            measureLabels?.create(
                textOptions(
                    LatLon((from.latitude + to.latitude) / 2, (from.longitude + to.longitude) / 2),
                    formatDistance(from.distanceToAsDouble(to).toFloat()),
                    MEASURE_LABEL_SP, MEASURE_COLOR, anchor = "bottom",
                ),
            )
        }
    }

    /** Al cambiar de estilo y al salir de la pantalla: los gestores tienen sus propias fuentes
     * y capas dentro del estilo, y hay que soltarlas. */
    fun release() {
        mapClick?.let { listener -> map?.removeOnMapClickListener(listener) }
        mapClick = null
        listOfNotNull(fills, halos, lines, dashes, symbols, measureLines, measureSymbols, measureLabels)
            .forEach { it.onDestroy() }
        fills = null
        halos = null
        lines = null
        dashes = null
        symbols = null
        measureLines = null
        measureSymbols = null
        measureLabels = null
        measureLive = mutableListOf()
        builtStyle = null
        icons.clear()
        drawn = null
        navSymbol = null
    }
}

/** [MapControl] sobre MapLibre: lo mismo que pide MapScreen, con su cámara. */
private class MapLibreControl(
    private val view: MapView,
    private val map: MapLibreMap,
    private val holder: MapLibreHolder,
) : MapControl {
    override fun moveTo(point: GeoPoint) {
        // Navegando, el centro viaja con el rumbo y la inclinación en la misma llamada: llegan
        // en el mismo instante que el giro del bloque de navegación (una posición por segundo) y
        // la segunda llamada cancelaría la primera. Así las dos piden lo mismo y da igual quién
        // gane.
        if (holder.navCamera) {
            map.easeTo(point.toLatLng(), holder.navBearing, holder.wantedTilt)
            return
        }
        // Fuera de la navegación, newLatLng y no una posición entera: así centrar no deshace una
        // inclinación puesta a mano.
        map.animateCamera(CameraUpdateFactory.newLatLng(point.toLatLng()), NAV_CAMERA_MS)
    }

    override fun jumpTo(point: GeoPoint, zoom: Double) {
        // Primero cortar lo que estuviera animando: al salir de la navegación el mapa se está
        // enderezando (medio segundo de easeCamera) y esa animación lleva dentro el centro que
        // tenía al empezar, así que se llevaba el mapa de vuelta al final del viaje y deshacía
        // este salto.
        map.cancelTransitions()
        // De paso endereza el mapa. Los saltos de golpe son tres: empezar a navegar (que pone su
        // propia inclinación justo después), un resultado del buscador y el paseo por cuadrantes
        // de la foto grande; en los tres el norte arriba y plano es lo que toca.
        map.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(point.toLatLng())
                    .zoom(zoom.toGlZoom())
                    .bearing(0.0)
                    .tilt(0.0)
                    .build(),
            ),
        )
    }

    override fun setZoom(zoom: Double) {
        map.moveCamera(CameraUpdateFactory.zoomTo(zoom.toGlZoom()))
    }

    override fun frameZone(lat: Double, lng: Double, radiusM: Double) {
        // El círculo entero con el 40% de margen, lo mismo que [MapView.frameZone].
        val ring = circleRing(lat, lng, radiusM * 1.4, points = 8).toLatLngs()
        map.easeCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(ring).build(), 0), NAV_CAMERA_MS)
    }

    override fun frameRoute(points: List<GeoPoint>, topInset: Int, bottomInset: Int) {
        if (points.size < 2) return
        val bounds = LatLngBounds.Builder().includes(points.map { it.toLatLng() }).build()
        val margin = (16 * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
        map.easeCamera(
            CameraUpdateFactory.newLatLngBounds(
                bounds, margin, topInset + margin, margin, bottomInset + margin,
            ),
            NAV_CAMERA_MS,
        )
    }

    override fun framePoints(points: List<GeoPoint>, paddingPx: Int) {
        if (points.size < 2) return
        // moveCamera y no easeCamera: detrás de un encuadre así viene siempre una foto, y la
        // espera de las teselas empieza a contar ya.
        val bounds = runCatching {
            LatLngBounds.Builder().includes(points.map { it.toLatLng() }).build()
        }.getOrNull() ?: return
        // Rumbo y inclinación a cero: si no, encuadrar "la ruta entera" sobre un mapa
        // inclinado deja la foto torcida y sin la mitad del recorrido.
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0.0, 0.0, paddingPx))
    }

    override fun nearBox(): String? {
        val bounds = map.projection.visibleRegion.latLngBounds
        return "${bounds.longitudeWest},${bounds.latitudeNorth},${bounds.longitudeEast},${bounds.latitudeSouth}"
    }

    override fun toScreen(point: GeoPoint): Offset? {
        val pixel = map.projection.toScreenLocation(point.toLatLng())
        return Offset(pixel.x, pixel.y)
    }

    override val widthPx: Int get() = view.width
    override val heightPx: Int get() = view.height
    override val zoom: Double get() = map.cameraPosition.zoom.toOsmZoom()
    override val maxZoom: Double get() = map.maxZoomLevel.toOsmZoom()
    override val center: GeoPoint
        get() = map.cameraPosition.target?.toGeoPoint() ?: GeoPoint(0.0, 0.0)

    override fun geoAt(x: Int, y: Int): GeoPoint? {
        if (view.width <= 0 || view.height <= 0) return null
        return map.projection.fromScreenLocation(PointF(x.toFloat(), y.toFloat())).toGeoPoint()
    }

    override suspend fun frame(tileWaitMs: Long): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        delay(tileWaitMs)
        // La captura la hace MapLibre leyendo su propia pintura: sale lo mismo que se ve (zonas,
        // ruta, regla, edificios en 3D) y sin los botones de Compose que van por encima. No vale
        // dibujar la vista en un lienzo como en osmdroid: lo que se ve lo pinta la GPU.
        return withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Bitmap> { continuation ->
                map.snapshot { bitmap -> if (continuation.isActive) continuation.resume(bitmap) }
            }
        }
    }

    override fun hideForMeasurePhoto(midPoints: Boolean, segmentDistances: Boolean): () -> Unit {
        // Las anotaciones se rehacen en vez de apagarse (MapLibre no tiene un interruptor por
        // anotación), pero sin pasar por Compose: una recomposición no llegaría a tiempo.
        val points = holder.measureLive.toList()
        val density = view.resources.displayMetrics.density
        holder.measureMidDots = midPoints
        holder.measureSegmentLabels = segmentDistances
        holder.drawMeasure(map.style, density, points)
        return {
            holder.measureMidDots = true
            holder.measureSegmentLabels = true
            holder.drawMeasure(map.style, density, points)
        }
    }
}

/** Las devoluciones de llamada de la regla, en un paquete: los listeners de MapLibre se
 * registran una sola vez (al cargar el estilo) y tienen que leer siempre las últimas. */
private class MeasureCallbacks(
    val delete: (Int) -> Unit,
    val moved: (Int, GeoPoint) -> Unit,
    val insert: (Int, GeoPoint) -> Unit,
    val grab: (GeoPoint) -> Unit,
    val liveTotal: (Double?) -> Unit,
)

/** El punto de la regla al que corresponde este símbolo, o null si el símbolo no es de la
 * regla (un avatar, un rótulo, la chincheta del buscador). */
private fun Symbol.measureIndex(): Int? = data?.asString
    ?.takeIf { it.startsWith(MEASURE_SYMBOL_DATA) }
    ?.removePrefix(MEASURE_SYMBOL_DATA)
    ?.toIntOrNull()

/** Para el aviso de cuando no se pueden montar las capas del estilo: es lo único que se
 * registra desde aquí, y sin esto el mapa se queda sin gente ni zonas sin decir por qué. */
private const val TAG = "LokateMapLibre"

/** El icono del punto de la regla, registrado una vez en el estilo. */
private const val MEASURE_DOT_ICON = "lokate-measure-dot"

/** Marca en los datos de un símbolo de la regla, con su sitio en la lista detrás. */
private const val MEASURE_SYMBOL_DATA = "lokate-measure-"

/** La letra de las medidas de tramo: un poco menor que la de las zonas, que son muchas. */
private const val MEASURE_LABEL_SP = 11f

/** Lo que se le espera a MapLibre por una captura antes de darla por perdida. Es una lectura de
 * lo ya pintado y tarda milisegundos; el tope está para no dejar la foto colgada si el contexto
 * de la GPU se ha ido por medio (la pantalla apagada, la app al fondo). */
private const val SNAPSHOT_TIMEOUT_MS = 5_000L

/** Si el mapa mira al norte. El rumbo de MapLibre va de 0 a 360, así que 359° está medio grado
 * torcido y no 359. */
private fun MapLibreMap.isFacingNorth(): Boolean =
    abs(((cameraPosition.bearing + 180.0) % 360.0) - 180.0) <= 0.5

/** Si el mapa está plano y mirando al norte. */
private fun MapLibreMap.isStraight(): Boolean = cameraPosition.tilt <= 0.5 && isFacingNorth()

/**
 * La cámara de una pieza: centro, rumbo e inclinación en la misma llamada. Lo que no se pasa se
 * queda como está. Por separado no vale: cada easeCamera cancela el anterior.
 */
private fun MapLibreMap.easeTo(target: LatLng?, bearing: Double?, tilt: Double) {
    val now = cameraPosition
    easeCamera(
        CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(target ?: now.target)
                .zoom(now.zoom)
                .bearing(bearing ?: now.bearing)
                .tilt(tilt)
                .build(),
        ),
        NAV_CAMERA_MS,
    )
}

/** El mapa otra vez plano y al norte, animado y sin moverlo de sitio. Un solo easeCamera: dos
 * seguidos (uno de inclinación y otro de rumbo) se pisan el uno al otro. */
private fun MapLibreMap.straighten() {
    if (isStraight()) return
    val position = cameraPosition
    easeCamera(
        CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(position.target)
                .zoom(position.zoom)
                .bearing(0.0)
                .tilt(0.0)
                .build(),
        ),
        NAV_CAMERA_MS,
    )
}

/**
 * Los dos gestos que MapLibre no da hechos tal como los quiere la app: mantener pulsado con
 * [Constants.MAP_LONG_PRESS_MS] (su onMapLongClick va con los ~500 ms del sistema y no se puede
 * alargar) y avisar de que el dedo ha arrastrado el mapa, una sola vez por gesto y solo con un
 * dedo. Es lo mismo que hace [TouchOverlay] en osmdroid, aquí desde el propio MapView porque
 * MapLibre no tiene capas de toque donde colgarse.
 */
@SuppressLint("ViewConstructor")
private class LokateMapLibreView(
    context: Context,
    options: MapLibreMapOptions,
    private val onLongPress: (GeoPoint) -> Unit,
    private val onPan: () -> Unit,
) : MapView(context, options) {

    /** El mapa en cuanto está listo: hace falta para saber a qué coordenada corresponde el
     * punto de la pantalla que se ha tocado. */
    var boundMap: MapLibreMap? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var downX = 0f
    private var downY = 0f
    private var panned = false
    private var pinch = false
    private var destroyed = false

    /** onDestroy dos veces (el ciclo de vida y el onDispose de Compose) revienta: la primera
     * manda. */
    fun destroyOnce() {
        if (destroyed) return
        destroyed = true
        onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                panned = false
                pinch = false
                // El punto se calcula AHORA: si se calculase al disparar, un mapa que se hubiera
                // movido entre medias daría la dirección de otro sitio.
                val point = boundMap?.projection?.fromScreenLocation(PointF(event.x, event.y))
                cancel()
                if (point != null) {
                    pending = Runnable {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onLongPress(point.toGeoPoint())
                    }.also { handler.postDelayed(it, Constants.MAP_LONG_PRESS_MS) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                    cancel()
                    if (!panned && !pinch && event.pointerCount == 1) {
                        panned = true
                        onPan()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pinch = true
                cancel()
            }
            else -> cancel()
        }
        return super.dispatchTouchEvent(event)
    }

    private fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }
}

/**
 * Edificios en 3D, si el estilo trae de dónde sacar su altura.
 *
 * Los estilos de OpenFreeMap van con el esquema OpenMapTiles, donde la capa `building` lleva
 * `render_height`/`render_min_height`. El claro (Liberty) ya trae su propia capa en 3D; el
 * oscuro no, y se le añade aquí. Sin esa fuente no se hace nada: no hay alturas que extruir.
 */
private fun Style.addBuildings3d() {
    if (getLayer(BUILDINGS_3D_LAYER) != null || getLayer("building-3d") != null) return
    if (getSource("openmaptiles") == null) return
    val layer = FillExtrusionLayer(BUILDINGS_3D_LAYER, "openmaptiles").apply {
        sourceLayer = "building"
        minZoom = 14f
        setProperties(
            PropertyFactory.fillExtrusionColor(BUILDINGS_3D_COLOR),
            PropertyFactory.fillExtrusionHeight(Expression.get("render_height")),
            PropertyFactory.fillExtrusionBase(Expression.get("render_min_height")),
            PropertyFactory.fillExtrusionOpacity(0.85f),
        )
    }
    // Por debajo de los rótulos del mapa: si no, los edificios taparían los nombres de calle.
    val firstLabel = layers.firstOrNull { it is SymbolLayer }?.id
    if (firstLabel != null) addLayerBelow(layer, firstLabel) else addLayer(layer)
}

private const val BUILDINGS_3D_LAYER = "lokate-buildings-3d"

/** Gris azulado: se distingue del suelo tanto en el estilo claro como en el oscuro sin tener
 * que elegir un color por estilo. */
private const val BUILDINGS_3D_COLOR = "#8a8f96"

/** Zoom de MapLibre a partir del de osmdroid y al revés. No son el mismo número: las teselas
 * vectoriales son de 512 px y las de osmdroid de 256, así que el mismo nivel se ve el doble de
 * cerca. Sin esto, cambiar de estilo daba un salto de zoom y la cámara recordada entre pestañas
 * no cuadraba entre los dos mapas. */
private fun Double.toGlZoom(): Double = this - 1.0

private fun Double.toOsmZoom(): Double = this + 1.0

private fun GeoPoint.toLatLng() = LatLng(latitude, longitude)

private fun LatLng.toGeoPoint() = GeoPoint(latitude, longitude)

private fun LatLon.toLatLng() = LatLng(lat, lng)

private fun List<LatLon>.toLatLngs() = map { it.toLatLng() }

/** El mismo anillo con el primer punto repetido al final: un relleno se cierra solo, una línea
 * no. */
/** Lo mismo que [closed] para un anillo que ya viene en GeoPoint (las isocronas). */
private fun List<GeoPoint>.closedGeo(): List<LatLng> =
    map { it.toLatLng() } + LatLng(first().latitude, first().longitude)

private fun List<LatLon>.closed(): List<LatLng> = toLatLngs() + LatLng(first().lat, first().lng)

/** Un color de Android como lo quiere MapLibre (sus opciones van en texto). La transparencia va
 * aparte, en la opacidad de cada anotación. */
private fun Int.rgba(): String = String.format("#%06X", 0xFFFFFF and this)

/** La transparencia de un color, de 0 a 1: es lo que MapLibre pide por separado. */
private fun Int.alphaFraction(): Float = Color.alpha(this) / 255f

/** Un rótulo plantado en el mapa: letra del color que toque con borde blanco alrededor para que
 * se lea sobre cualquier estilo. No es el recuadro blanco de osmdroid —aquí el texto lo pinta el
 * propio mapa con sus fuentes— pero cumple lo mismo. */
private fun textOptions(
    at: LatLon,
    text: String,
    sizeSp: Float,
    color: Int,
    anchor: String,
): SymbolOptions {
    val clean = text.trim()
    return SymbolOptions()
        .withLatLng(at.toLatLng())
        .withTextField(if (clean.length > LABEL_MAX_CHARS) clean.take(LABEL_MAX_CHARS) + "…" else clean)
        // La fuente que ya usan los estilos de OpenFreeMap: pedir otra dejaría el rótulo en
        // blanco, porque los glifos los sirve el propio proveedor del estilo.
        .withTextFont(arrayOf("Noto Sans Regular"))
        .withTextSize(sizeSp)
        .withTextColor(color.rgba())
        .withTextHaloColor("#ffffff")
        .withTextHaloWidth(1.2f)
        .withTextAnchor(anchor)
}