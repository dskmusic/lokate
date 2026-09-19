package com.dskmusic.lokate.location

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.push.NotificationHelper
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.util.DeviceStatusUtils
import com.dskmusic.lokate.util.LocationFrequency
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * START_STICKY: si el sistema mata el proceso, Android intenta recrear el servicio.
 * LocationUpdateWorker es el respaldo por si ese reinicio automático no llega a producirse
 * (algunos fabricantes lo bloquean pese a START_STICKY).
 */
class LocationForegroundService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locator: ServiceLocator
    private val serviceScope = CoroutineScope(SupervisorJob())
    private var currentIntervalMs: Long = -1

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            serviceScope.launch {
                runCatching {
                    val status = DeviceStatusUtils.read(applicationContext)
                    val frequency = locator.settings.locationFrequency.first()
                    locator.locationRepository.ping(location.latitude, location.longitude, location.accuracy, status, frequency)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        locator = ServiceLocator.getInstance(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationHelper.buildLocationServiceNotification(this)
        ServiceCompat.startForeground(
            this,
            Constants.LOCATION_SERVICE_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        serviceScope.launch {
            val frequency = locator.settings.locationFrequency.first()
            // El guardia va aquí y no en LocationServiceController.ensureStarted porque a este
            // servicio lo arrancan cinco sitios distintos (MainActivity, el NavHost, el
            // onboarding, el BootReceiver y el worker de respaldo cada 15 min): comprobarlo en el
            // propio servicio los cubre todos. La notificación asoma unos milisegundos antes de
            // pararse — startForeground tiene que salir ya, antes de leer el DataStore.
            if (frequency == LocationFrequency.DISABLED) {
                stopSelf()
                return@launch
            }
            startLocationUpdates(frequency.intervalMs)
        }
        return START_STICKY
    }

    private fun startLocationUpdates(intervalMs: Long) {
        if (currentIntervalMs == intervalMs) return
        currentIntervalMs = intervalMs

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()

        fusedClient.removeLocationUpdates(locationCallback)
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        fusedClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
