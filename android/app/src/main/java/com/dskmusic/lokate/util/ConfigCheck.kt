package com.dskmusic.lokate.util

import android.content.Context
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.location.LocationManagerCompat

/**
 * Qué le falta por configurar a ESTE móvil para que la app funcione del todo. El resultado
 * viaja al servidor en cada ping y en /auth/device (igual que la frecuencia), y el resto del
 * grupo lo ve en la ficha de ese miembro — así se detecta desde fuera al típico usuario que
 * denegó un permiso y luego dice que "no le llegan las notificaciones" o que "no le ven".
 *
 * Formato: códigos separados por comas. Cadena VACÍA = todo correcto (distinto de null, que
 * significa "su app es antigua y no lo manda"). Los códigos son estables y se traducen en el
 * móvil de quien mira (strings.xml), no aquí: así cada uno lo lee en su idioma.
 *
 * ponytail: no se comprueba el autoarranque de Xiaomi/Samsung/Huawei — no hay API para saber
 * si está concedido, así que saldría siempre como "falta" en esos móviles (ruido permanente).
 * Eso se sigue cubriendo en el onboarding.
 */
object ConfigCheck {

    const val LOCATION = "location"
    const val BACKGROUND_LOCATION = "bg_location"
    const val NOTIFICATIONS = "notifications"
    const val NOTIFICATION_CHANNEL = "notif_channel"
    const val BATTERY = "battery"
    const val GPS_OFF = "gps_off"

    /** Orden fijo (de más grave a menos) para que la lista se lea igual siempre. */
    val ALL = listOf(LOCATION, BACKGROUND_LOCATION, NOTIFICATIONS, NOTIFICATION_CHANNEL, BATTERY, GPS_OFF)

    fun issues(context: Context): List<String> = buildList {
        if (!PermissionUtils.hasForegroundLocationPermission(context)) add(LOCATION)
        if (!PermissionUtils.hasBackgroundLocationPermission(context)) add(BACKGROUND_LOCATION)
        // areNotificationsEnabled cubre tanto el permiso denegado en Android 13+ como el
        // interruptor general de la app apagado desde los ajustes del sistema en cualquier
        // versión — sin él, notify() no hace nada y el push se pierde en silencio.
        if (!PermissionUtils.hasNotificationPermission(context) ||
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            add(NOTIFICATIONS)
        } else if (hasBlockedChannel(context)) {
            // Solo si las notificaciones están permitidas en general: si no, ya lo dice la línea
            // de arriba y repetirlo no aporta nada.
            add(NOTIFICATION_CHANNEL)
        }
        if (!PermissionUtils.isIgnoringBatteryOptimizations(context)) add(BATTERY)
        if (!isLocationEnabled(context)) add(GPS_OFF)
    }

    /** Cadena lista para mandar al servidor. */
    fun serialize(context: Context): String = issues(context).joinToString(",")

    /** Lo contrario: de la cadena del servidor a códigos conocidos. Se descarta lo que no
     * reconozcamos, por si un móvil con una versión más nueva manda códigos que aún no existen aquí. */
    fun parse(raw: String?): List<String>? =
        raw?.split(",")?.map { it.trim() }?.filter { it in ALL }

    private fun hasBlockedChannel(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return false
        // Por prefijo y no por id exacto: el canal de zona lleva sufijo de versión, que cambia
        // con el sonido elegido (ver NotificationHelper.ensureZoneChannel).
        val watched = listOf(Constants.ZONE_CHANNEL_ID, Constants.RING_CHANNEL_ID, Constants.SYSTEM_CHANNEL_ID)
        return manager.notificationChannels.any { channel ->
            channel.importance == android.app.NotificationManager.IMPORTANCE_NONE &&
                watched.any { channel.id.startsWith(it) }
        }
    }

    /** El permiso concedido no sirve de nada si la ubicación del sistema está apagada del todo. */
    private fun isLocationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return LocationManagerCompat.isLocationEnabled(manager)
    }
}
