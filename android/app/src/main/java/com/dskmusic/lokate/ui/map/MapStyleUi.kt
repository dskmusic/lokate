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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import org.osmdroid.views.MapView
import java.io.File

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
