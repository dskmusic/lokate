package com.dskmusic.lokate.push

import com.dskmusic.lokate.R
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.location.LiveTracking
import com.dskmusic.lokate.location.LocationServiceController
import com.dskmusic.lokate.util.DeviceStatusUtils
import com.google.android.gms.location.CurrentLocationRequest
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
     * Responde con cualquier frecuencia configurada, incluida "deshabilitado": lo que ese ajuste
     * apaga es mandar ubicación por iniciativa propia, no atender a quien la pide a la cara. */
    private suspend fun pingOneShot(locator: com.dskmusic.lokate.di.ServiceLocator) {
        val frequency = locator.settings.locationFrequency.first()
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
                runCatching { pingOneShot(locator) }
                    .onFailure { android.util.Log.w("LokatePush", "Petición puntual fallida", it) }
            }
            // Alguien nos ha puesto en seguimiento en vivo desde su mapa: silencioso, va
            // consentido con la entrada al grupo, y manda por encima de la frecuencia elegida
            // (también de "deshabilitado") hasta que deje de seguirnos o caduque. Normalmente
            // solo llega la PRIMERA activación; las renovaciones y el fin viajan en la respuesta
            // de cada ping, y el servidor repite el push si nos ve callados.
            "live_tracking" -> CoroutineScope(Dispatchers.IO).launch {
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
            // Un admin ha añadido una wifi a nuestras "wifis de casa" desde nuestra ficha. Esa
            // lista solo existe aquí, por eso viaja por push. Silencioso a propósito (sin
            // notificación ni sonido, como la petición de ubicación puntual): el push no lleva
            // bloque "notification", así que el sistema tampoco pinta nada con la app cerrada.
            "add_known_wifi" -> {
                val ssid = message.data["ssid"].orEmpty()
                if (ssid.isNotBlank()) CoroutineScope(Dispatchers.IO).launch {
                    val current = locator.settings.knownWifiSsids.first()
                    // Repetida: no se toca nada (es un Set, no duplicaría) y encima así no se
                    // sube una copia idéntica por cada intento.
                    if (ssid in current) return@launch
                    locator.settings.setKnownWifiSsids(current + ssid)
                    // Y se sube la copia en el acto: es de donde saca el admin la lista para
                    // saber qué wifis tiene ya (ver admin_api.list_known_wifi). Sin esto, hasta
                    // la copia automática del día siguiente le seguiría pareciendo que falta.
                    runCatching { locator.backupRepository.backupNow() }
                        .onFailure { android.util.Log.w("LokatePush", "Copia tras añadir wifi fallida", it) }
                }
            }
            "emergency_message" -> {
                NotificationHelper.playRingAlarm(this, null, com.dskmusic.lokate.util.VibrationPattern.STRONG, forcePriority = true)
                val attachmentUrl = message.data["attachment_url"].orEmpty().ifBlank { null }
                val attachmentKind = message.data["attachment_kind"].orEmpty().ifBlank { null }
                val absoluteAttachmentUrl = attachmentUrl?.let { com.dskmusic.lokate.data.remote.absoluteMediaUrl(it) }
                NotificationHelper.showEmergencyMessageNotification(this, title, body, absoluteAttachmentUrl, attachmentKind)
            }
            // "Lleva X sin dar señal": es el único aviso del servidor que se puede silenciar por
            // persona (ajuste en su ficha) además de por app. El push llega igual y se descarta
            // aquí: el servidor no sabe —ni tiene por qué— quién quiere saber de quién.
            "member_silent" -> {
                val silentUserId = message.data["user_id"].orEmpty()
                val wanted = runBlocking { locator.settings.silentAlertUserIds.first() }
                val notifySilent = runBlocking { locator.settings.notifySilentEnabled.first() }
                if (notifySystem && notifySilent && silentUserId in wanted) {
                    NotificationHelper.showSilentNotification(this, silentUserId, title, body)
                }
            }
            // Ya vuelve a dar señal: se retira su aviso sin mirar ningún ajuste — borrar lo que
            // ya no es verdad no es notificar. Si no había aviso suyo, no hace nada.
            "member_silent_over" -> {
                NotificationHelper.cancelSilentNotification(this, message.data["user_id"].orEmpty())
            }
            else -> if (notifySystem) {
                NotificationHelper.showSystemNotification(this, title, body)
            }
        }
    }
}

/** Lo que se está dispuesto a esperar a un fix, y lo viejo que puede ser uno ya hecho para
 * darlo por bueno sin encender nada. */
private const val ONE_SHOT_TIMEOUT_MS = 20_000L
private const val ONE_SHOT_MAX_AGE_MS = 30_000L

private suspend fun fetchOneShotLocation(context: android.content.Context): android.location.Location? =
    suspendCancellableCoroutine { continuation ->
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cancellationSource = CancellationTokenSource()
        continuation.invokeOnCancellation { cancellationSource.cancel() }
        // Con tope y con edad máxima, que antes no llevaba ninguno de los dos: bajo techo y sin
        // ver el cielo el GPS se puede quedar buscando un buen rato sin llegar a nada, y aceptar
        // una posición de hace menos de medio minuto hace que pedir ubicación a todo el grupo dos
        // veces seguidas no vuelva a encender el GPS de nadie. Los 20 s son lo que espera quien
        // la pide (ver MemberDetailViewModel.LOCATION_REQUEST_TIMEOUT_MS): pasados, ya no mira.
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(ONE_SHOT_TIMEOUT_MS)
            .setMaxUpdateAgeMillis(ONE_SHOT_MAX_AGE_MS)
            .build()
        try {
            client.getCurrentLocation(request, cancellationSource.token)
                .addOnSuccessListener { location -> continuation.resume(location) }
                .addOnFailureListener { e -> continuation.resumeWithException(e) }
        } catch (e: SecurityException) {
            continuation.resume(null)
        }
    }
