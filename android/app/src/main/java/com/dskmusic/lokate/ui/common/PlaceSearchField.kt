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
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.NominatimService
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.util.PlaceQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Un sitio elegido en el buscador. */
data class PlaceResult(val label: String, val lat: Double, val lng: Double)

/**
 * Buscador de sitios de una sola línea con lupa a la derecha. La búsqueda arranca solo al pulsar
 * la lupa (o "buscar" en el teclado), nunca al teclear: buscar carácter a carácter encolaba una
 * petición por letra y dejaba la app colgada.
 *
 * Antes de preguntar a Nominatim se intenta entender el texto como un punto del mapa
 * ([PlaceQuery]), así que también vale pegar coordenadas o un enlace de Google Maps.
 */
@Composable
fun PlaceSearchField(
    nominatim: NominatimService,
    label: String,
    modifier: Modifier = Modifier,
    onPicked: (PlaceResult) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // Nominatim exige 1 petición/segundo y aquí las dispara el usuario: dos toques seguidos a
    // la lupa se separan solos en vez de arriesgar un bloqueo del servicio.
    val lastSearchAtMs = remember { longArrayOf(0L) }

    fun search() {
        val text = query.trim()
        if (text.isEmpty() || searching) return
        keyboard?.hide()
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
                        val elapsed = System.currentTimeMillis() - lastSearchAtMs[0]
                        if (elapsed < Constants.NOMINATIM_MIN_INTERVAL_MS) {
                            delay(Constants.NOMINATIM_MIN_INTERVAL_MS - elapsed)
                        }
                        lastSearchAtMs[0] = System.currentTimeMillis()
                        null to nominatim.search(resolved.searchText).mapNotNull {
                            val lat = it.lat.toDoubleOrNull()
                            val lng = it.lon.toDoubleOrNull()
                            if (lat != null && lng != null) PlaceResult(it.display_name, lat, lng) else null
                        }
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
                        onClick = { query = ""; results = null; failed = false },
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

            // Lista corta (Nominatim devuelve 5): Column normal, sin scroll propio, para poder
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
