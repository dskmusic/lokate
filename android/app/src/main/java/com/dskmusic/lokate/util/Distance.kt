package com.dskmusic.lokate.util

import android.location.Location
import java.util.Locale
import kotlin.math.roundToInt

/** Metros en línea recta entre dos puntos (Location.distanceBetween ya hace la cuenta sobre el
 * elipsoide WGS84, no hace falta implementar Haversine a mano). */
fun distanceMeters(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Float {
    val out = FloatArray(1)
    Location.distanceBetween(fromLat, fromLng, toLat, toLng, out)
    return out[0]
}

/** "850 m", "3,2 km", "47 km". Sin traducir: las unidades se escriben igual en es y en en. */
fun formatDistance(meters: Float): String = when {
    meters < 1_000f -> "${meters.roundToInt()} m"
    meters < 10_000f -> String.format(Locale.getDefault(), "%.1f km", meters / 1_000f)
    else -> "${(meters / 1_000f).roundToInt()} km"
}

/** "8 min", "1 h", "1 h 12 min". Por debajo del minuto no se da un número: a esa escala una
 * estimación de tiempo no significa nada. Sin traducir, igual que [formatDistance]. */
fun formatDuration(minutes: Int): String = when {
    minutes < 1 -> "< 1 min"
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}
