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
 * decidiendo quién está dentro de qué, y tampoco hay aviso doble — cuando llegue el ping
 * periódico de después, el estado en el servidor ya habrá cambiado y no lo verá como transición.
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val fix = event.triggeringLocation ?: return

        val app = context.applicationContext
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
