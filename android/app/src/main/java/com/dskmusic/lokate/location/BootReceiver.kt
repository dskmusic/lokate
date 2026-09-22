package com.dskmusic.lokate.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dskmusic.lokate.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val locator = ServiceLocator.getInstance(context)
        if (!locator.authRepository.isLoggedIn()) return

        LocationServiceController.ensureStarted(context)
        LocationUpdateWorker.schedule(context)
        // Al reiniciar, el sistema olvida las geocercas de todas las apps: hay que volver a
        // dárselas o los avisos de zona rápidos dejarían de funcionar hasta el siguiente ciclo
        // del worker (15 min). Sin goAsync: no se espera al resultado.
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { ZoneGeofencing.refresh(app) }
        }
    }
}
