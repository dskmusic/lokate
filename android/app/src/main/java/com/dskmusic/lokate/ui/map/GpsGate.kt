package com.dskmusic.lokate.ui.map

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dskmusic.lokate.R
import com.dskmusic.lokate.util.ConfigCheck
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

/**
 * Lo que hay que encender para poder navegar, preguntado antes de arrancar.
 *
 * Con la ubicación del sistema apagada el viaje salía a medio montar: el servicio arranca, pide
 * posiciones y no llega ninguna, así que nunca se publica un estado y la pantalla se queda sin
 * cartel de maniobra y sin botón de salir — parecía que la app se había estropeado, y solo se
 * podía parar desde el aviso de la barra.
 *
 * [onEnabled] se llama cuando la ubicación ya está encendida: quien llama arranca el viaje ahí.
 */
@Composable
fun GpsRequiredDialog(onDismiss: () -> Unit, onEnabled: () -> Unit) {
    val context = LocalContext.current
    val resolution = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        // Hay móviles que devuelven CANCELED aunque la ubicación quede encendida: se mira el
        // interruptor de verdad antes de darlo por rechazado.
        if (result.resultCode == Activity.RESULT_OK || ConfigCheck.isLocationEnabled(context)) {
            onEnabled()
        } else {
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nav_gps_off_title)) },
        text = { Text(stringResource(R.string.nav_gps_off_body)) },
        confirmButton = {
            TextButton(onClick = { askForLocation(context, resolution, onEnabled, onDismiss) }) {
                Text(stringResource(R.string.nav_gps_off_enable))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Saca el diálogo del sistema para encender la ubicación sin salir de la app, que es el de Play
 * Services: [ResolvableApiException.getResolution] trae el permiso para lanzarlo.
 *
 * ponytail: sin Play Services (o si el diálogo no se deja lanzar) se abre la pantalla de ajustes
 * de ubicación y ahí se acaba nuestra parte — al volver hay que darle otra vez a navegar. Es un
 * toque más en el único caso en el que no hay diálogo que sacar.
 */
private fun askForLocation(
    context: Context,
    resolution: ActivityResultLauncher<IntentSenderRequest>,
    onEnabled: () -> Unit,
    onFailed: () -> Unit,
) {
    // El intervalo es indiferente aquí: lo que se comprueba es que la alta precisión esté
    // disponible, que es lo que pide la navegación (ver NavigationService.startLocation).
    val request = LocationSettingsRequest.Builder()
        .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).build())
        .build()
    LocationServices.getSettingsClient(context).checkLocationSettings(request)
        // Ya estaba encendida (se encendió entre el aviso y el botón): a navegar sin más.
        .addOnSuccessListener { onEnabled() }
        .addOnFailureListener { error ->
            val sender = (error as? ResolvableApiException)?.resolution
            val lanzado = sender != null && runCatching {
                resolution.launch(IntentSenderRequest.Builder(sender).build())
            }.isSuccess
            if (!lanzado) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                onFailed()
            }
        }
}
