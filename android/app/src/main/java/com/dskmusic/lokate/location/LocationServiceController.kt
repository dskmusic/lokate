package com.dskmusic.lokate.location

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object LocationServiceController {
    fun ensureStarted(context: Context) {
        val intent = Intent(context, LocationForegroundService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, LocationForegroundService::class.java))
    }
}
