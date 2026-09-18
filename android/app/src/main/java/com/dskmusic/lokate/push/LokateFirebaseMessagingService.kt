package com.dskmusic.lokate.push

import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.util.DeviceStatusUtils
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LokateFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val locator = ServiceLocator.getInstance(applicationContext)
        if (!locator.authRepository.isLoggedIn()) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { locator.authRepository.registerDevice(token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val locator = ServiceLocator.getInstance(applicationContext)
        val title = message.notification?.title ?: message.data["title"] ?: return
        val body = message.notification?.body ?: message.data["body"] ?: ""
        val type = message.data["type"]

        val notifyZone = runBlocking { locator.settings.notifyZoneEnabled.first() }
        val notifySystem = runBlocking { locator.settings.notifySystemEnabled.first() }

        when (type) {
            // Zona: notificación normal, respeta el silencio/no molestar del teléfono. El
            // sonido/vibración son los que el usuario elija en Ajustes ("Notificaciones").
            "zone_transition" -> if (notifyZone) {
                val soundUri = runBlocking { locator.settings.ringSoundUri.first() }
                val pattern = runBlocking { locator.settings.vibrationPattern.first() }
                NotificationHelper.playRingAlarm(this, soundUri, pattern, forcePriority = false)
                NotificationHelper.showZoneNotification(this, title, body, Constants.LOCATION_SERVICE_NOTIFICATION_ID + 1)
            }
            // "Hacer sonar" y mensajes de emergencia: SIEMPRE con el sonido de alarma del
            // sistema y vibración fuerte, ignorando silencio/no molestar — no son
            // personalizables ni dependen de ningún ajuste, a propósito (son avisos de
            // seguridad explícitos de otro miembro).
            "ring" -> {
                NotificationHelper.playRingAlarm(this, null, com.dskmusic.lokate.util.VibrationPattern.STRONG, forcePriority = true)
                NotificationHelper.showRingNotification(this, title, body)
            }
            // Ubicación puntual pedida desde otro dispositivo (icono "actualizar" en detalle de
            // miembro): totalmente silencioso, sin sonido ni notificación — solo se lee la
            // posición una vez y se sube igual que un ping normal en segundo plano.
            "request_location" -> CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val location = fetchOneShotLocation(this@LokateFirebaseMessagingService) ?: return@runCatching
                    val status = DeviceStatusUtils.read(applicationContext)
                    locator.locationRepository.ping(location.latitude, location.longitude, location.accuracy, status)
                }
            }
            "emergency_message" -> {
                NotificationHelper.playRingAlarm(this, null, com.dskmusic.lokate.util.VibrationPattern.STRONG, forcePriority = true)
                val attachmentUrl = message.data["attachment_url"].orEmpty().ifBlank { null }
                val attachmentKind = message.data["attachment_kind"].orEmpty().ifBlank { null }
                val absoluteAttachmentUrl = attachmentUrl?.let { com.dskmusic.lokate.data.remote.absoluteMediaUrl(it) }
                NotificationHelper.showEmergencyMessageNotification(this, title, body, absoluteAttachmentUrl, attachmentKind)
            }
            else -> if (notifySystem) {
                NotificationHelper.showSystemNotification(this, title, body, Constants.LOCATION_SERVICE_NOTIFICATION_ID + 2)
            }
        }
    }
}

private suspend fun fetchOneShotLocation(context: android.content.Context): android.location.Location? =
    suspendCancellableCoroutine { continuation ->
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cancellationSource = CancellationTokenSource()
        continuation.invokeOnCancellation { cancellationSource.cancel() }
        try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationSource.token)
                .addOnSuccessListener { location -> continuation.resume(location) }
                .addOnFailureListener { e -> continuation.resumeWithException(e) }
        } catch (e: SecurityException) {
            continuation.resume(null)
        }
    }
