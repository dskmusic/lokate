package com.dskmusic.lokate.util

object Constants {
    // Nominatim exige un User-Agent identificativo y respetar 1 req/seg.
    const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org/"
    const val NOMINATIM_USER_AGENT = "LokateByDSK/1.0 (contacto: victorm_cm@yahoo.es)"
    const val NOMINATIM_MIN_INTERVAL_MS = 1000L

    const val PREFS_NAME = "lokate_prefs"
    const val SECURE_PREFS_NAME = "lokate_secure_prefs"

    const val LOCATION_SERVICE_NOTIFICATION_ID = 1001
    const val LOCATION_CHANNEL_ID = "lokate_location"
    const val ZONE_CHANNEL_ID = "lokate_zone"
    const val RING_CHANNEL_ID = "lokate_ring"
    const val SYSTEM_CHANNEL_ID = "lokate_system"

    const val LOCATION_WORKER_TAG = "lokate_location_backup_worker"

    const val DEFAULT_HISTORY_HOURS = 24

    /** Cada cuántos días se vacía sola la caché de teselas del mapa al arrancar la app (ver
     * MapTileCache + LokateApplication) — un mapa de carreteras no cambia tan a menudo como
     * para necesitar comprobar frescura tesela a tesela; refrescar la caché entera de vez en
     * cuando es mucho más simple y ya evita que se quede desactualizada indefinidamente. */
    const val MAP_CACHE_MAX_AGE_DAYS = 14L

    /** Valor guardado en [com.dskmusic.lokate.data.prefs.SettingsDataStore.ringSoundUri] cuando el
     * usuario elige explícitamente "Alarma" — distinto de `null` (nunca tocado), que ahora usa el
     * sonido de notificación por defecto en vez de alarma. Sin este distintivo, elegir "Alarma" y
     * no tocar el ajuste serían indistinguibles (ambos se guardarían como ausencia de clave). */
    const val RING_SOUND_ALARM_DEFAULT = "system_alarm_default"

    // Extras del intent que abre el visor de mensaje de emergencia al tocar su notificación.
    const val EXTRA_EMERGENCY_SENDER = "extra_emergency_sender"
    const val EXTRA_EMERGENCY_TEXT = "extra_emergency_text"
    const val EXTRA_EMERGENCY_ATTACHMENT_URL = "extra_emergency_attachment_url"
    const val EXTRA_EMERGENCY_ATTACHMENT_KIND = "extra_emergency_attachment_kind"
}

enum class LocationFrequency(val intervalMs: Long) {
    /** Lo más parecido a "Localizar mi dispositivo" de Google/apps similares: pide ubicación al
     * GPS cada pocos segundos en vez de esperar a un sondeo periódico — el límite real de
     * "tiempo real" lo pone la propia frecuencia del chip GPS del teléfono, no este valor. */
    REAL_TIME(3_000L),
    BALANCED(120_000L),
    BATTERY_SAVER(600_000L),
}

enum class ThemeMode {
    LIGHT, DARK, AMOLED, SYSTEM
}

enum class MapStyle {
    STANDARD, SATELLITE, DARK
}

/** Patrones de vibración para notificaciones/"hacer sonar el dispositivo" (timings en ms:
 * apagado, encendido, apagado...). [cycles] = cuántas veces se repite el patrón completo antes
 * de pararse solo — solo se aplica a las notificaciones personalizables (zona); "hacer sonar" y
 * los mensajes de emergencia ignoran esto a propósito y suenan/vibran en bucle hasta que el
 * usuario los para (ver [com.dskmusic.lokate.push.NotificationHelper]). */
enum class VibrationPattern(val timings: LongArray, val cycles: Int) {
    SOFT(longArrayOf(0, 200, 300, 200, 300, 200, 300), cycles = 3),
    STRONG(longArrayOf(0, 700, 200, 700, 200, 700, 200), cycles = 3),
    SOS(
        longArrayOf(
            0, 150, 150, 150, 150, 150, // S: . . .
            400, // pausa
            400, 150, 400, 150, 400, 150, // O: - - -
            400, // pausa
            150, 150, 150, 150, 150, 150, // S: . . .
            800,
        ),
        cycles = 1,
    ),
    HEARTBEAT(longArrayOf(0, 120, 120, 180, 600), cycles = 3),
}
