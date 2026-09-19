package com.dskmusic.lokate.data.repository

import android.content.Context
import com.dskmusic.lokate.data.prefs.SessionManager
import com.dskmusic.lokate.data.prefs.SettingsDataStore
import com.dskmusic.lokate.data.remote.ApiService
import com.dskmusic.lokate.data.remote.dto.LoginRequestDto
import com.dskmusic.lokate.data.remote.dto.RegisterDeviceRequestDto
import com.dskmusic.lokate.data.remote.dto.RegisterRequestDto
import com.dskmusic.lokate.data.remote.dto.UpdateFlagRequestDto
import com.dskmusic.lokate.data.remote.dto.UpdateProfileRequestDto
import com.dskmusic.lokate.data.remote.dto.UserDto
import com.dskmusic.lokate.push.NotificationHelper
import com.dskmusic.lokate.ui.map.MapCameraMemory
import com.dskmusic.lokate.util.DeviceStatusUtils
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class AuthRepository(
    private val api: ApiService,
    private val session: SessionManager,
    private val settings: SettingsDataStore,
    private val appContext: Context,
) {
    suspend fun register(username: String, password: String, displayName: String): UserDto =
        withContext(Dispatchers.IO) {
            val response = api.register(RegisterRequestDto(username, password, displayName))
            session.token = response.access_token
            session.userId = response.user.id
            // Sin esto, si antes hubo otra cuenta abierta en este mismo proceso de la app
            // (mismo dispositivo probando varios usuarios sin matarla del todo), el mapa
            // arrancaría en la posición/seguimiento del usuario anterior en vez de centrarse
            // en la ubicación real de quien acaba de entrar.
            MapCameraMemory.reset()
            response.user
        }

    suspend fun login(username: String, password: String): UserDto = withContext(Dispatchers.IO) {
        val response = api.login(LoginRequestDto(username, password))
        session.token = response.access_token
        session.userId = response.user.id
        MapCameraMemory.reset()
        response.user
    }

    suspend fun me(): UserDto = withContext(Dispatchers.IO) { api.me() }

    suspend fun updateDisplayName(displayName: String): UserDto = withContext(Dispatchers.IO) {
        api.updateProfile(UpdateProfileRequestDto(displayName))
    }

    suspend fun uploadAvatar(imageFile: File): String = withContext(Dispatchers.IO) {
        val body = imageFile.asRequestBody("image/jpeg".toMediaType())
        val part = MultipartBody.Part.createFormData("file", imageFile.name, body)
        api.uploadAvatar(part).avatar_url
    }

    /**
     * Registra el token FCM y, de paso, TODO el estado de este móvil: frecuencia, batería,
     * WiFi, lo que falte por configurar y el canal de notificaciones de zona. Hace también de
     * latido — con el envío de ubicación desactivado no hay pings, y esta es la única vía por
     * la que el grupo ve su batería y por la que el servidor se entera de a qué canal mandar
     * los avisos de zona (sin canal registrado, el push va "solo data" y no se ve hasta que el
     * móvil despierta). Lo llaman el arranque de la app, el login, un token nuevo, un cambio en
     * Ajustes y el worker cada 15 min.
     */
    suspend fun registerDevice(fcmToken: String) = withContext(Dispatchers.IO) {
        val status = DeviceStatusUtils.read(appContext)
        api.registerDevice(
            RegisterDeviceRequestDto(
                fcm_token = fcmToken,
                location_frequency = settings.locationFrequency.first().name,
                config_issues = status.configIssues.orEmpty(),
                zone_channel_id = NotificationHelper.currentZoneChannelId(appContext, settings),
                battery_level = status.batteryLevel,
                is_charging = status.isCharging,
                wifi_connected = status.wifiConnected,
                wifi_ssid = status.wifiSsid,
            ),
        )
    }

    /**
     * Lee el token FCM actual y lo registra en el backend. Necesario justo después de
     * login/registro: Firebase suele generar el token la primera vez que arranca la app
     * (antes de tener cuenta), así que [LokateFirebaseMessagingService.onNewToken] — que solo
     * salta cuando el token CAMBIA — nunca llega a avisar al backend si no se hace esto aquí.
     */
    suspend fun registerCurrentDeviceToken() {
        val token = withContext(Dispatchers.IO) {
            suspendCancellableCoroutine<String?> { cont ->
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { cont.resume(it) {} }
                    .addOnFailureListener { cont.resume(null) {} }
            }
        }
        if (token != null) registerDevice(token)
    }

    /** false también ante cualquier fallo de red — que no haya conexión no debe interpretarse
     * como "sí hay actualización" ni interrumpir nada. */
    suspend fun checkForUpdate(): Boolean = withContext(Dispatchers.IO) {
        runCatching { api.updateCheck().update_available }.getOrDefault(false)
    }

    /** Solo admins (lo exige el propio backend) — enciende o apaga el aviso de actualización
     * para todo el mundo sin tener que entrar por SSH a tocar el archivo a mano. */
    suspend fun setUpdateFlag(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        api.setUpdateFlag(UpdateFlagRequestDto(enabled)).update_available
    }

    fun isLoggedIn(): Boolean = session.isLoggedIn

    fun logout(context: Context) {
        session.clear()
        MapCameraMemory.reset()
    }
}
