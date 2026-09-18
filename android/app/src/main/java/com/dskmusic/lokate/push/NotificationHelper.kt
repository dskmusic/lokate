package com.dskmusic.lokate.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.dskmusic.lokate.R
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.util.VibrationPattern
import kotlinx.coroutines.runBlocking

const val RING_NOTIFICATION_ID = Constants.LOCATION_SERVICE_NOTIFICATION_ID + 3

object NotificationHelper {

    @Volatile
    private var activeRingtone: Ringtone? = null

    // Un único Handler compartido (no uno nuevo por llamada) para poder cancelar de verdad
    // cualquier continuación de ciclo pendiente de una prueba anterior — si no, probar un
    // patrón y cambiar a otro antes de que termine el primero podía dejar "resucitar" el
    // viejo por encima del nuevo, dando la sensación de que nunca paraba.
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Suena y vibra con el patrón elegido.
     * [soundUri] null = nunca se ha tocado el ajuste → sonido de notificación por defecto del
     * sistema (o de alarma si [forcePriority], para "hacer sonar"/mensajes de emergencia, que
     * siempre pasan null aquí a propósito). [Constants.RING_SOUND_ALARM_DEFAULT] = el usuario
     * eligió explícitamente "Alarma" como sonido para las notificaciones normales.
     * [forcePriority] true (por defecto): usa el canal de audio de ALARMA, que Android trata como
     * las alarmas del despertador — suena y vibra aunque el móvil esté en silencio, vibración o
     * ahorro de batería. Si el usuario lo desactiva en Ajustes, se usa el canal normal, que sí
     * respeta el modo silencio del teléfono.
     *
     * Con [forcePriority] ("hacer sonar" y mensajes de emergencia) el sonido y la vibración se
     * repiten EN BUCLE sin parar solos — es a propósito, son avisos que deben seguir hasta que
     * el destinatario los atienda; solo los para tocar la notificación, la acción "Detener", o
     * descartarla. Sin [forcePriority] (notificaciones de zona, personalizables), el patrón se
     * repite [VibrationPattern.cycles] veces y se para solo.
     */
    fun playRingAlarm(context: Context, soundUri: String?, pattern: VibrationPattern, forcePriority: Boolean = true) {
        stopRingAlarm(context)

        val usage = if (forcePriority) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val uri = when {
            soundUri == Constants.RING_SOUND_ALARM_DEFAULT -> RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            soundUri != null -> Uri.parse(soundUri)
            forcePriority -> RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val ringtone = RingtoneManager.getRingtone(context, uri)
        if (ringtone != null) {
            ringtone.audioAttributes = audioAttributes
            if (forcePriority && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.isLooping = true
            }
            activeRingtone = ringtone
            ringtone.play()
        }

        if (forcePriority) {
            systemVibrator(context)?.vibrate(VibrationEffect.createWaveform(pattern.timings, 0), audioAttributes)
        } else {
            playVibrationCycle(context, pattern, audioAttributes, pattern.cycles)
        }
    }

    /** Reproduce UN ciclo del patrón (sin bucle) y, a los milisegundos exactos que debería
     * durar, lo cancela él mismo antes de decidir si arranca el siguiente ciclo o para del
     * todo — no delega en que el propio sistema respete correctamente ni el "sin bucle" (-1)
     * ni una duración calculada de antemano para un array largo con varios ciclos ya pegados:
     * en algunos fabricantes (mismo motivo que obliga a llamar dos APIs distintas de cancelar
     * en [cancelVibration]) eso no se puede dar por hecho. Ciclo a ciclo, cancelando de verdad
     * entre medias, es la única forma de estar seguros de que para donde toca. */
    private fun playVibrationCycle(context: Context, pattern: VibrationPattern, audioAttributes: AudioAttributes, cyclesLeft: Int) {
        if (cyclesLeft <= 0) return
        systemVibrator(context)?.vibrate(VibrationEffect.createWaveform(pattern.timings, -1), audioAttributes)
        handler.postDelayed(
            {
                cancelVibration(context)
                if (cyclesLeft > 1) playVibrationCycle(context, pattern, audioAttributes, cyclesLeft - 1)
            },
            pattern.timings.sum(),
        )
    }

    fun stopRingAlarm(context: Context) {
        handler.removeCallbacksAndMessages(null)
        activeRingtone?.let { if (it.isPlaying) it.stop() }
        activeRingtone = null
        cancelVibration(context)
    }

    /** Llama a las DOS APIs de cancelar vibración, no solo la "correcta" según la versión de
     * Android: en algunos fabricantes, para un patrón largo ya en marcha, solo una de las dos
     * corta de verdad — la otra deja que siga hasta el final del patrón. Llamar a ambas es
     * inofensivo (cancelar dos veces no hace nada raro) y cubre ese caso. */
    private fun cancelVibration(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.cancel()
        }
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
    }

    private fun systemVibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                Constants.LOCATION_CHANNEL_ID,
                context.getString(R.string.notification_channel_location_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notification_channel_location_desc) },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                Constants.ZONE_CHANNEL_ID,
                context.getString(R.string.notification_channel_zone_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.notification_channel_zone_desc) },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                Constants.SYSTEM_CHANNEL_ID,
                context.getString(R.string.notification_channel_system_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notification_channel_system_desc) },
        )

        // Canal aparte para "hacer sonar el dispositivo": bypassDnd es lo que le pide al sistema
        // que la notificación en sí también intente saltarse el modo No Molestar (además del
        // sonido/vibración que ya forzamos a mano en playRingAlarm).
        manager.createNotificationChannel(
            NotificationChannel(
                Constants.RING_CHANNEL_ID,
                context.getString(R.string.notification_channel_ring_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_channel_ring_desc)
                setBypassDnd(true)
            },
        )
    }

    fun buildLocationServiceNotification(context: Context): android.app.Notification =
        NotificationCompat.Builder(context, Constants.LOCATION_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.location_service_notification_title))
            .setContentText(context.getString(R.string.location_service_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    fun showZoneNotification(context: Context, title: String, body: String, notificationId: Int) {
        val notification = NotificationCompat.Builder(context, Constants.ZONE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    fun showSystemNotification(context: Context, title: String, body: String, notificationId: Int) {
        val notification = NotificationCompat.Builder(context, Constants.SYSTEM_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    /** A diferencia de las demás: toca la notificación, su botón "Detener", o descártala — las tres paran el sonido. */
    fun showRingNotification(context: Context, title: String, body: String) {
        val stopIntent = Intent(context, StopRingReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopPendingIntent = PendingIntent.getBroadcast(context, RING_NOTIFICATION_ID, stopIntent, flags)

        val notification = NotificationCompat.Builder(context, Constants.RING_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(stopPendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .addAction(0, context.getString(R.string.ring_stop_action), stopPendingIntent)
            .build()
        NotificationManagerCompat.from(context).notify(RING_NOTIFICATION_ID, notification)
    }

    /** Mensaje de emergencia: misma notificación forzada que "hacer sonar", con la imagen
     * adjunta a tamaño grande si la hay. Al tocarla se abre el visor completo (MainActivity
     * se encarga de parar el sonido/vibración y navegar), swipe/"Detener" solo paran. */
    fun showEmergencyMessageNotification(
        context: Context,
        senderName: String,
        text: String,
        attachmentUrl: String?,
        attachmentKind: String?,
    ) {
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val viewIntent = Intent(context, com.dskmusic.lokate.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(Constants.EXTRA_EMERGENCY_SENDER, senderName)
            putExtra(Constants.EXTRA_EMERGENCY_TEXT, text)
            putExtra(Constants.EXTRA_EMERGENCY_ATTACHMENT_URL, attachmentUrl)
            putExtra(Constants.EXTRA_EMERGENCY_ATTACHMENT_KIND, attachmentKind)
        }
        val contentPendingIntent = PendingIntent.getActivity(context, RING_NOTIFICATION_ID, viewIntent, pendingIntentFlags)

        val stopIntent = Intent(context, StopRingReceiver::class.java)
        val stopPendingIntent = PendingIntent.getBroadcast(context, RING_NOTIFICATION_ID, stopIntent, pendingIntentFlags)

        val image = if (attachmentKind == "image" && attachmentUrl != null) fetchBitmap(context, attachmentUrl) else null

        val builder = NotificationCompat.Builder(context, Constants.RING_CHANNEL_ID)
            .setContentTitle(senderName)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .addAction(0, context.getString(R.string.ring_stop_action), stopPendingIntent)

        if (image != null) {
            builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(image).setSummaryText(text))
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }

        NotificationManagerCompat.from(context).notify(RING_NOTIFICATION_ID, builder.build())
    }

    private fun fetchBitmap(context: Context, url: String): Bitmap? {
        val loader = ImageLoader.Builder(context).build()
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        val result = runCatching { runBlocking { loader.execute(request) } }.getOrNull()
        return ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
    }
}
