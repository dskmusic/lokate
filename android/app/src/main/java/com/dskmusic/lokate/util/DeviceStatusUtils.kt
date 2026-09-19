package com.dskmusic.lokate.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import com.dskmusic.lokate.data.repository.DeviceStatus

object DeviceStatusUtils {
    fun read(context: Context): DeviceStatus {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val isCharging = batteryManager?.isCharging

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = connectivityManager?.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val wifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

        // El SSID solo viene relleno con permiso de ubicación concedido (ya lo pedimos en el
        // onboarding); si no, Android devuelve "<unknown ssid>".
        val wifiSsid = if (wifiConnected == true) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        } else {
            null
        }

        return DeviceStatus(
            batteryLevel = level,
            isCharging = isCharging,
            wifiConnected = wifiConnected,
            wifiSsid = wifiSsid,
            configIssues = ConfigCheck.serialize(context),
        )
    }
}
