package com.dskmusic.lokate.ui.common

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices

/** Última ubicación conocida del dispositivo, si hay permiso y alguna guardada. No la pide al
 * GPS: el servicio en segundo plano ya la está refrescando continuamente. */
@SuppressLint("MissingPermission")
fun lastKnownLocation(context: Context, onFound: (Location) -> Unit) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        return
    }
    LocationServices.getFusedLocationProviderClient(context).lastLocation
        .addOnSuccessListener { location -> if (location != null) onFound(location) }
}

/**
 * Tu posición para calcular distancias. `null` mientras no se sepa (sin permiso, o todavía
 * resolviéndose).
 *
 * ponytail: se lee una sola vez al entrar en la pantalla, no se sigue en vivo — para un "a 1,2 km
 * de ti" no merece la pena mantener suscripciones de ubicación abiertas.
 */
@Composable
fun rememberMyLocation(): Location? {
    val context = LocalContext.current
    var location by remember { mutableStateOf<Location?>(null) }
    LaunchedEffect(Unit) { lastKnownLocation(context) { location = it } }
    return location
}
