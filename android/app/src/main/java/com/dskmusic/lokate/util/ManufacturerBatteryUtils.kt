package com.dskmusic.lokate.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Xiaomi/Samsung/Huawei matan procesos en segundo plano de forma agresiva pese a tener
 * "ignorar optimización de batería" concedido — cada uno tiene su propio panel adicional
 * de autoarranque/restricciones que hay que abrir a mano.
 */
object ManufacturerBatteryUtils {

    fun manufacturer(): String = Build.MANUFACTURER.lowercase()

    fun hasKnownAggressiveBatteryManagement(): Boolean {
        val m = manufacturer()
        return m.contains("xiaomi") || m.contains("samsung") || m.contains("huawei") || m.contains("honor")
    }

    fun manufacturerDisplayName(): String = Build.MANUFACTURER

    /** Devuelve el intent que abre el panel específico del fabricante, o null si no hay uno conocido. */
    fun autoStartSettingsIntent(context: Context): Intent? {
        val pkg = context.packageName
        val candidates = when {
            manufacturer().contains("xiaomi") -> listOf(
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    ),
                ),
            )
            manufacturer().contains("samsung") -> listOf(
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity",
                    ),
                ),
            )
            manufacturer().contains("huawei") || manufacturer().contains("honor") -> listOf(
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    ),
                ),
            )
            else -> emptyList()
        }

        for (intent in candidates) {
            if (intent.resolveActivity(context.packageManager) != null) return intent
        }
        // Si el panel específico no existe en esta build del fabricante, caemos a los ajustes de la app.
        return null.also { android.util.Log.d("ManufacturerBattery", "No autostart panel found for pkg=$pkg") }
    }

    fun tryOpenAutoStartSettings(context: Context): Boolean {
        val intent = autoStartSettingsIntent(context) ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
