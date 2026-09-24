package com.dskmusic.lokate.util

import androidx.annotation.StringRes
import com.dskmusic.lokate.R

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

    /** Avisos que el movil se pinta sobre si mismo. Id fijo y uno por problema: repetir el
     * aviso no debe apilar veinte notificaciones, sino reemplazar la anterior. */
    const val SESSION_EXPIRED_NOTIFICATION_ID = 1002
    const val PING_STALLED_NOTIFICATION_ID = 1003

    const val LOCATION_WORKER_TAG = "lokate_location_backup_worker"

    /** Accesos directos del icono de la app (ver res/xml/shortcuts.xml). La sección va como
     * acción del intent y no como extra porque el XML de shortcuts no admite extras. */
    const val ACTION_OPEN_PEOPLE = "com.dskmusic.lokate.action.OPEN_PEOPLE"
    const val ACTION_OPEN_ZONES = "com.dskmusic.lokate.action.OPEN_ZONES"
    const val ACTION_OPEN_HISTORY = "com.dskmusic.lokate.action.OPEN_HISTORY"

    /** Catálogo oficial de mapas de Mapsforge. Se descarga directo de ahí, el servidor de
     * Lokate no interviene: son archivos públicos generados de OpenStreetMap. */
    const val MAPSFORGE_BASE_URL = "https://download.mapsforge.org/maps/v5/"

    const val DEFAULT_HISTORY_HOURS = 24

    /** Cada cuántos días se vacía sola la caché de teselas del mapa al arrancar la app (ver
     * MapTileCache + LokateApplication) — un mapa de carreteras no cambia tan a menudo como
     * para necesitar comprobar frescura tesela a tesela; refrescar la caché entera de vez en
     * cuando es mucho más simple y ya evita que se quede desactualizada indefinidamente. */
    const val MAP_CACHE_MAX_AGE_DAYS = 14L

    /** Zoom inicial del mapa por defecto. 19 es el último nivel con teselas reales (tanto en el
     * mapa estándar de OSM como en el satélite de Esri): a partir de 20 osmdroid ya no descarga
     * nada, se inventa la tesela escalando la de 19 (MapTileApproximater), que sale borrosa y
     * tarda más en aparecer. Por eso el defecto se queda por debajo de ese límite. */
    const val MAP_DEFAULT_ZOOM = 18

    /** Tope del ajuste de zoom inicial. Por encima de 19 son teselas aproximadas (ver
     * [MAP_DEFAULT_ZOOM]), pero se deja elegir hasta 24 para quien quiera abrir muy cerca. */
    const val MAP_MAX_ZOOM = 24

    /** Cuánto se ve el círculo del margen de error en el mapa, en tanto por ciento (ajuste
     * "mostrar precisión"). Es una opacidad, no un tamaño: el radio siempre son los metros
     * reales que diga el fix. */
    const val MAP_ACCURACY_INTENSITY_DEFAULT = 50

    /** Zoom al que se salta tras elegir un resultado del buscador: suficiente para ver la calle
     * sin pasarse del último nivel con teselas reales (ver [MAP_DEFAULT_ZOOM]). */
    const val MAP_SEARCH_RESULT_ZOOM = 17.0

    /** A partir de cuántos metros el mapa salta al destino en vez de animar el viaje (ver
     * [com.dskmusic.lokate.ui.map.moveTo]). animateTo recorre el camino intermedio al zoom
     * actual, así que un salto largo obliga a descargar las teselas de todo el trayecto y el
     * mapa se queda en gris un buen rato. Subirlo si se prefiere ver el recorrido, bajarlo si
     * aún así tarda en cargar. */
    const val MAP_ANIMATE_MAX_DISTANCE_METERS = 400.0

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

    /** Id de la notificación concreta que lanzó el intent — cada mensaje de emergencia tiene
     * el suyo, para que descartar uno no borre los demás. */
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

    /** Persona a la que se refiere la notificación de zona; al tocarla se abre su ficha. El
     * nombre NO lleva el prefijo "extra_" de los de arriba a propósito: tiene que ser idéntico
     * a la clave del payload del push (ver geofence.py), porque con la app cerrada la
     * notificación la pinta el sistema y Android entrega los "data" tal cual como extras. Así
     * el mismo código sirve venga de donde venga el toque. */
    const val EXTRA_PUSH_USER_ID = "user_id"

    /** Igual que [EXTRA_PUSH_USER_ID]: el nombre es el del payload del push, no "extra_algo". */
    const val EXTRA_PUSH_TYPE = "type"

    /** Aviso de "actualiza la app" que manda un admin desde Ajustes. Al tocarlo se abre la app
     * y se descarga e instala sola, sin buscar nada por menus. */
    const val PUSH_TYPE_UPDATE_PROMPT = "update_prompt"

    /** Id fijo: si el admin lo manda dos veces, la barra no acumula dos avisos iguales. */
    const val UPDATE_NOTIFICATION_ID = 1004
}

/** El orden de los valores es el que se ve en ajustes (de más frecuente a menos), y su nombre
 * es lo que se guarda en el servidor y muestran las fichas de los demás miembros. */
enum class LocationFrequency(
    val intervalMs: Long,
    @StringRes val labelRes: Int,
    @StringRes val shortLabelRes: Int,
) {
    /** Lo más parecido a "Localizar mi dispositivo" de Google/apps similares: pide ubicación al
     * GPS cada pocos segundos en vez de esperar a un sondeo periódico — el límite real de
     * "tiempo real" lo pone la propia frecuencia del chip GPS del teléfono, no este valor. */
    REAL_TIME(3_000L, R.string.frequency_high, R.string.frequency_short_high),
    EVERY_30_SEC(30_000L, R.string.frequency_30_sec, R.string.frequency_short_30_sec),
    EVERY_1_MIN(60_000L, R.string.frequency_1_min, R.string.frequency_short_1_min),
    BALANCED(120_000L, R.string.frequency_balanced, R.string.frequency_short_balanced),
    EVERY_5_MIN(300_000L, R.string.frequency_5_min, R.string.frequency_short_5_min),
    BATTERY_SAVER(600_000L, R.string.frequency_battery_saver, R.string.frequency_short_battery_saver),

    /** Batería casi cero: no se manda nada por iniciativa propia (el servicio en primer plano
     * se para solo al leer este valor), pero sí se responde a quien pida una ubicación puntual
     * desde la ficha de miembro o nos siga en vivo desde el mapa. A cambio, los avisos de zona dejan de funcionar: sin pings no
     * hay nada que comprobar. El intervalo no se usa. */
    ON_DEMAND(0L, R.string.frequency_on_demand, R.string.frequency_short_on_demand),

    /** No se envía nada por iniciativa propia: el servicio en primer plano se para solo al leer
     * este valor. Lo que sí sigue atendiendo, como [ON_DEMAND], es que alguien del grupo pida la
     * ubicación puntual o active el seguimiento en vivo — son peticiones a la cara de gente que
     * ya está en el grupo, y el ajuste está para ahorrar batería, no para esconderse (para eso
     * está el modo oculto). El intervalo no se usa. */
    DISABLED(0L, R.string.frequency_disabled, R.string.frequency_short_disabled),
    ;

    /** Si este modo mantiene vivo el servicio en primer plano. Los dos que no ([ON_DEMAND] y
     * [DISABLED]) solo se diferencian en el texto: ninguno manda nada solo, los dos responden a
     * una petición puntual y al seguimiento en vivo. */
    val sendsPeriodicUpdates: Boolean get() = intervalMs > 0L
}

enum class ThemeMode {
    LIGHT, DARK, AMOLED, SYSTEM
}

enum class MapStyle {
    STANDARD, SATELLITE, DARK,

    /** Mapa vectorial dibujado en el móvil desde los archivos .map descargados
     * (ver [com.dskmusic.lokate.data.offline.OfflineMaps]). Sin mapa descargado de la zona que se
     * está mirando no hay nada que dibujar, así que se cae al mapa de internet. */
    OFFLINE,

    /** Igual que [OFFLINE] pero con el mismo filtro de colores invertidos que [DARK]. */
    OFFLINE_DARK,
    ;

    val isOffline: Boolean get() = this == OFFLINE || this == OFFLINE_DARK
    val isDark: Boolean get() = this == DARK || this == OFFLINE_DARK

    /** El equivalente con teselas de internet, para cuando no hay mapa descargado de la zona
     * que se está mirando: el oscuro sigue oscuro. */
    val online: MapStyle
        get() = when (this) {
            OFFLINE -> STANDARD
            OFFLINE_DARK -> DARK
            else -> this
        }
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
