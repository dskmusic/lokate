package com.dskmusic.lokate.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.util.LocationFrequency
import com.dskmusic.lokate.util.MapStyle
import com.dskmusic.lokate.util.ThemeMode
import com.dskmusic.lokate.util.VibrationPattern
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = com.dskmusic.lokate.util.Constants.PREFS_NAME)

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT_COLOR = intPreferencesKey("accent_color_argb")
        val LOCATION_FREQUENCY = stringPreferencesKey("location_frequency")
        val NOTIFY_ZONE = booleanPreferencesKey("notify_zone")
        val NOTIFY_SYSTEM = booleanPreferencesKey("notify_system")
        val HISTORY_HOURS = intPreferencesKey("history_hours")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val ONBOARDING_ASKED = stringPreferencesKey("onboarding_asked_issues")
        val SERVER_BASE_URL = stringPreferencesKey("server_base_url_override")
        val RING_SOUND_URI = stringPreferencesKey("ring_sound_uri")
        val VIBRATION_PATTERN = stringPreferencesKey("vibration_pattern")
        val MAP_ZOOM = intPreferencesKey("map_initial_zoom")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val LAST_HISTORY_USER_ID = stringPreferencesKey("last_history_user_id")
        val MAP_STYLE = stringPreferencesKey("map_style")
        val TEST_MODE_RECIPIENTS = stringPreferencesKey("test_mode_recipients")
        val MAP_CACHE_CLEARED_AT = longPreferencesKey("map_cache_cleared_at")
        val KNOWN_WIFI_SSIDS = stringSetPreferencesKey("known_wifi_ssids")
        val BACKUP_LAST_AT = longPreferencesKey("backup_last_at")
        val LAST_PING_OK_AT = longPreferencesKey("last_ping_ok_at")
        val LAST_PING_WARN_AT = longPreferencesKey("last_ping_warn_at")
    }

    /** Ajustes que NO viajan en la copia, uno por uno y por un motivo:
     *  - la URL del servidor: la copia se baja DEL servidor al que apuntas, restaurar otra te
     *    dejaría hablando con quien no es;
     *  - el onboarding: en un móvil nuevo los permisos hay que volver a pedirlos, y darlo por
     *    hecho deja la app sin ubicación y sin explicación;
     *  - lo demás son marcas de este móvil (cuándo se limpió la caché de mapas, a quién miraste
     *    la última vez, cuándo se hizo la última copia), que no significan nada en otro. */
    private val notBackedUp = setOf(
        Keys.SERVER_BASE_URL.name,
        Keys.ONBOARDING_DONE.name,
        Keys.ONBOARDING_ASKED.name,
        Keys.MAP_CACHE_CLEARED_AT.name,
        Keys.LAST_HISTORY_USER_ID.name,
        Keys.BACKUP_LAST_AT.name,
        Keys.LAST_PING_OK_AT.name,
        Keys.LAST_PING_WARN_AT.name,
    )

    /** Cuándo se entregó el último ping (epoch ms), 0 = nunca. Es el latido que vigila
     * [com.dskmusic.lokate.location.LocationUpdateWorker] para avisar al dueño del móvil si
     * esto lleva parado demasiado. Se escribe como mucho una vez por minuto (lo limita
     * [com.dskmusic.lokate.data.repository.LocationRepository]): en tiempo real hay un ping
     * cada 3 s y escribir en disco veinte veces por minuto para esto no tiene sentido. */
    val lastPingOkAt: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_PING_OK_AT] ?: 0L }
    suspend fun setLastPingOkAt(epochMs: Long) {
        context.dataStore.edit { it[Keys.LAST_PING_OK_AT] = epochMs }
    }

    /** Cuándo se avisó por última vez de ese parón, para no repetir el aviso cada 15 min. */
    val lastPingWarnAt: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_PING_WARN_AT] ?: 0L }
    suspend fun setLastPingWarnAt(epochMs: Long) {
        context.dataStore.edit { it[Keys.LAST_PING_WARN_AT] = epochMs }
    }

    /** Cuándo se subió la última copia automática (epoch ms), 0 = nunca. */
    val backupLastAt: Flow<Long> = context.dataStore.data.map { it[Keys.BACKUP_LAST_AT] ?: 0L }
    suspend fun setBackupLastAt(epochMs: Long) {
        context.dataStore.edit { it[Keys.BACKUP_LAST_AT] = epochMs }
    }

    /**
     * Todos los ajustes de este móvil en un JSON `{"clave": {"t": tipo, "v": valor}}`.
     *
     * Se recorre el DataStore entero en vez de enumerar los ajustes uno a uno: así un ajuste
     * nuevo entra en la copia el día que se añade, sin que nadie se acuerde de tocar esto.
     * El tipo se guarda porque al restaurar hay que volver a crear la clave tipada.
     */
    suspend fun exportJson(): String {
        val json = JSONObject()
        context.dataStore.data.first().asMap().forEach { (key, value) ->
            if (key.name in notBackedUp) return@forEach
            val type = when (value) {
                is String -> "s"
                is Boolean -> "b"
                is Int -> "i"
                is Long -> "l"
                is Float -> "f"
                is Double -> "d"
                is Set<*> -> "ss"
                else -> return@forEach
            }
            val stored = if (value is Set<*>) JSONArray(value.map { it.toString() }) else value
            json.put(key.name, JSONObject().put("t", type).put("v", stored))
        }
        return json.toString()
    }

    /**
     * Vuelca una copia de [exportJson] encima de los ajustes actuales. Solo pisa las claves que
     * vengan en la copia: lo que no esté se queda como está (una copia vieja no debería borrar
     * un ajuste que entonces no existía). Un valor con un tipo que no cuadra se salta: más vale
     * perder un ajuste que dejar la app sin restaurar nada.
     */
    suspend fun importJson(payload: String) {
        val parsed = JSONObject(payload)
        context.dataStore.edit { prefs ->
            parsed.keys().forEach { name ->
                if (name in notBackedUp) return@forEach
                val entry = parsed.optJSONObject(name) ?: return@forEach
                runCatching {
                    when (entry.getString("t")) {
                        "s" -> prefs[stringPreferencesKey(name)] = entry.getString("v")
                        "b" -> prefs[booleanPreferencesKey(name)] = entry.getBoolean("v")
                        "i" -> prefs[intPreferencesKey(name)] = entry.getInt("v")
                        "l" -> prefs[longPreferencesKey(name)] = entry.getLong("v")
                        "f" -> prefs[floatPreferencesKey(name)] = entry.getDouble("v").toFloat()
                        "d" -> prefs[doublePreferencesKey(name)] = entry.getDouble("v")
                        "ss" -> {
                            val array = entry.getJSONArray("v")
                            prefs[stringSetPreferencesKey(name)] = (0 until array.length()).map { array.getString(it) }.toSet()
                        }
                    }
                }
            }
        }
    }

    /** Cuándo se vació la caché de teselas del mapa por última vez (epoch ms) — la usa
     * LokateApplication para saber si toca refrescarla sola al arrancar (ver
     * Constants.MAP_CACHE_MAX_AGE_DAYS). 0L = nunca (fuerza un primer vaciado). */
    val mapCacheClearedAt: Flow<Long> = context.dataStore.data.map { it[Keys.MAP_CACHE_CLEARED_AT] ?: 0L }
    suspend fun setMapCacheClearedAt(epochMs: Long) {
        context.dataStore.edit { it[Keys.MAP_CACHE_CLEARED_AT] = epochMs }
    }

    /** Estilo de tiles del mapa (estándar/satélite/oscuro), recordado entre sesiones. */
    val mapStyle: Flow<MapStyle> = context.dataStore.data.map { prefs ->
        prefs[Keys.MAP_STYLE]?.let { runCatching { MapStyle.valueOf(it) }.getOrNull() } ?: MapStyle.STANDARD
    }
    suspend fun setMapStyle(style: MapStyle) {
        context.dataStore.edit { it[Keys.MAP_STYLE] = style.name }
    }

    /** A quién le llegan los avisos disparados en el modo prueba de los administradores (ids
     * separados por comas). Se recuerda entre sesiones: montar la prueba cada vez es lo pesado,
     * y normalmente se repite sobre los mismos móviles. Vacío = nadie elegido todavía. */
    val testModeRecipients: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.TEST_MODE_RECIPIENTS].orEmpty().split(",").filter { it.isNotBlank() }.toSet()
    }
    suspend fun setTestModeRecipients(userIds: Set<String>) {
        context.dataStore.edit { it[Keys.TEST_MODE_RECIPIENTS] = userIds.joinToString(",") }
    }

    /** Último miembro consultado en Historial (se recuerda entre sesiones). null = el propio usuario. */
    val lastHistoryUserId: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_HISTORY_USER_ID] }
    suspend fun setLastHistoryUserId(userId: String?) {
        context.dataStore.edit { prefs ->
            if (userId == null) prefs.remove(Keys.LAST_HISTORY_USER_ID) else prefs[Keys.LAST_HISTORY_USER_ID] = userId
        }
    }

    /** Nivel de zoom inicial del mapa (osmdroid: 3 = mundo, 19 = calle). Por defecto
     * [Constants.MAP_DEFAULT_ZOOM]; se acota al tope actual del ajuste por si quedó guardado
     * un valor mayor de alguna versión anterior. */
    val mapInitialZoom: Flow<Int> =
        context.dataStore.data.map { (it[Keys.MAP_ZOOM] ?: Constants.MAP_DEFAULT_ZOOM).coerceAtMost(Constants.MAP_MAX_ZOOM) }
    suspend fun setMapInitialZoom(zoom: Int) {
        context.dataStore.edit { it[Keys.MAP_ZOOM] = zoom }
    }

    /** "es"/"en"/"auto" (sigue el idioma del sistema). Por defecto "auto". */
    val appLanguage: Flow<String> = context.dataStore.data.map { it[Keys.APP_LANGUAGE] ?: "auto" }
    suspend fun setAppLanguage(language: String) {
        context.dataStore.edit { it[Keys.APP_LANGUAGE] = language }
    }

    /** null = sonido de alarma por defecto del sistema. */
    val ringSoundUri: Flow<String?> = context.dataStore.data.map { it[Keys.RING_SOUND_URI] }
    suspend fun setRingSoundUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.RING_SOUND_URI) else prefs[Keys.RING_SOUND_URI] = uri
        }
    }

    val vibrationPattern: Flow<VibrationPattern> = context.dataStore.data.map { prefs ->
        prefs[Keys.VIBRATION_PATTERN]?.let { runCatching { VibrationPattern.valueOf(it) }.getOrNull() }
            ?: VibrationPattern.SOFT
    }
    suspend fun setVibrationPattern(pattern: VibrationPattern) {
        context.dataStore.edit { it[Keys.VIBRATION_PATTERN] = pattern.name }
    }

    /** null = usar la URL por defecto de compilación (BuildConfig.API_BASE_URL). */
    val serverBaseUrlOverride: Flow<String?> = context.dataStore.data.map { it[Keys.SERVER_BASE_URL] }

    suspend fun setServerBaseUrlOverride(url: String?) {
        context.dataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(Keys.SERVER_BASE_URL) else prefs[Keys.SERVER_BASE_URL] = url.trim()
        }
    }

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }
    suspend fun setOnboardingCompleted(done: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = done }
    }

    /** Códigos de [com.dskmusic.lokate.util.ConfigCheck] que el onboarding ya llegó a pedirle a
     * ESTE móvil, separados por comas. Lo que falte y no esté aquí vuelve a sacar el onboarding
     * al abrir la app — así, cuando una actualización añade un permiso nuevo, se pide solo sin
     * tener que borrar los datos de la app. */
    val onboardingAskedIssues: Flow<String> = context.dataStore.data.map { it[Keys.ONBOARDING_ASKED].orEmpty() }
    suspend fun setOnboardingAskedIssues(issues: String) {
        context.dataStore.edit { it[Keys.ONBOARDING_ASKED] = issues }
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    /** ARGB Int, por defecto un verde Lokate. */
    val accentColor: Flow<Int> = context.dataStore.data.map { it[Keys.ACCENT_COLOR] ?: DEFAULT_ACCENT }

    suspend fun setAccentColor(argb: Int) {
        context.dataStore.edit { it[Keys.ACCENT_COLOR] = argb }
    }

    val locationFrequency: Flow<LocationFrequency> = context.dataStore.data.map { prefs ->
        prefs[Keys.LOCATION_FREQUENCY]?.let { runCatching { LocationFrequency.valueOf(it) }.getOrNull() }
            ?: LocationFrequency.EVERY_1_MIN
    }

    suspend fun setLocationFrequency(freq: LocationFrequency) {
        context.dataStore.edit { it[Keys.LOCATION_FREQUENCY] = freq.name }
    }

    /** Wifis que el usuario marcó como sitio fijo (casa, trabajo). Estando conectado a uno de
     * ellos no hace falta preguntar dónde está cada minuto: ver LocationForegroundService. */
    val knownWifiSsids: Flow<Set<String>> = context.dataStore.data.map { it[Keys.KNOWN_WIFI_SSIDS] ?: emptySet() }

    suspend fun setKnownWifiSsids(ssids: Set<String>) {
        context.dataStore.edit { it[Keys.KNOWN_WIFI_SSIDS] = ssids }
    }

    val notifyZoneEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIFY_ZONE] ?: true }
    suspend fun setNotifyZoneEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFY_ZONE] = enabled }
    }

    val notifySystemEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.NOTIFY_SYSTEM] ?: true }
    suspend fun setNotifySystemEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFY_SYSTEM] = enabled }
    }

    val historyHours: Flow<Int> = context.dataStore.data.map { it[Keys.HISTORY_HOURS] ?: 24 }
    suspend fun setHistoryHours(hours: Int) {
        context.dataStore.edit { it[Keys.HISTORY_HOURS] = hours }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    companion object {
        const val DEFAULT_ACCENT = 0xFF4A6FE3.toInt() // azul índigo
    }
}
