package com.dskmusic.lokate.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.util.LocationFrequency
import com.dskmusic.lokate.util.MapStyle
import com.dskmusic.lokate.util.ThemeMode
import com.dskmusic.lokate.util.VibrationPattern
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
        val SERVER_BASE_URL = stringPreferencesKey("server_base_url_override")
        val RING_SOUND_URI = stringPreferencesKey("ring_sound_uri")
        val VIBRATION_PATTERN = stringPreferencesKey("vibration_pattern")
        val MAP_ZOOM = intPreferencesKey("map_initial_zoom")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val LAST_HISTORY_USER_ID = stringPreferencesKey("last_history_user_id")
        val MAP_STYLE = stringPreferencesKey("map_style")
        val TEST_MODE_RECIPIENTS = stringPreferencesKey("test_mode_recipients")
        val MAP_CACHE_CLEARED_AT = longPreferencesKey("map_cache_cleared_at")
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
            ?: LocationFrequency.REAL_TIME
    }

    suspend fun setLocationFrequency(freq: LocationFrequency) {
        context.dataStore.edit { it[Keys.LOCATION_FREQUENCY] = freq.name }
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
