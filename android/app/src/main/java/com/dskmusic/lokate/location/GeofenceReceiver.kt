package com.dskmusic.lokate.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.util.DeviceStatusUtils
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * El sistema nos despierta aquí al cruzar el borde de una zona, aunque la app esté cerrada.
 *
 * Lo único que hace es mandar un ping YA con la posición del cruce: el aviso al grupo lo sigue
 * decidiendo el servidor con ese ping, igual que con cualquier otro. Así no hay dos sitios
 * decidiendo quién está dentro de qué, y el ping periódico de después ya no ve transición ninguna
 * porque el estado en el servidor habrá cambiado.
 *
 * Lo que eso NO cubre es que los dos lleguen a la vez: el fix que el sistema trae aquí es el mismo
 * que el fused provider le acaba de dar al servicio en primer plano, así que los dos pings salen en
 * el mismo instante y ninguno ve el cambio del otro (el 05/10/2026, dos avisos de la misma salida
 * con 1 ms de diferencia). De eso se protege el servidor, que es el único sitio donde se ven las
 * dos peticiones: ver geofence.check_zone_transitions. Aquí no hay nada que arreglar — este ping es
 * el camino rápido de los avisos y tiene que salir siempre.
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val fix = event.triggeringLocation ?: return

        val app = context.applicationContext
        BatteryStats.onGeofenceEvent(app)
        // goAsync: un receptor muere en cuanto vuelve de onReceive, y aquí hay que hablar por
        // red. Si no llega a salir, LocationRepository lo deja en la cola y sale luego.
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val locator = ServiceLocator.getInstance(app)
                if (!locator.authRepository.isLoggedIn()) return@launch
                val frequency = locator.settings.locationFrequency.first()
                // Quien apagó el envío de ubicación no manda nada, tampoco por esto: es
                // exactamente lo que dice ese ajuste (y por lo que ya avisa de que los avisos de
                // zona dejan de funcionar).
                if (!frequency.sendsPeriodicUpdates) return@launch
                locator.locationRepository.ping(
                    fix.latitude,
                    fix.longitude,
                    fix.accuracy,
                    DeviceStatusUtils.read(app),
                    frequency,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
