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
import com.dskmusic.lokate.util.DeviceStatusUtils
import com.dskmusic.lokate.util.PermissionUtils
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
        // Se salta sola cuando no hay nada nuevo que contar y los pings ya van al día.
        runCatching { locator.authRepository.refreshDeviceRegistration() }

        // Copia de ajustes en la nube, como mucho una al día (lo decide el repositorio). Va
        // aquí y no en un worker propio: este ya se despierta cada 15 min con red.
        runCatching { locator.backupRepository.autoBackupIfDue() }

        // Lo que se quedó sin entregar (túnel, avión, servidor caído) sale aquí, aprovechando
        // que el worker ya tiene red garantizada y el móvil ya está despierto. La cola no
        // tiene alarma propia a propósito.
        runCatching { locator.locationRepository.flushPending() }

        // Las geocercas del sistema, por si el fabricante las tiró al matar la app o han
        // cambiado desde otro móvil del grupo. Es idempotente y no cuesta red.
        runCatching { ZoneGeofencing.refresh(applicationContext) }

        runCatching { heartbeat(locator) }

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
     * Latido de posición: si hace un buen rato que no sale nada, manda la posición que el
     * sistema ya tiene calculada, aunque sea la misma de antes.
     *
     * Hace falta porque el filtro de distancia vive ahora dentro del sistema
     * (setMinUpdateDistanceMeters): un móvil parado deja de recibir fixes, así que el servicio
     * no tiene qué mandar y en el grupo esa persona se congelaría — indistinguible de un móvil
     * apagado, que es justo lo que hay que poder distinguir. Sale casi gratis: se reaprovecha
     * una posición ya hecha y, si no hay ninguna reciente, se pide sin encender el GPS.
     */
    private suspend fun heartbeat(locator: ServiceLocator) {
        val frequency = locator.settings.locationFrequency.first()
        if (!frequency.sendsPeriodicUpdates) return
        val lastOk = locator.settings.lastPingOkAt.first()
        if (lastOk != 0L && System.currentTimeMillis() - lastOk < HEARTBEAT_AFTER_MS) return
        if (!PermissionUtils.hasForegroundLocationPermission(applicationContext)) return

        // Vale cualquier posición que el sistema ya tenga de la última media hora, y eso es
        // lo normal: el servicio le sigue pidiendo una cada 15 min aunque luego no se la
        // entreguen por el filtro de distancia. Solo si no hay ninguna se calcula, y en
        // "equilibrado", que es wifi y antenas — nunca el GPS. Sin edad máxima se mandaría la
        // posición de hace horas con hora de ahora, que es peor que no mandar nada.
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(MAX_FIX_AGE_MS)
            .setDurationMillis(LAST_LOCATION_TIMEOUT_S * 1000)
            .build()
        val fix = withContext(Dispatchers.IO) {
            runCatching {
                Tasks.await(
                    LocationServices.getFusedLocationProviderClient(applicationContext)
                        .getCurrentLocation(request, CancellationTokenSource().token),
                    LAST_LOCATION_TIMEOUT_S + 5,
                    TimeUnit.SECONDS,
                )
            }.getOrNull()
        } ?: return

        locator.locationRepository.ping(
            fix.latitude,
            fix.longitude,
            fix.accuracy,
            DeviceStatusUtils.read(applicationContext),
            frequency,
        )
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
        /** Cuánto tiene que llevar sin salir un ping para que el worker mande el latido.
         *
         * Diez y no quince (el ritmo de reposo del servicio) porque el worker despierta cada 15
         * min por su cuenta: puesto en quince, una pasada que cae a los catorce minutos del
         * último ping se salta el latido y hay que esperar a la siguiente — media hora larga de
         * silencio con el móvil perfectamente. No cuesta batería: el latido sale como mucho una
         * vez por pasada, y la pasada ya se hace igual. */
        private const val HEARTBEAT_AFTER_MS = 10 * 60_000L

        /** Lo más vieja que puede ser una posición ya calculada para valer como latido. */
        private const val MAX_FIX_AGE_MS = 30 * 60_000L

        private const val LAST_LOCATION_TIMEOUT_S = 10L

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
