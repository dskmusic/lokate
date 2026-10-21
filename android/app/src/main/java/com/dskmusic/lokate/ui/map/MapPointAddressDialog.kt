package com.dskmusic.lokate.ui.map

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.NominatimAddressDto
import com.dskmusic.lokate.data.remote.NominatimService
import com.dskmusic.lokate.data.repository.LocationRepository
import com.dskmusic.lokate.ui.common.apaisado
import com.dskmusic.lokate.ui.common.cuadroAncho
import com.dskmusic.lokate.ui.common.propiedadesCuadro
import com.dskmusic.lokate.util.LocationSharing
import java.util.Locale
import org.osmdroid.util.GeoPoint

/** Amarillo y negro de señal de carretera. No sale de la paleta de la app a propósito: es la
 * acción por la que se abre este cuadro nueve de cada diez veces y tiene que verse desde el otro
 * lado de la habitación, en tema claro y en oscuro igual. */
private val SIGN_YELLOW = Color(0xFFFFC400)
private val SIGN_INK = Color(0xFF14110D)

/**
 * Qué hay en el sitio del mapa que se acaba de mantener pulsado, con copiar y compartir.
 *
 * De lo concreto a lo general y cada cosa separada de la siguiente: calle y número en grande, la
 * dirección entera debajo (barrio, ciudad, código postal), una raya, el enlace de Google Maps
 * —que se toca y abre el punto allí—, las coordenadas, y abajo del todo el "cómo llegar" con
 * pinta de señal de tráfico. Donde no hay una dirección exacta, arriba va lo más aproximado que
 * conozca Nominatim de ese punto: la calle sin portal, el barrio, o el pueblo.
 *
 * Tumbado esa misma pila no cabe de alto y el "cómo llegar" se quedaba fuera, así que se parte
 * en dos columnas: a la izquierda lo que se lee, a la derecha lo que se toca.
 *
 * Mientras contesta solo gira la rueda; si no contesta o no conoce el sitio —en medio del campo
 * no hay dirección que dar— se quedan las coordenadas, que es la respuesta peor pero siempre
 * cierta.
 */
@Composable
fun MapPointAddressDialog(
    point: GeoPoint,
    nominatim: NominatimService,
    /** Quien contesta primero (Pelias, por el servidor, ver backend places.py). null o sin
     * respuesta = lo busca Nominatim, que es lo que se hacía antes. */
    servidor: LocationRepository? = null,
    /** Abre el "cómo llegar" hasta este punto (ver [RoutePlanner]). */
    onRouteTo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val tumbado = apaisado()
    val coords = remember(point) {
        String.format(Locale.US, "%.6f, %.6f", point.latitude, point.longitude)
    }
    // null = todavía se está buscando. Un DTO vacío = ya contestó y no sabe nada de ese sitio.
    var found by remember(point) { mutableStateOf<NominatimAddressDto?>(null) }

    LaunchedEffect(point) {
        // Primero el servidor y, si no contesta o de ahí no sabe nada, Nominatim.
        // ponytail: sin la espera de 1 s entre peticiones que pide Nominatim. No hace falta
        // aquí: cada consulta cuesta mantener el dedo quieto 1,5 s, así que el propio gesto va
        // más lento que el límite. Si algún día esto se llama solo, la espera habrá que ponerla.
        found = servidor?.addressOf(point.latitude, point.longitude)
            ?: runCatching { nominatim.reverse(point.latitude, point.longitude) }
                .getOrDefault(NominatimAddressDto())
    }

    val completa = found?.display_name?.takeIf { it.isNotBlank() }
    // El titular: calle y número. Si Nominatim no desglosa la dirección (servidores viejos), el
    // primer trozo de display_name ya suele ser el portal y la calle.
    val titular = found?.streetLine ?: completa?.substringBefore(",")?.trim() ?: coords
    // Lo que se copia y lo que se comparte: la dirección entera, no solo el titular.
    val texto = completa ?: coords

    // Las tres piezas de dentro, sueltas porque se colocan de dos maneras según cómo esté el
    // móvil: en pila de pie, en dos columnas tumbado.
    val direccion: @Composable () -> Unit = {
        // Las dos líneas juntas, que son la misma cosa dicha con más o menos detalle.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titular, style = MaterialTheme.typography.titleMedium)
            if (completa != null && completa != titular) {
                Text(
                    completa,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    // El enlace y las coordenadas, cada uno con su icono y con aire entre medias: antes iban
    // pegados a la dirección y los tres parecían el mismo párrafo.
    val enlaceYCoordenadas: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // Tocarlo abre el punto en Google Maps (o en el navegador si no está).
                modifier = Modifier.fillMaxWidth().clickable {
                    LocationSharing.openInGoogleMaps(context, point.latitude, point.longitude, titular)
                    onDismiss()
                },
            ) {
                Icon(
                    Icons.Filled.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    LocationSharing.mapsUrl(point.latitude, point.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.MyLocation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    coords,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    // Dentro del cuadro y no como cuarto botón abajo: cuatro botones no caben en una fila en un
    // móvil estrecho, y este abre otra cosa en vez de cerrar esto.
    val comoLlegar: @Composable () -> Unit = {
        Surface(
            color = SIGN_YELLOW,
            contentColor = SIGN_INK,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(3.dp, SIGN_INK),
            modifier = Modifier.fillMaxWidth().clickable { onRouteTo() },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Directions, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.route_to_action),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.cuadroAncho(),
        properties = propiedadesCuadro(),
        // Tumbado se cae el icono: son unos 40 dp de los ~330 que mide la pantalla de alto, y
        // el título ya dice de qué va el cuadro.
        icon = if (tumbado) null else {
            { Icon(Icons.Filled.Place, contentDescription = null) }
        },
        title = { Text(stringResource(R.string.map_point_address_title)) },
        text = {
            if (found == null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            } else if (tumbado) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f)) { direccion() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        enlaceYCoordenadas()
                        comoLlegar()
                    }
                }
            } else Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                direccion()
                HorizontalDivider()
                enlaceYCoordenadas()
                comoLlegar()
            }
        },
        // Los tres juntos y centrados: copiar, compartir y cerrar son del mismo peso, y a la
        // derecha dejaban todo el cuadro descolgado hacia un lado.
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(texto))
                    // Android 13+ ya enseña su propio aviso al copiar; el Toast sobraría.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, R.string.map_point_address_copied, Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                }) { Text(stringResource(R.string.copy_action)) }
                TextButton(onClick = {
                    // El selector del sistema: a dónde va esto lo decide quien comparte. El
                    // enlace de Google va debajo del texto, lo pone LocationSharing.
                    val intent = LocationSharing.shareIntent(point.latitude, point.longitude, texto)
                    context.startActivity(Intent.createChooser(intent, null))
                    onDismiss()
                }) { Text(stringResource(R.string.share_action)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}
