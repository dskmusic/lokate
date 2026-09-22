package com.dskmusic.lokate.push

import com.dskmusic.lokate.R
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.location.LiveTracking
import com.dskmusic.lokate.location.LocationServiceController
import com.dskmusic.lokate.util.DeviceStatusUtils
import com.dskmusic.lokate.util.LocationFrequency
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

    /** Una ubicación puntual y para arriba, sin tocar el ritmo del servicio. La usan tanto la
     * petición puntual desde la ficha de miembro como el arranque del seguimiento en vivo.
     * Con el envío de ubicación deshabilitado no manda nada. */
    private suspend fun pingOneShot(locator: com.dskmusic.lokate.di.ServiceLocator) {
        val frequency = locator.settings.locationFrequency.first()
        if (frequency == LocationFrequency.DISABLED) return
        val location = fetchOneShotLocation(this) ?: return
        val status = DeviceStatusUtils.read(applicationContext)
        locator.locationRepository.ping(location.latitude, location.longitude, location.accuracy, status, frequency)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val locator = ServiceLocator.getInstance(applicationContext)
        val type = message.data["type"]
        // Antes, un push sin título se descartaba entero con un return silencioso. Ahora cae al
        // nombre de la app: perder el aviso es mucho peor que enseñarlo sin título propio.
        val title = message.notification?.title ?: message.data["title"] ?: getString(R.string.app_name)
        val body = message.notification?.body ?: message.data["body"] ?: ""
        // Rastro en logcat para poder diagnosticar los "no me llegó": si esta línea aparece, el
        // push SÍ llegó al dispositivo y el problema está de aquí hacia dentro (permiso de
        // notificaciones, canal bloqueado, un ajuste apagado); si no aparece, no llegó nunca.
        android.util.Log.i("LokatePush", "Push recibido: type=$type")

        val notifyZone = runBlocking { locator.settings.notifyZoneEnabled.first() }
        val notifySystem = runBlocking { locator.settings.notifySystemEnabled.first() }

        when (type) {
            // Zona: notificación normal, respeta el silencio/no molestar del teléfono. El
            // sonido/vibración son los que el usuario elija en Ajustes ("Notificaciones"), pero
            // los pone el CANAL, no este código: por aquí solo se pasa con la app en primer
            // plano — fuera de ella este push lleva bloque "notification" y lo pinta el sistema
            // sin arrancar el proceso (ver push.py). Llamar aquí a playRingAlarm sonaría dos veces.
            "zone_transition" -> if (notifyZone) {
                val channelId = runBlocking {
                    NotificationHelper.currentZoneChannelId(this@LokateFirebaseMessagingService, locator.settings)
                }
                NotificationHelper.showZoneNotification(this, channelId, title, body, message.data["user_id"])
            }
            // "Hacer sonar" y mensajes de emergencia: SIEMPRE con el sonido de alarma del
            // sistema y vibración fuerte, ignorando silencio/no molestar — no son
            // personalizables ni dependen de ningún ajuste, a propósito (son avisos de
            // seguridad explícitos de otro miembro).
            "ring" -> {
                NotificationHelper.playRingAlarm(this, null, com.dskmusic.lokate.util.VibrationPattern.STRONG, forcePriority = true)
                NotificationHelper.showRingNotification(this, title, body)
            }
            // Parada remota: quien hizo sonar pulsa "Detener" en su móvil. Silencioso, mismo
            // efecto que la acción "Detener" de la propia notificación.
            "stop_ring" -> NotificationHelper.dismissRing(this)
            // Ubicación puntual pedida desde otro dispositivo (icono "actualizar" en detalle de
            // miembro): totalmente silencioso, sin sonido ni notificación — solo se lee la
            // posición una vez y se sube igual que un ping normal en segundo plano.
            "request_location" -> CoroutineScope(Dispatchers.IO).launch {
                // Con el envío desactivado en Ajustes no se responde ni a las peticiones
                // puntuales: quien la pidió ve "desactivado" en la ficha del miembro.
                runCatching { pingOneShot(locator) }
            }
            // Alguien nos ha puesto en seguimiento en vivo desde su mapa: silencioso, va
            // consentido con la entrada al grupo. Solo la PRIMERA activación llega por push;
            // las renovaciones y el fin viajan en la respuesta de cada ping.
            "live_tracking" -> CoroutineScope(Dispatchers.IO).launch {
                if (locator.settings.locationFrequency.first() == LocationFrequency.DISABLED) return@launch
                LiveTracking.update(message.data["seconds"]?.toIntOrNull() ?: 0)
                // En "solo bajo demanda" no hay servicio corriendo y hay que levantarlo: el
                // push de alta prioridad da permiso para arrancarlo desde segundo plano. Si
                // aun así falla (Android 12+ puede negarlo), hay que ENTERARSE: en silencio,
                // el seguimiento en vivo no arrancaba y no había forma de saber por qué.
                runCatching { LocationServiceController.ensureStarted(applicationContext) }
                    .onFailure { android.util.Log.e("LokatePush", "No se pudo arrancar el servicio para el seguimiento", it) }
                // Y una posición ya, sin esperar al primer fix del servicio: es lo que hace que
                // quien acaba de pulsar "seguir" vea el primer tic en un par de segundos.
                runCatching { pingOneShot(locator) }
                    .onFailure { android.util.Log.w("LokatePush", "Seguimiento: primer ping fallido", it) }
            }
            "emergency_message" -> {
                NotificationHelper.playRingAlarm(this, null, com.dskmusic.lokate.util.VibrationPattern.STRONG, forcePriority = true)
                val attachmentUrl = message.data["attachment_url"].orEmpty().ifBlank { null }
                val attachmentKind = message.data["attachment_kind"].orEmpty().ifBlank { null }
                val absoluteAttachmentUrl = attachmentUrl?.let { com.dskmusic.lokate.data.remote.absoluteMediaUrl(it) }
                NotificationHelper.showEmergencyMessageNotification(this, title, body, absoluteAttachmentUrl, attachmentKind)
            }
            else -> if (notifySystem) {
                NotificationHelper.showSystemNotification(this, title, body)
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
