package com.dskmusic.lokate.ui.map

import android.app.Application
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.offline.OfflineMaps
import com.dskmusic.lokate.util.MapStyle
import kotlinx.coroutines.delay
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

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

/** Archivos .map descargados, listos para pasarle a [applyMapStyle]. */
@Composable
fun rememberOfflineMapFiles(): List<File> {
    val context = LocalContext.current
    LaunchedEffect(Unit) { OfflineMaps.refresh(context) }
    return OfflineMaps.installed.collectAsStateWithLifecycle().value
}

/**
 * Deja el mapa con el estilo elegido (teselas + filtro de color).
 *
 * Sin conexión y sin ningún archivo descargado cae al mapa estándar: es mejor enseñar el mapa
 * de siempre que una pantalla en blanco. El aviso de "descárgate una zona" lo da MapScreen.
 */
fun MapView.applyMapStyle(style: MapStyle, offlineFiles: List<File> = emptyList()) {
    if (style.isOffline && offlineFiles.isNotEmpty()) {
        // Qué archivos alimentan ahora mismo al proveedor: sin esto, descargar o borrar una zona
        // no se vería hasta reabrir la app, y rehacerlo en cada recomposición reabriría los .map
        // (decenas de MB) cada 15 segundos.
        val signature = offlineFiles.joinToString { it.name }
        // Un .map ilegible (descarga corrupta, archivo copiado a mano) hace saltar a Mapsforge al
        // abrirlo: mejor volver al mapa de internet que dejar la app tirada.
        val already = tag == signature
        val ready = already || runCatching {
            MapsForgeTileSource.createInstance(context.applicationContext as Application)
            val source = MapsForgeTileSource.createFromFiles(offlineFiles.toTypedArray())
            setTileProvider(MapsForgeTileProvider(SimpleRegisterReceiver(context), source, null))
            tag = signature
        }.isSuccess
        if (ready) {
            mapOverlay?.setColorFilter(if (style.isDark) DARK_MODE_FILTER else null)
            // Proveedor recién puesto: sin repintar, el mapa se queda en blanco hasta tocarlo.
            if (!already) invalidate()
            return
        }
    }
    // Volviendo a las teselas de internet: el proveedor de Mapsforge no sabe descargar nada.
    var changed = false
    if (tag != null) {
        setTileProvider(MapTileProviderBasic(context.applicationContext))
        tag = null
        changed = true
    }
    val tileSource = when (style) {
        MapStyle.SATELLITE -> SatelliteTileSource
        MapStyle.DARK, MapStyle.STANDARD, MapStyle.OFFLINE, MapStyle.OFFLINE_DARK -> TileSourceFactory.MAPNIK
    }
    // setTileSource vacía la caché de teselas en memoria de osmdroid: llamarlo en cada
    // recomposición (una por sondeo, ~15s) obligaba a releer de disco o volver a descargar todo
    // lo visible una y otra vez. Solo se toca si el estilo ha cambiado.
    if (tileProvider.tileSource != tileSource) {
        setTileSource(tileSource)
        changed = true
    }
    mapOverlay?.setColorFilter(if (style.isDark) DARK_MODE_FILTER else null)
    // Cambiar de fuente deja la vista con las teselas viejas hasta el siguiente gesto: repintar
    // ya, para que el estilo nuevo cargue sin tener que pellizcar el mapa.
    if (changed) invalidate()
}

/** Lo que tarda el mapa en quedarse quieto tras un salto: durante el camino osmdroid va
 * avisando de centros intermedios (y al cambiar de proveedor de teselas suelta otro evento con
 * el centro todavía sin actualizar), así que solo cuenta dónde se queda. */
private const val COVERAGE_SETTLE_MS = 500L

/**
 * Estilo que hay que aplicar de verdad. En modo sin conexión, mirar una zona que no se ha
 * descargado (un familiar en otro país) dejaría el mapa en blanco: se cae al mapa de internet y
 * se avisa con un toast. Al volver a una zona descargada vuelve solo, y avisa igual.
 */
@Composable
fun rememberEffectiveMapStyle(map: MapView, style: MapStyle, offlineFiles: List<File>): MapStyle {
    val context = LocalContext.current
    // covered: lo que se ve ahora mismo, cambia en cada evento del mapa.
    // applied: lo último que se ha aplicado y avisado, ya sin los tumbos del camino.
    var covered by remember { mutableStateOf(true) }
    var applied by remember { mutableStateOf(true) }
    DisposableEffect(map, style, offlineFiles) {
        if (!style.isOffline || offlineFiles.isEmpty()) {
            covered = true
            applied = true
            onDispose { }
        } else {
            // El mapa recién creado está en (0, 0) hasta que alguien lo centra: hasta entonces
            // se ajusta en silencio, que si no el primer centrado soltaría un aviso nada más
            // abrir la pantalla. El aviso es para cuando cambia algo, no para recibir al usuario.
            var centered = map.mapCenter.latitude != 0.0 || map.mapCenter.longitude != 0.0
            val initial = !centered || OfflineMaps.covers(map.mapCenter.latitude, map.mapCenter.longitude)
            covered = initial
            applied = initial
            val listener = object : MapListener {
                fun recheck(): Boolean {
                    val lat = map.mapCenter.latitude
                    val lng = map.mapCenter.longitude
                    covered = OfflineMaps.covers(lat, lng)
                    if (!centered && (lat != 0.0 || lng != 0.0)) {
                        centered = true
                        applied = covered
                    }
                    return false
                }

                override fun onScroll(event: ScrollEvent?) = recheck()
                override fun onZoom(event: ZoomEvent?) = recheck()
            }
            map.addMapListener(listener)
            onDispose { map.removeMapListener(listener) }
        }
    }
    LaunchedEffect(covered, style, offlineFiles) {
        if (covered == applied) return@LaunchedEffect
        delay(COVERAGE_SETTLE_MS)
        applied = covered
        Toast.makeText(
            context,
            if (covered) R.string.offline_maps_back_in_area else R.string.offline_maps_out_of_area,
            Toast.LENGTH_SHORT,
        ).show()
    }
    return if (applied) style else style.online
}

/** Cada cuánto se comprueba si quedan teselas sin su versión buena. Lo bastante espaciado para
 * no repintar sin parar y lo bastante corto para que el usuario no llegue a fijarse. */
private const val TILE_RETRY_INTERVAL_MS = 1_500L

/** Reintentos seguidos sin que baje el número de teselas a medias. Pasados estos, esas teselas
 * es que no existen (mar, zoom más allá de lo que publica el proveedor) y seguir pidiéndolas
 * solo gastaría datos y batería. El contador se pone a cero en cuanto hay cualquier avance o el
 * usuario mueve el mapa. */
private const val TILE_RETRY_MAX = 5

/** Reintentos de los gordos (ver abajo). Dos bastan: si tras vaciar la caché dos veces siguen sin
 * llegar, o no existen o no hay red, y en ninguno de los dos casos lo arregla insistir. */
private const val TILE_HARD_RETRY_MAX = 2

/** Tope de teselas que se piden a mano de una vez (ver [prefetchRealTiles]). En zoom 20 la
 * pantalla de un móvil cabe en unas 15 teselas de nivel 19, y de 21 para arriba en 6 o menos;
 * 24 deja margen y a la vez impide una tormenta de descargas si la vista fuese enorme. */
private const val PREFETCH_MAX_TILES = 24

/**
 * Último zoom con teselas de VERDAD en la fuente que esté puesta: 19 tanto en el mapa de OSM como
 * en el satélite de Esri (los mapas descargados traen el suyo).
 */
fun MapView.maxRealTileZoom(): Double = tileProvider.tileSource.maximumZoomLevel.toDouble()

/**
 * Pide a mano las teselas de [maxRealTileZoom] que cubren lo que se está viendo.
 *
 * Por encima de ese nivel osmdroid no pide nada: se salta el descargador (la fuente no publica
 * esos zooms) y pasa el turno al aproximador, que amplía una tesela de más abajo... pero solo si
 * ya la tiene guardada. En un sitio donde no se ha estado nunca no tiene ninguna, y como tampoco
 * la pide, la pantalla se queda en la cuadrícula vacía para siempre. Pidiéndola nosotros, al
 * llegar se guarda en la caché de disco, el aproximador la amplía y el mapa se pinta — borroso,
 * como cualquier mapa a ese nivel, pero sin tocarle el zoom al usuario. Al moverse por la zona
 * esto se repite con las teselas nuevas que vayan haciendo falta.
 */
private fun MapView.prefetchRealTiles() {
    val zoom = tileProvider.tileSource.maximumZoomLevel
    val box = projection.boundingBox
    val side = 1 shl zoom

    fun tileX(lon: Double) = (((lon + 180.0) / 360.0) * side).toInt().coerceIn(0, side - 1)
    fun tileY(lat: Double): Int {
        // Mercator, la cuenta de toda la vida de las teselas (85.05 es donde el mapa se corta).
        val rad = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        return ((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * side).toInt().coerceIn(0, side - 1)
    }

    val x0 = tileX(box.lonWest)
    val x1 = tileX(box.lonEast)
    val y0 = tileY(box.latNorth)
    val y1 = tileY(box.latSouth)
    // ponytail: si la vista cruza el meridiano 180 los índices se dan la vuelta; a estos zooms la
    // pantalla mide metros, así que se deja pasar en vez de partir el rectángulo en dos.
    if (x1 < x0 || y1 < y0) return
    var asked = 0
    for (x in x0..x1) {
        for (y in y0..y1) {
            if (asked++ >= PREFETCH_MAX_TILES) return
            // Es lo mismo que hace osmdroid al dibujar: si ya está en caché devuelve y no gasta
            // nada, si no la descarga en segundo plano y al terminar repinta el mapa él solo.
            tileProvider.getMapTile(MapTileIndex.getTileIndex(zoom, x, y))
        }
    }
}

/**
 * Vuelve a pedir las teselas que se quedaron a medias (el mapa "pixelado" al abrirlo).
 *
 * osmdroid, mientras una tesela no está, dibuja la del zoom de arriba estirada — de ahí los
 * cuadros gordos. La vuelve a pedir SOLO al dibujarse, así que si la descarga se le cayó de la
 * cola con el mapa quieto, ahí se queda hasta que el usuario lo toca (por eso se arreglaba al
 * hacer zoom). Repintar mientras queden teselas estiradas o ausentes las re-pide; en cuanto
 * están todas, esto no hace nada.
 */
@Composable
fun TileRetryEffect(map: MapView) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(map, lifecycleOwner) {
        // Solo mientras el mapa está delante: el bucle no termina nunca (cuando no falta nada
        // sigue mirando, porque el usuario puede mover el mapa en cualquier momento), así que
        // sin esto seguía despertando cada segundo y medio con la app en segundo plano — y ahí
        // no hay nada que repintar ni proceso que se congele, que el servicio lo mantiene vivo.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var pending = -1
            var tries = 0
            var hardTries = 0
            while (true) {
                delay(TILE_RETRY_INTERVAL_MS)
                // isDone: solo cuentan las cifras de una pasada de dibujado terminada.
                val states = map.overlayManager.tilesOverlay?.tileStates?.takeIf { it.isDone } ?: continue
                val missing = states.scaled + states.notFound
                if (missing != pending) {
                    // Algo se ha movido (han llegado teselas, o el usuario ha cambiado la vista).
                    pending = missing
                    tries = 0
                    hardTries = 0
                }
                if (missing == 0) continue
                if (map.zoomLevelDouble > map.maxRealTileZoom()) {
                    // Aquí "a medias" no significa nada: TODO lo que se ve son ampliaciones y
                    // siempre cuenta como estirado. El único hueco de verdad es notFound, que es
                    // donde no se pintó nada. Y no se arregla repintando ni vaciando la caché
                    // (eso tiraría lo poco que hubiera): falta la tesela de abajo, y esa hay que
                    // pedirla a mano.
                    if (states.notFound > 0) map.prefetchRealTiles()
                    continue
                }
                if (tries++ < TILE_RETRY_MAX) {
                    map.invalidate()
                    continue
                }
                // Repintar ya no sirve: la tesela se quedó marcada como pedida o como fallida y
                // osmdroid no la vuelve a pedir por mucho que se redibuje (por eso el usuario
                // tenía que mover el zoom: a otro nivel son teselas distintas). Vaciar la caché
                // en memoria las deja sin marca y el siguiente dibujado las pide de nuevo; las
                // buenas que se tiran vuelven del disco, sin descargar nada.
                //
                // Solo con la pantalla ENTERA vacía, que es el atasco de verdad: faltando unas
                // pocas lo normal es que no existan (mar, borde del mapa descargado, zoom más allá
                // de lo que publica el proveedor) y vaciar la caché haría parpadear el mapa entero
                // para volver a pintar lo mismo.
                if (missing == states.total && hardTries++ < TILE_HARD_RETRY_MAX) {
                    map.tileProvider.clearTileCache()
                    map.invalidate()
                }
            }
        }
    }
}

/** Botón de capas con el menú de estilos, igual en el mapa principal y en el historial. */
@Composable
fun MapStyleMenuButton(onSelect: (MapStyle) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Layers, contentDescription = stringResource(R.string.map_style_button))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val items = listOf(
                Triple(MapStyle.STANDARD, R.string.map_style_standard, Icons.Filled.Map),
                Triple(MapStyle.SATELLITE, R.string.map_style_satellite, Icons.Filled.Satellite),
                Triple(MapStyle.DARK, R.string.map_style_dark, Icons.Filled.DarkMode),
                Triple(MapStyle.OFFLINE, R.string.map_style_offline, Icons.Filled.CloudOff),
                Triple(MapStyle.OFFLINE_DARK, R.string.map_style_offline_dark, Icons.Filled.NightsStay),
            )
            items.forEach { (style, label, icon) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                    onClick = {
                        onSelect(style)
                        expanded = false
                    },
                )
            }
        }
    }
}
