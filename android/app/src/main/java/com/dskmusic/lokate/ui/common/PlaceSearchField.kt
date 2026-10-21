package com.dskmusic.lokate.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.NominatimResultDto
import com.dskmusic.lokate.data.remote.NominatimService
import com.dskmusic.lokate.data.prefs.SettingsDataStore
import com.dskmusic.lokate.data.repository.LocationRepository
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.util.Coordinates
import com.dskmusic.lokate.util.PlaceQuery
import com.dskmusic.lokate.util.distanceMeters
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Nominatim admite 1 petición por segundo y aquí las dispara el usuario: esto espera lo que
 * falte antes de cada una en vez de arriesgar un bloqueo del servicio. */
private suspend fun throttle(lastSearchAtMs: LongArray) {
    val elapsed = System.currentTimeMillis() - lastSearchAtMs[0]
    if (elapsed < Constants.NOMINATIM_MIN_INTERVAL_MS) {
        delay(Constants.NOMINATIM_MIN_INTERVAL_MS - elapsed)
    }
    lastSearchAtMs[0] = System.currentTimeMillis()
}

/** Un sitio elegido en el buscador. */
data class PlaceResult(val label: String, val lat: Double, val lng: Double)

/** De lo que contesta el buscador a lo que se enseña en la lista. Vale para los dos —el del
 * servidor contesta con la misma forma que Nominatim a propósito, ver places.py— y tira lo que
 * venga sin coordenadas, que no se puede ni dibujar ni abrir. */
private fun toPlace(dto: NominatimResultDto): PlaceResult? {
    val lat = dto.lat.toDoubleOrNull()
    val lng = dto.lon.toDoubleOrNull()
    return if (lat != null && lng != null) PlaceResult(dto.display_name, lat, lng) else null
}

/** Los cuatro números de un viewbox, "lonOeste,latNorte,lonEste,latSur". */
private fun boxNumbers(box: String): List<Double>? =
    box.split(',').mapNotNull { it.trim().toDoubleOrNull() }.takeIf { it.size == 4 }

/** El centro de lo que se está mirando, para medir distancias desde ahí. */
private fun boxCenter(box: String): Coordinates? =
    boxNumbers(box)?.let { (oeste, norte, este, sur) ->
        Coordinates((norte + sur) / 2, (oeste + este) / 2)
    }

/**
 * El mismo trozo de mapa pero [veces] más ancho y más alto, con un mínimo de
 * [MIN_HALF_SPAN_DEG] de lado a cada costado del centro: es "por aquí al lado" cuando en lo que
 * se ve no hay nada. Sin el mínimo, mirando una calle de cerca, cuatro veces una calle sigue
 * siendo una calle.
 *
 * ponytail: al cruzar el antimeridiano no se parte en dos cajas, se recorta en ±180 (lo que
 * hacía antes con la caja visible, igual); solo afecta a quien busque en medio del Pacífico.
 */
private fun widenBox(box: String, veces: Double): String? =
    boxNumbers(box)?.let { (oeste, norte, este, sur) ->
        val lonMedio = (oeste + este) / 2
        val latMedio = (norte + sur) / 2
        val ancho = (abs(este - oeste) / 2 * veces).coerceAtLeast(MIN_HALF_SPAN_DEG)
        val alto = (abs(norte - sur) / 2 * veces).coerceAtLeast(MIN_HALF_SPAN_DEG)
        listOf(
            (lonMedio - ancho).coerceAtLeast(-180.0),
            (latMedio + alto).coerceAtMost(90.0),
            (lonMedio + ancho).coerceAtMost(180.0),
            (latMedio - alto).coerceAtLeast(-90.0),
        ).joinToString(",")
    }

/** 0,35° son unos 39 km a lo alto: el radio de "mi ciudad y sus alrededores". Ensanchar sale
 * gratis porque lo de dentro se ordena por distancia, así que lo cercano sigue saliendo primero;
 * lo que no sale gratis es quedarse corto, que es acabar con resultados de otra provincia. */
private const val MIN_HALF_SPAN_DEG = 0.35

/** Cuánto se ensancha el cerco en el segundo intento. */
private const val WIDEN_TIMES = 12.0

/** Cuántos sitios se le piden a Nominatim en los intentos cercanos. Los ordena por su
 * "importancia", no por distancia: pidiendo cinco en un cerco grande, los cinco pueden ser los
 * cinco importantes de lejos y el de al lado no venir. Se piden muchos y se ordenan aquí. */
private const val NEARBY_CANDIDATES = 20

/** Cuántos se enseñan al final: la lista va debajo del buscador, sobre el mapa, y sin scroll
 * propio (a propósito, ver abajo). */
private const val RESULTS_SHOWN = 5

/**
 * Buscador de sitios de una sola línea con lupa a la derecha. La búsqueda arranca solo al pulsar
 * la lupa (o "buscar" en el teclado), nunca al teclear: buscar carácter a carácter encolaba una
 * petición por letra y dejaba la app colgada.
 *
 * Antes de preguntar se intenta entender el texto como un punto del mapa ([PlaceQuery]), así que
 * también vale pegar coordenadas o un enlace de Google Maps.
 *
 * Y se pregunta en este orden: el buscador del servidor ([servidor], que es Pelias) y, si no
 * está o no conoce el sitio, Nominatim con sus tres cercos de siempre.
 */
@Composable
fun PlaceSearchField(
    nominatim: NominatimService,
    label: String,
    /** El buscador del servidor (Pelias, ver backend places.py): ordena por cercanía SIN recortar
     * el mundo y sin la espera de 1 s que exige Nominatim. null, no disponible o sin resultados =
     * se busca como siempre, que es el suplente. */
    servidor: LocationRepository? = null,
    /** Los ajustes, solo para saber si el buscador del servidor está encendido
     * ([SettingsDataStore.placeSearchPelias]). null = encendido, que es lo de siempre. */
    settings: SettingsDataStore? = null,
    modifier: Modifier = Modifier,
    /** El trozo de mapa que se está mirando, "lonOeste,latNorte,lonEste,latSur". Lo que haya
     * ahí dentro se contesta primero y ordenado por distancia a su centro, luego lo que haya en
     * el mismo trozo ensanchado, y solo entonces el mundo; null = el mundo directamente. */
    nearBox: () -> String? = { null },
    /** La X del buscador. Sirve para quitar del mapa la chincheta del último resultado. */
    onCleared: () -> Unit = {},
    /** Texto con el que arrancar, buscando ya: lo que llega compartido desde otra app. */
    textoInicial: String = "",
    onPicked: (PlaceResult) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Quién busca, según Ajustes. Apagado, [servidor] es como si no estuviera y busca Nominatim.
    val usarServidor = settings?.placeSearchPelias
        ?.collectAsStateWithLifecycle(initialValue = true)?.value ?: true
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // Cuándo se preguntó por última vez, para el freno de [throttle].
    val lastSearchAtMs = remember { longArrayOf(0L) }

    fun search() {
        val text = query.trim()
        if (text.isEmpty() || searching) return
        keyboard?.hide()
        // Se lee aquí y no dentro de la corrutina: el mapa solo se puede preguntar desde el hilo
        // principal.
        val box = nearBox()
        searching = true
        failed = false
        results = null
        scope.launch {
            // Todo el trabajo en Dispatchers.IO y envuelto en runCatching: así ni la red ni un
            // fallo inesperado pueden congelar la interfaz ni dejar la lupa girando y
            // deshabilitada para siempre (o sea, el buscador muerto).
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val resolved = PlaceQuery.resolve(text)
                    val point = resolved.coordinates
                    if (point != null) {
                        PlaceResult(text, point.lat, point.lng) to emptyList<PlaceResult>()
                    } else {
                        val centro = box?.let(::boxCenter)
                        // Primero el buscador del servidor: ordena por cercanía al centro de lo
                        // que se está mirando sin recortar nada, así que lo de al lado sale
                        // primero y lo de la otra punta del mundo sigue saliendo (que es lo que
                        // con los cercos de Nominatim se perdía). Si no contesta o no conoce el
                        // sitio, abajo está lo de siempre.
                        val delServidor = servidor.takeIf { usarServidor }
                            ?.searchPlaces(resolved.searchText, centro?.lat, centro?.lng)
                            ?.mapNotNull(::toPlace)
                            .orEmpty()
                        if (delServidor.isNotEmpty()) {
                            return@runCatching null to delServidor.take(RESULTS_SHOWN)
                        }

                        suspend fun ask(viewbox: String?) = nominatim.search(
                            resolved.searchText,
                            limit = if (viewbox != null) NEARBY_CANDIDATES else RESULTS_SHOWN,
                            viewbox = viewbox,
                            bounded = if (viewbox != null) 1 else null,
                        ).mapNotNull(::toPlace)
                        // Tres intentos, de lo cerca a lo lejos: lo que se está viendo; si por ahí
                        // no hay nada, el mismo trozo ensanchado (la tienda que se busca suele
                        // estar en la misma ciudad, pero no en el pedazo exacto que se mira);
                        // y solo si sigue sin salir nada, por todo el mundo, que es lo que hace
                        // que "Torre Eiffel" aparezca desde Canarias. Cada intento cuesta un
                        // segundo (el que exige Nominatim entre peticiones).
                        var cerca = emptyList<PlaceResult>()
                        if (box != null) {
                            throttle(lastSearchAtMs)
                            cerca = ask(box)
                            if (cerca.isEmpty()) {
                                widenBox(box, WIDEN_TIMES)?.let { ancho ->
                                    throttle(lastSearchAtMs)
                                    cerca = ask(ancho)
                                }
                            }
                        }
                        // Lo de cerca, por distancia al centro de lo que se mira: buscando una
                        // cadena con veinte tiendas, Nominatim las ordena por su "importancia" y
                        // la primera salía en la otra punta. Al resultado mundial no se le toca
                        // el orden: ahí la distancia pondría un bar llamado "Torre Eiffel" por
                        // delante de la torre.
                        val found = when {
                            cerca.isEmpty() -> { throttle(lastSearchAtMs); ask(null) }
                            centro == null -> cerca
                            else -> cerca.sortedBy {
                                distanceMeters(centro.lat, centro.lng, it.lat, it.lng)
                            }.take(RESULTS_SHOWN)
                        }
                        null to found
                    }
                }
            }
            searching = false
            outcome
                .onFailure { failed = true }
                .onSuccess { (point, found) ->
                    if (point != null) onPicked(point) else results = found
                }
        }
    }

    // Lo compartido desde otra app se escribe y se busca sin pedir nada: es lo que se iba a
    // tocar a mano de todas formas. Una sola vez, que es cuando cambia el texto que llega.
    LaunchedEffect(textoInicial) {
        if (textoInicial.isNotBlank()) {
            query = textoInicial
            search()
        }
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column {
            // Barra a mano en vez de OutlinedTextField: ese mide 56dp fijos de alto (más el
            // hueco de su etiqueta flotante) y sobre el mapa tapaba demasiado. El Surface ya
            // hace de caja, así que aquí el alto lo marcan los botones y nada más.
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it; results = null; failed = false },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { query = ""; results = null; failed = false; onCleared() },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.place_search_clear),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                IconButton(
                    onClick = { search() },
                    enabled = query.isNotBlank() && !searching,
                    modifier = Modifier.size(42.dp),
                ) {
                    if (searching) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.place_search_action),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            // Lista corta (cinco, ver RESULTS_SHOWN): Column normal, sin scroll propio, para poder
            // meter este buscador dentro de pantallas que ya scrollean.
            results?.let { found ->
                if (found.isEmpty()) {
                    Text(
                        stringResource(R.string.place_search_not_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                } else {
                    found.forEach { result ->
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { results = null; onPicked(result) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Text(
                                result.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (failed) {
                Text(
                    stringResource(R.string.place_search_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}
