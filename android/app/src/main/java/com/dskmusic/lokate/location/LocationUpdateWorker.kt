package com.dskmusic.lokate.location

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import com.dskmusic.lokate.di.ServiceLocator
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

        // Con el envío desactivado no hay nada que arrancar (el servicio se pararía solo al
        // leerlo). Y arrancar un foreground service desde aquí puede fallar en Android 12+ por
        // estar la app en segundo plano: que ese fallo no se lleve por delante el latido.
        if (locator.settings.locationFrequency.first().sendsPeriodicUpdates) {
            runCatching { LocationServiceController.ensureStarted(applicationContext) }
        }
        return Result.success()
    }

    companion object {
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
