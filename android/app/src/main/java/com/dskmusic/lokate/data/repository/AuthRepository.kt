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
    /** Borra de este móvil lo que era del usuario anterior — ver ServiceLocator. */
    private val clearLocalUserData: suspend () -> Unit,
) {
    /**
     * Cuenta distinta a la que estaba = todo lo guardado aquí es de otra persona. Se borra ANTES
     * de guardar la sesión nueva: así ni se llega a pintar lo viejo ni los pings pendientes del
     * anterior salen con el token del que acaba de entrar. Misma cuenta = no se toca nada, que su
     * historial descargado le sigue sirviendo.
     */
    private suspend fun switchAccount(newUserId: String) {
        if (session.userId != newUserId) clearLocalUserData()
    }

    suspend fun register(username: String, password: String, displayName: String): UserDto =
        withContext(Dispatchers.IO) {
            val response = api.register(RegisterRequestDto(username, password, displayName))
            switchAccount(response.user.id)
            session.token = response.access_token
            session.userId = response.user.id
            response.user
        }

    suspend fun login(username: String, password: String): UserDto = withContext(Dispatchers.IO) {
        val response = api.login(LoginRequestDto(username, password))
        switchAccount(response.user.id)
        session.token = response.access_token
        session.userId = response.user.id
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
        // Solo si la llamada salió bien (si no, api.registerDevice ya habría lanzado): es lo que
        // mira [refreshDeviceRegistration] para saber si hace falta repetirla.
        settings.setDeviceRegistered(fcmToken, System.currentTimeMillis())
    }

    /**
     * Lee el token FCM actual y lo registra en el backend. Necesario justo después de
     * login/registro: Firebase suele generar el token la primera vez que arranca la app
     * (antes de tener cuenta), así que [LokateFirebaseMessagingService.onNewToken] — que solo
     * salta cuando el token CAMBIA — nunca llega a avisar al backend si no se hace esto aquí.
     */
    suspend fun registerCurrentDeviceToken() {
        val token = currentFcmToken()
        if (token != null) registerDevice(token)
    }

    /**
     * La versión para el latido del worker, que pasa cada 15 minutos: registra solo si hay algo
     * nuevo que contar. Antes era una llamada de red cada cuarto de hora, 96 al día, casi
     * siempre para repetir palabra por palabra lo que el servidor ya sabía.
     *
     * "Nada nuevo" son las cuatro cosas a la vez: el token es el mismo, este móvil manda
     * ubicación por su cuenta (y cada ping lleva ya batería, wifi y configuración), un ping ha
     * llegado hace poco de verdad, y no hace tanto del último registro. Lo último es el seguro:
     * si el servidor pierde el dispositivo, como mucho un día después se vuelve a presentar.
     */
    suspend fun refreshDeviceRegistration() {
        val token = currentFcmToken() ?: return
        val now = System.currentTimeMillis()
        val nothingNew = settings.locationFrequency.first().sendsPeriodicUpdates &&
            token == settings.lastRegisteredToken.first() &&
            now - settings.lastPingOkAt.first() < REGISTER_SKIP_AFTER_PING_MS &&
            now - settings.lastDeviceRegisterAt.first() < REGISTER_MAX_GAP_MS
        if (nothingNew) return
        registerDevice(token)
    }

    private suspend fun currentFcmToken(): String? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine<String?> { cont ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { cont.resume(it) {} }
                .addOnFailureListener { cont.resume(null) {} }
        }
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

    suspend fun logout(context: Context) {
        // Primero borrar y luego cerrar: si algo fallara a medias, es mejor quedarse dentro con
        // los datos limpios que fuera con los datos del anterior a la vista.
        clearLocalUserData()
        session.clear()
    }
}

/** Un ping reciente ya le ha contado al servidor todo lo que lleva el registro: con el intervalo
 * de reposo en 15 min, 20 da margen a que uno se pierda sin dar el estado por viejo. */
private const val REGISTER_SKIP_AFTER_PING_MS = 20 * 60_000L

/** Y aun sin novedades, presentarse una vez al día. */
private const val REGISTER_MAX_GAP_MS = 24 * 60 * 60_000L
