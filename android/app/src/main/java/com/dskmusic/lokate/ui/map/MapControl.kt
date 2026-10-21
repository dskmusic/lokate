package com.dskmusic.lokate.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.data.remote.dto.ZoneDto
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.util.MapStyle
import kotlinx.coroutines.delay
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Lo que las pantallas le piden al mapa desde fuera (centrar, encuadrar, el zoom, qué se está
 * viendo, una foto), sin saber quién lo pinta. Con esto los botones "dónde estoy", "seguir en
 * vivo", el encuadre de una zona o de una ruta, el tic de radar y las fotos del mapa funcionan
 * igual con osmdroid y con MapLibre.
 *
 * Las coordenadas siguen siendo GeoPoint de osmdroid porque es el tipo que usa toda la app
 * (DTOs, rutas, navegación): es una clase de datos sin nada de dibujo dentro, y MapLibre la
 * convierte a LatLng en una línea. La geometría de verdad —los círculos— sí es neutra, ver
 * [LatLon] en MapGeometry.kt.
 */
interface MapControl {
    /** Centra el mapa. Anima los saltos cortos y salta directo los largos. */
    fun moveTo(point: GeoPoint)

    /** Centra y pone zoom de golpe, sin animar (el salto al empezar a navegar o al elegir un
     * resultado del buscador: encadenar zoom y animación cuelga el mapa de osmdroid). */
    fun jumpTo(point: GeoPoint, zoom: Double)

    fun setZoom(zoom: Double)

    /** El círculo entero de una zona encuadrado, con margen. */
    fun frameZone(lat: Double, lng: Double, radiusM: Double)

    /** La ruta entera encuadrada, descontando lo que tapan el buscador (arriba) y el cartel de
     * la ruta (abajo), en píxeles. */
    fun frameRoute(points: List<GeoPoint>, topInset: Int, bottomInset: Int)

    /** Todos los puntos encuadrados con el mismo margen por los cuatro lados: el encuadre de las
     * fotos de la ruta y de la regla. */
    fun framePoints(points: List<GeoPoint>, paddingPx: Int)

    /** Lo que se está viendo, como "lonWest,latNorth,lonEast,latSouth" — es el formato que pide
     * Nominatim para buscar cerca de aquí. null si el mapa todavía no sabe su tamaño. */
    fun nearBox(): String?

    /** Dónde cae ese punto en la pantalla, para pintar encima del mapa (ver [LiveRadarPulse]). */
    fun toScreen(point: GeoPoint): Offset?

    // Lo que necesitan las fotos del mapa (ver MapSnapshot.kt). Son las piezas mínimas: el
    // paseo por cuadrantes, el pie de foto y el guardado son los mismos para los dos mapas.

    val widthPx: Int
    val heightPx: Int

    /** Zoom actual y tope, en niveles de osmdroid (teselas de 256 px) para que los dos
     * renderizadores hablen del mismo número. */
    val zoom: Double
    val maxZoom: Double

    val center: GeoPoint

    /** La coordenada que cae en ese píxel de la pantalla. null si el mapa no está medido. */
    fun geoAt(x: Int, y: Int): GeoPoint?

    /** Una foto de lo que se está viendo, tras esperar [tileWaitMs] a que lleguen las teselas del
     * encuadre nuevo. null si el mapa no está listo o no hay memoria. */
    suspend fun frame(tileWaitMs: Long): Bitmap?

    /** Quita de la foto de la regla los puntos de en medio y/o las medidas de cada tramo (en una
     * ruta corta se amontonan); devuelve con qué volver a enseñarlos. */
    fun hideForMeasurePhoto(midPoints: Boolean, segmentDistances: Boolean): () -> Unit
}

/** [MapControl] sobre el mapa de osmdroid: todo esto ya existía como extensiones de MapView. */
private class OsmMapControl(private val view: MapView) : MapControl {
    override fun moveTo(point: GeoPoint) = view.moveTo(point)

    override fun jumpTo(point: GeoPoint, zoom: Double) {
        view.post {
            view.controller.setZoom(zoom)
            view.controller.setCenter(point)
        }
    }

    override fun setZoom(zoom: Double) {
        view.controller.setZoom(zoom)
    }

    override fun frameZone(lat: Double, lng: Double, radiusM: Double) = view.frameZone(lat, lng, radiusM)

    override fun frameRoute(points: List<GeoPoint>, topInset: Int, bottomInset: Int) =
        view.frameRoute(points, topInset, bottomInset)

    override fun framePoints(points: List<GeoPoint>, paddingPx: Int) {
        if (points.isEmpty()) return
        runCatching { view.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(points), false, paddingPx) }
    }

    override fun nearBox(): String? = view.boundingBox?.let {
        "${it.lonWest},${it.latNorth},${it.lonEast},${it.latSouth}"
    }

    override fun toScreen(point: GeoPoint): Offset? {
        val pixel = view.projection.toPixels(point, null) ?: return null
        return Offset(pixel.x.toFloat(), pixel.y.toFloat())
    }

    override val widthPx: Int get() = view.width
    override val heightPx: Int get() = view.height
    override val zoom: Double get() = view.zoomLevelDouble
    override val maxZoom: Double get() = view.maxZoomLevel
    override val center: GeoPoint get() = GeoPoint(view.mapCenter.latitude, view.mapCenter.longitude)

    override fun geoAt(x: Int, y: Int): GeoPoint? {
        if (view.width <= 0 || view.height <= 0) return null
        val point = view.projection.fromPixels(x, y)
        return GeoPoint(point.latitude, point.longitude)
    }

    override suspend fun frame(tileWaitMs: Long): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        delay(tileWaitMs)
        val bitmap = runCatching {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        view.draw(Canvas(bitmap))
        return bitmap
    }

    override fun hideForMeasurePhoto(midPoints: Boolean, segmentDistances: Boolean): () -> Unit =
        view.hideMeasureExtras(midPoints, segmentDistances)
}

fun mapControlOf(view: MapView): MapControl = OsmMapControl(view)

/**
 * El mapa de la app: el de siempre (osmdroid) o el vectorial (MapLibre), según el estilo
 * elegido. Los parámetros son los de [OsmMapView].
 */
@Composable
fun LokateMapView(
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
    /** El mando del mapa ya listo. Sustituye al onMapReady(MapView) de [OsmMapView]: lo que
     * llega aquí vale para los dos renderizadores. */
    onMapControl: (MapControl) -> Unit = {},
    initialZoom: Double = Constants.MAP_DEFAULT_ZOOM.toDouble(),
    mapStyle: MapStyle = MapStyle.STANDARD,
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
    /** Hueco en píxeles que hay que dejarle arriba al botón del norte para que no caiga debajo
     * del buscador de sitios. Solo lo usa MapLibre (ver [MapLibreMapView]). */
    compassTopInsetPx: Int = 0,
    /** Hueco en píxeles para el botón de los 45° navegando (la barra del viaje). Solo lo usa
     * MapLibre (ver [MapLibreMapView]). */
    tiltBottomInsetPx: Int = 0,
) {
    if (mapStyle.isVector) {
        MapLibreMapView(
            members = members,
            zones = zones,
            modifier = modifier,
            avatarBitmaps = avatarBitmaps,
            onMemberClick = onMemberClick,
            draggableUserIds = draggableUserIds,
            onMemberDragEnd = onMemberDragEnd,
            onMapTap = onMapTap,
            onMapLongPress = onMapLongPress,
            onUserPan = onUserPan,
            routePoints = routePoints,
            isochroneRings = isochroneRings,
            navPoint = navPoint,
            navRotate = navRotate,
            searchPoint = searchPoint,
            searchLabel = searchLabel,
            onSearchPointClick = onSearchPointClick,
            measurePoints = measurePoints,
            onMeasurePointDelete = onMeasurePointDelete,
            onMeasurePointMoved = onMeasurePointMoved,
            onMeasurePointInsert = onMeasurePointInsert,
            onMeasurePointGrab = onMeasurePointGrab,
            onMeasureLiveTotal = onMeasureLiveTotal,
            onMapControl = onMapControl,
            initialZoom = initialZoom,
            mapStyle = mapStyle,
            showAccuracy = showAccuracy,
            accuracyIntensity = accuracyIntensity,
            accuracyColor = accuracyColor,
            showAccuracyLabels = showAccuracyLabels,
            showZoneMargin = showZoneMargin,
            zoneMarginColor = zoneMarginColor,
            zoneFillIntensity = zoneFillIntensity,
            zoneColor = zoneColor,
            rememberCamera = rememberCamera,
            autoCenterUserId = autoCenterUserId,
            compassTopInsetPx = compassTopInsetPx,
            tiltBottomInsetPx = tiltBottomInsetPx,
        )
    } else {
        OsmMapView(
            members = members,
            zones = zones,
            modifier = modifier,
            avatarBitmaps = avatarBitmaps,
            onMemberClick = onMemberClick,
            draggableUserIds = draggableUserIds,
            onMemberDragEnd = onMemberDragEnd,
            onMapTap = onMapTap,
            onMapLongPress = onMapLongPress,
            onUserPan = onUserPan,
            routePoints = routePoints,
            isochroneRings = isochroneRings,
            navPoint = navPoint,
            navRotate = navRotate,
            searchPoint = searchPoint,
            searchLabel = searchLabel,
            onSearchPointClick = onSearchPointClick,
            measurePoints = measurePoints,
            onMeasurePointDelete = onMeasurePointDelete,
            onMeasurePointMoved = onMeasurePointMoved,
            onMeasurePointInsert = onMeasurePointInsert,
            onMeasurePointGrab = onMeasurePointGrab,
            onMeasureLiveTotal = onMeasureLiveTotal,
            onMapReady = { view -> onMapControl(mapControlOf(view)) },
            initialZoom = initialZoom,
            mapStyle = mapStyle,
            showAccuracy = showAccuracy,
            accuracyIntensity = accuracyIntensity,
            accuracyColor = accuracyColor,
            showAccuracyLabels = showAccuracyLabels,
            showZoneMargin = showZoneMargin,
            zoneMarginColor = zoneMarginColor,
            zoneFillIntensity = zoneFillIntensity,
            zoneColor = zoneColor,
            rememberCamera = rememberCamera,
            autoCenterUserId = autoCenterUserId,
        )
    }
}
