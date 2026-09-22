package com.dskmusic.lokate.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.location.UpdateMode
import com.dskmusic.lokate.util.LocationFrequency
import com.dskmusic.lokate.util.formatRelativeTime
import com.dskmusic.lokate.util.formatTimestamp
import com.dskmusic.lokate.util.parseIsoDate

/**
 * Por qué la última posición de alguien es de hace lo que es.
 *
 * El ritmo que cada uno elige en Ajustes solo manda mientras se está moviendo: su móvil espacia
 * las actualizaciones a una cada 15 minutos en cuanto se queda quieto o entra en una de sus
 * wifis de casa. Visto desde fuera, eso es indistinguible de "se ha roto algo", y de ahí este
 * archivo: el modo lo manda el propio móvil en cada ping (ver [UpdateMode]) y aquí solo se
 * traduce a algo que se pueda leer.
 */

/** Los dos números del móvil del OTRO, que es de quien hablamos aquí; por eso no se leen de
 * LocationForegroundService, que son los de este. Coinciden salvo que esa persona tenga una
 * versión distinta de la app, y entonces lo único que pasa es que el aviso de "no da señales"
 * llega algo antes o algo después. */
private const val IDLE_INTERVAL_MS = 15 * 60_000L
private const val MAX_BATCH_DELAY_MS = 60_000L

/** Cuánto hay que pasarse de lo esperado para dar la voz de alarma. Un ping que falla no se
 * reintenta (el siguiente sale al terminar el intervalo), así que perder uno suelto es normal y
 * no debería pintar nada en rojo: con el doble, hacen falta dos seguidos. */
private const val OVERDUE_FACTOR = 2

/**
 * Lo que puede tardar como mucho en llegar una actualización de quien está en [mode], o null si
 * en ese modo no hay nada que esperar: "solo bajo demanda" y "desactivado" no mandan nada por su
 * cuenta, y de una app anterior a esta versión no se sabe.
 */
fun expectedGapMs(frequency: LocationFrequency?, mode: String?): Long? {
    return when (mode) {
        // En vivo el móvil manda cada pocos segundos; el margen es para el camino.
        UpdateMode.LIVE -> 30_000L
        UpdateMode.STILL, UpdateMode.HOME_WIFI -> IDLE_INTERVAL_MS
        // El lote es lo que el sistema puede retrasar la entrega juntando varios fixes.
        UpdateMode.MOVING -> frequency?.intervalMs?.takeIf { it > 0L }?.plus(MAX_BATCH_DELAY_MS)
        else -> null
    }
}

/** Lleva tanto sin dar señales que ya no lo explica su forma de actualizar. */
fun isOverdue(location: LocationDto): Boolean {
    if (location.live_seconds > 0) return false
    val expected = expectedGapMs(frequencyOf(location), modeOf(location)) ?: return false
    val date = parseIsoDate(location.timestamp) ?: return false
    return System.currentTimeMillis() - date.time > expected * OVERDUE_FACTOR
}

/** El seguimiento en vivo manda sobre lo que diga el último ping: puede haber empezado después.
 * Y quien tiene el envío apagado se queda con el modo del último ping que mandó, de cuando aún
 * lo tenía encendido: mandaría "en movimiento" y, detrás, el rojo de "no da señales". */
private fun modeOf(location: LocationDto): String? = when {
    location.live_seconds > 0 -> UpdateMode.LIVE
    frequencyOf(location)?.sendsPeriodicUpdates == false -> UpdateMode.ON_DEMAND
    else -> location.update_mode
}

private fun frequencyOf(location: LocationDto): LocationFrequency? =
    LocationFrequency.entries.find { it.name == location.location_frequency }

/** Coletilla para la línea de la hora: "7 min · en reposo". null = no hay nada que añadir (el
 * seguimiento en vivo ya se ve en su propia etiqueta, y de lo desconocido no se opina). */
@StringRes
fun updateModeShortLabel(location: LocationDto): Int? = when {
    isOverdue(location) -> R.string.update_short_overdue
    else -> when (modeOf(location)) {
        UpdateMode.MOVING -> R.string.update_short_moving
        UpdateMode.STILL -> R.string.update_short_still
        UpdateMode.HOME_WIFI -> R.string.update_short_home_wifi
        UpdateMode.ON_DEMAND -> R.string.update_short_on_demand
        else -> null
    }
}

@StringRes
private fun stateLabel(mode: String?): Int = when (mode) {
    UpdateMode.MOVING -> R.string.update_state_moving
    UpdateMode.STILL -> R.string.update_state_still
    UpdateMode.HOME_WIFI -> R.string.update_state_home_wifi
    UpdateMode.LIVE -> R.string.update_state_live
    UpdateMode.ON_DEMAND -> R.string.update_state_on_demand
    else -> R.string.update_state_unknown
}

@StringRes
private fun bodyLabel(mode: String?): Int = when (mode) {
    UpdateMode.MOVING -> R.string.update_body_moving
    UpdateMode.STILL -> R.string.update_body_still
    UpdateMode.HOME_WIFI -> R.string.update_body_home_wifi
    UpdateMode.LIVE -> R.string.update_body_live
    UpdateMode.ON_DEMAND -> R.string.update_body_on_demand
    else -> R.string.update_body_unknown
}

/** Resumen de una línea para la ficha ("Ahora mismo: Sin moverse"). Tocarlo abre el detalle. */
@Composable
fun UpdateStatusSummary(location: LocationDto, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        stringResource(R.string.update_info_state, stringResource(stateLabel(modeOf(location)))),
        style = MaterialTheme.typography.bodySmall,
        textDecoration = TextDecoration.Underline,
        color = if (isOverdue(location)) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier.clickable(onClick = onClick),
    )
}

/** La explicación completa, la misma desde la lista de gente y desde la ficha. */
@Composable
fun UpdateStatusDialog(location: LocationDto, onDismiss: () -> Unit) {
    val mode = modeOf(location)
    val frequency = frequencyOf(location)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.update_info_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(
                        R.string.update_info_last,
                        formatRelativeTime(location.timestamp),
                        formatTimestamp(location.timestamp),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        R.string.update_info_frequency,
                        stringResource(frequency?.labelRes ?: R.string.frequency_short_unknown),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.update_info_state, stringResource(stateLabel(mode))),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(bodyLabel(mode)), style = MaterialTheme.typography.bodyMedium)
                if (isOverdue(location)) {
                    Text(
                        stringResource(R.string.update_info_overdue),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    stringResource(R.string.update_info_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
