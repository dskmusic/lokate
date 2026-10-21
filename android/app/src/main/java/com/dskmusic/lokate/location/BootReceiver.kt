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

        val app = context.applicationContext

        // onReceive corre en el hilo principal y BOOT_COMPLETED tiene un presupuesto de tiempo
        // estricto: montar aquí el ServiceLocator (primera lectura de DataStore) o la sesión
        // (clave en el Keystore + EncryptedSharedPreferences) puede reventar el tope en un
        // arranque en frío y acabar en el diálogo "Lokate no responde". goAsync() devuelve el
        // control enseguida y mantiene vivo el proceso hasta acabar el arranque del servicio.
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val locator = ServiceLocator.getInstance(app)
                if (locator.authRepository.isLoggedIn()) {
                    LocationServiceController.ensureStarted(app)
                    LocationUpdateWorker.schedule(app)
                }
            } finally {
                pendingResult.finish()
            }
        }

        // Al reiniciar, el sistema olvida las geocercas de todas las apps: hay que volver a
        // dárselas o los avisos de zona rápidos dejarían de funcionar hasta el siguiente ciclo
        // del worker (15 min). Sin goAsync: no se espera al resultado.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { ZoneGeofencing.refresh(app) }
        }
    }
}
