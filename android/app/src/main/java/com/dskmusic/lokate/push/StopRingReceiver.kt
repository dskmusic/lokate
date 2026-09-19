package com.dskmusic.lokate.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dskmusic.lokate.util.Constants

/** Para el sonido de "hacer sonar el dispositivo" al tocar la notificación, su botón "Detener", o al descartarla. */
class StopRingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Sin extra (notificación de "hacer sonar"): el id fijo de siempre.
        val id = intent.getIntExtra(Constants.EXTRA_NOTIFICATION_ID, RING_NOTIFICATION_ID)
        NotificationHelper.dismissRing(context, id)
    }
}
