package com.dskmusic.lokate.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import com.dskmusic.lokate.data.repository.DeviceStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach

object DeviceStatusUtils {
    /** Último SSID visto por [wifiSsidFlow], para que [currentWifiSsid] pueda contestar sin
     * montar un callback y esperarlo. */
    @Volatile
    private var lastSsid: String? = null

    fun read(context: Context): DeviceStatus {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val isCharging = batteryManager?.isCharging

        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }

        return DeviceStatus(
            batteryLevel = level,
            isCharging = isCharging,
            wifiConnected = active?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            wifiSsid = currentWifiSsid(context),
            configIssues = ConfigCheck.serialize(context),
        )
    }

    /** SSID del wifi actual, o null si no hay wifi o el sistema no suelta el nombre. */
    fun currentWifiSsid(context: Context): String? =
        lastSsid ?: ssidOf(context.applicationContext, wifiCapabilities(context))

    /**
     * SSID del wifi actual (null mientras no haya wifi), por la vía del callback de red: a
     * partir de Android 12 el nombre de la red es información sensible a ubicación y solo llega
     * sin tachar a quien registra un callback teniendo ubicación fina concedida y la ubicación
     * del sistema encendida. Preguntarlo a bote pronto devuelve "<unknown ssid>".
     *
     * El callback se pide filtrado por wifi en vez de sobre la red por defecto: un wifi sin
     * internet, o con una VPN por medio, no es la red por defecto y aun así es donde estamos.
     */
    fun wifiSsidFlow(context: Context): Flow<String?> = callbackFlow {
        val app = context.applicationContext
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(ssidOf(app, capabilities))
            }

            override fun onLost(network: Network) {
                trySend(null)
            }
        }
        // Primer valor sin esperar al callback: en Android 11 y anteriores ya sale de aquí.
        trySend(ssidOf(app, wifiCapabilities(app)))
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        manager.registerNetworkCallback(request, callback)
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged().onEach { lastSsid = it }

    /** Capacidades del wifi conectado, sea o no la red por defecto. */
    @Suppress("DEPRECATION")
    private fun wifiCapabilities(context: Context): NetworkCapabilities? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return manager.allNetworks.firstNotNullOfOrNull { network ->
            manager.getNetworkCapabilities(network)?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        }
    }

    @Suppress("DEPRECATION")
    private fun ssidOf(context: Context, capabilities: NetworkCapabilities?): String? {
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return null
        val fromCapabilities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            capabilities.transportInfo as? WifiInfo
        } else {
            null
        }
        // transportInfo no lleva el WifiInfo hasta Android 12 (y antes de la 10 ni existe): en
        // esas versiones el método viejo, ya obsoleto, es el que da el nombre.
        return fromCapabilities?.cleanSsid()
            ?: (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.cleanSsid()
    }

    private fun WifiInfo.cleanSsid(): String? =
        ssid?.trim('"')?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }

    private const val UNKNOWN_SSID = "<unknown ssid>"
}
