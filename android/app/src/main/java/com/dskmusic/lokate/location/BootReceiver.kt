package com.dskmusic.lokate.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dskmusic.lokate.di.ServiceLocator

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val locator = ServiceLocator.getInstance(context)
        if (!locator.authRepository.isLoggedIn()) return

        LocationServiceController.ensureStarted(context)
        LocationUpdateWorker.schedule(context)
    }
}
