package com.dskmusic.lokate.location

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import com.dskmusic.lokate.data.remote.SessionAlert
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.push.NotificationHelper
import com.dskmusic.lokate.R
import com.dskmusic.lokate.util.Constants
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Respaldo de WorkManager: si el foreground service ha muerto y START_STICKY no lo ha
 * revivido (fabricantes agresivos), este worker periódico vuelve a arrancarlo.
 * WorkManager mínimo real es 15 min, por eso no sustituye al servicio, solo lo relanza.
 */
class LocationUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val locator = ServiceLocator.getInstance(applicationContext)
        if (!locator.authRepository.isLoggedIn()) return Result.success()

        // Latido: re-registra token FCM, canal de notificaciones y estado del móvil. Es lo
        // único que corre en un móvil con el envío de ubicación desactivado, y lo que hace que
        // uno que acaba de actualizar la app registre su canal sin esperar a que alguien la abra.
        runCatching { locator.authRepository.registerCurrentDeviceToken() }

        // Copia de ajustes en la nube, como mucho una al día (lo decide el repositorio). Va
        // aquí y no en un worker propio: este ya se despierta cada 15 min con red.
        runCatching { locator.backupRepository.autoBackupIfDue() }

        // Lo que se quedó sin entregar (túnel, avión, servidor caído) sale aquí, aprovechando
        // que el worker ya tiene red garantizada y el móvil ya está despierto. La cola no
        // tiene alarma propia a propósito.
        runCatching { locator.locationRepository.flushPending() }

        runCatching { warnIfStalled(locator) }

        // Con el envío desactivado no hay nada que arrancar (el servicio se pararía solo al
        // leerlo). Y arrancar un foreground service desde aquí puede fallar en Android 12+ por
        // estar la app en segundo plano: que ese fallo no se lleve por delante el latido.
        if (locator.settings.locationFrequency.first().sendsPeriodicUpdates) {
            runCatching { LocationServiceController.ensureStarted(applicationContext) }
        }
        return Result.success()
    }

    /**
     * Vigilante: avisa al dueño del móvil si lleva demasiado sin conseguir mandar ubicación.
     * Es el caso que más pasa y del que nadie se entera — permiso revocado, el fabricante
     * mató el servicio, optimización de batería — y el síntoma es siempre el mismo: en el
     * grupo esa persona se queda congelada y en su móvil no pasa nada.
     */
    private suspend fun warnIfStalled(locator: ServiceLocator) {
        val frequency = locator.settings.locationFrequency.first()
        // Quien no manda nada por decisión propia no está averiado.
        if (!frequency.sendsPeriodicUpdates) return
        // Si la sesión ha caducado ya hay un aviso puesto, y es el que explica qué hacer.
        if (SessionAlert.expired) return

        val now = System.currentTimeMillis()
        val lastOk = locator.settings.lastPingOkAt.first()
        if (lastOk == 0L) {
            // Recién instalado o recién actualizado: no hay parón, hay falta de historia.
            locator.settings.setLastPingOkAt(now)
            return
        }
        // El margen sale del ritmo elegido, pero nunca baja de MIN_STALL_MS: un móvil quieto o
        // en el wifi de casa manda como mucho cada 15 min por diseño, y avisar antes de eso
        // sería llamar avería a lo que es ahorro.
        val limit = maxOf(frequency.intervalMs * 3, MIN_STALL_MS)
        if (now - lastOk < limit) return
        if (now - locator.settings.lastPingWarnAt.first() < WARN_EVERY_MS) return
        locator.settings.setLastPingWarnAt(now)
        NotificationHelper.showLocalAlert(
            applicationContext,
            Constants.PING_STALLED_NOTIFICATION_ID,
            applicationContext.getString(R.string.ping_stalled_title),
            applicationContext.getString(R.string.ping_stalled_body),
        )
    }

    companion object {
        /** Lo menos que tiene que llevar callado un móvil para dar por hecho que algo va mal. */
        private const val MIN_STALL_MS = 45 * 60_000L

        /** Cada cuánto se repite el aviso mientras siga sin mandar nada. */
        private const val WARN_EVERY_MS = 12 * 3600_000L

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<LocationUpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                Constants.LOCATION_WORKER_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
