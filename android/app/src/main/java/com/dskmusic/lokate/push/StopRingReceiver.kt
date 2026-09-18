package com.dskmusic.lokate.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.dskmusic.lokate.util.Constants

/** Para el sonido de "hacer sonar el dispositivo" al tocar la notificación, su botón "Detener", o al descartarla. */
class StopRingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationHelper.stopRingAlarm(context)
        NotificationManagerCompat.from(context).cancel(Constants.LOCATION_SERVICE_NOTIFICATION_ID + 3)
    }
}
