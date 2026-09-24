package com.dskmusic.lokate.location

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.dskmusic.lokate.BuildConfig
import com.dskmusic.lokate.data.remote.dto.BatteryReportDto
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.util.ConfigCheck
import com.dskmusic.lokate.util.DeviceStatusUtils
import kotlinx.coroutines.flow.first

/**
 * Lo que ESTA app le ha hecho gastar al móvil desde la última carga, medido por ella misma.
 *
 * El desglose por aplicación que enseña Android (los mAh de la pantalla de batería) es
 * privilegiado: ninguna app puede leerlo, ni siquiera el suyo propio. Lo que sí se puede es
 * contar lo que uno mismo hace —cuánto tiempo ha tenido el GPS fino pedido, cuántos pings ha
 * mandado, cuántas veces le ha despertado el worker— y preguntarle al sistema en qué régimen le
 * tiene (cubo de reposo, optimización de batería, ahorro de energía, muertes del proceso). Con
 * eso un admin diagnostica desde su móvil sin depender de que el usuario sepa mirar ajustes.
 *
 * Cómo se mide el tiempo: hay un "trozo" abierto (modo actual y desde cuándo) y cada vez que
 * pasa algo —un fix, un cambio de ritmo, el worker de los 15 minutos— se cierra y se suma al
 * saco que toque. Lo que no se mide no se cuenta: un hueco de más de [DEAD_GAP_MS] (proceso
 * muerto, reposo profundo) no se le suma a ningún modo y el informe lo enseña como la diferencia
 * entre el periodo y el tiempo medido.
 *
 * ponytail: SharedPreferences y no DataStore como el resto de ajustes. Esto se toca desde el
 * hilo del callback de ubicación, desde receptores y desde corrutinas, varias veces por minuto
 * y sin nadie observando los cambios: apply() es exactamente eso, y DataStore obligaría a meter
 * suspend (y un scope) en todos esos sitios para no ganar nada.
 */
object BatteryStats {

    /** Si el servicio en primer plano está vivo AHORA. Solo en memoria a propósito: el informe se
     * genera en este mismo proceso, y si el proceso ha muerto el servicio también. */
    @Volatile
    var serviceAlive = false

    const val MODE_LIVE = "live"
    const val MODE_MOVE = "move"
    const val MODE_IDLE = "idle"
    const val MODE_OFF = "off"

    /**
     * Arranque del proceso. Abre el periodo si esta instalación todavía no medía nada y empieza
     * un trozo nuevo SIN cerrar el anterior: lo que haya pasado mientras el proceso estaba muerto
     * no se le suma a ningún modo, sale como "sin medir" en el informe.
     *
     * Lo llama [com.dskmusic.lokate.LokateApplication], no el primer ping: con el móvil quieto
     * pueden pasar 15 minutos hasta que hay ping, y hasta entonces no se medía nada.
     */
    @Synchronized
    fun start(context: Context) {
        val prefs = prefs(context)
        if (prefs.getLong(K_PERIOD_START, 0L) == 0L) {
            resetPeriod(prefs, batteryLevel(context), fromCharge = false)
        } else {
            prefs.edit().putLong(K_SEGMENT_SINCE, System.currentTimeMillis()).apply()
        }
    }

    /**
     * El cargador se ha puesto o se ha quitado (lo avisa el sistema al instante, ver
     * LocationForegroundService.powerReceiver). Los dos casos empiezan periodo y por eso da igual
     * cuál sea: mientras carga no hay gasto que medir, y al desenchufar empieza de verdad
     * "desde la última carga".
     */
    @Synchronized
    fun onPower(context: Context) {
        resetPeriod(prefs(context), batteryLevel(context), fromCharge = true)
    }

    /** Cierra el trozo de tiempo que llevaba corriendo y abre otro con el modo nuevo. */
    @Synchronized
    fun onMode(context: Context, mode: String, highAccuracy: Boolean) {
        val prefs = prefs(context)
        accumulate(prefs)
        prefs.edit().putString(K_MODE, mode).putBoolean(K_HIGH, highAccuracy).apply()
    }

    /** Cierra el trozo de tiempo sin cambiar de modo. Lo llaman los sitios por los que se pasa
     * cada poco (un fix, el worker) para que el reparto no dependa de que haya cambios de ritmo. */
    @Synchronized
    fun tick(context: Context) {
        accumulate(prefs(context))
    }

    @Synchronized
    fun onFix(context: Context, sent: Boolean) {
        accumulate(prefs(context))
        bump(context, if (sent) K_FIX_OK else K_FIX_DROP)
    }

    fun onPing(context: Context, delivered: Boolean) = bump(context, if (delivered) K_PING_OK else K_PING_FAIL)

    fun onOneShot(context: Context) = bump(context, K_ONE_SHOT)

    fun onLiveSession(context: Context) = bump(context, K_LIVE_SESSION)

    fun onWorkerRun(context: Context) {
        bump(context, K_WORKER)
        tick(context)
    }

    fun onGeofenceEvent(context: Context) = bump(context, K_GEO_EVENT)

    fun onGeofenceRegister(context: Context) = bump(context, K_GEO_REGISTER)

    fun onPush(context: Context) = bump(context, K_PUSH)

    /**
     * El nivel de batería que acaba de leerse para un ping. Si el móvil está cargando empieza un
     * periodo nuevo: lo que interesa es el gasto DESDE la última carga.
     *
     * ponytail: el corte es el último ping que vio el cargador puesto, no el instante exacto de
     * desenchufar (que nadie nos cuenta). Puede irse de unos minutos y no cambia el diagnóstico.
     */
    @Synchronized
    fun onBattery(context: Context, level: Int?, charging: Boolean?) {
        val prefs = prefs(context)
        if (charging == true) {
            resetPeriod(prefs, level, fromCharge = true)
            return
        }
        // Primera vez (instalación recién hecha): el periodo empieza aquí, no en 1970.
        if (prefs.getLong(K_PERIOD_START, 0L) == 0L) resetPeriod(prefs, level, fromCharge = false)
    }

    /** El informe completo, tal cual sube al servidor. Lo pide un admin por push silencioso. */
    suspend fun report(context: Context): BatteryReportDto {
        val app = context.applicationContext
        tick(app)
        val prefs = prefs(app)
        val now = System.currentTimeMillis()
        val status = DeviceStatusUtils.read(app)
        onBattery(app, status.batteryLevel, status.isCharging)
        val periodStart = prefs.getLong(K_PERIOD_START, now)
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val exits = exitReasons(app)
        return BatteryReportDto(
            generated_at = now,
            app_version = BuildConfig.VERSION_NAME,
            device = Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE,
            period_start = periodStart,
            period_ms = now - periodStart,
            period_from_charge = prefs.getBoolean(K_FROM_CHARGE, false),
            battery_start_pct = prefs.getInt(K_START_PCT, -1),
            battery_now_pct = status.batteryLevel ?: -1,
            is_charging = status.isCharging == true,
            temperature_c = temperature(app),
            live_ms = prefs.getLong(msKey(MODE_LIVE), 0L),
            move_ms = prefs.getLong(msKey(MODE_MOVE), 0L),
            idle_ms = prefs.getLong(msKey(MODE_IDLE), 0L),
            off_ms = prefs.getLong(msKey(MODE_OFF), 0L),
            gps_high_ms = prefs.getLong(K_MS_HIGH, 0L),
            gps_balanced_ms = prefs.getLong(K_MS_BALANCED, 0L),
            fixes_ok = prefs.getLong(K_FIX_OK, 0L),
            fixes_dropped = prefs.getLong(K_FIX_DROP, 0L),
            pings_ok = prefs.getLong(K_PING_OK, 0L),
            pings_failed = prefs.getLong(K_PING_FAIL, 0L),
            one_shots = prefs.getLong(K_ONE_SHOT, 0L),
            live_sessions = prefs.getLong(K_LIVE_SESSION, 0L),
            worker_runs = prefs.getLong(K_WORKER, 0L),
            geofence_events = prefs.getLong(K_GEO_EVENT, 0L),
            geofence_registers = prefs.getLong(K_GEO_REGISTER, 0L),
            pushes = prefs.getLong(K_PUSH, 0L),
            frequency = ServiceLocator.getInstance(app).settings.locationFrequency.first().name,
            mode = prefs.getString(K_MODE, MODE_OFF) ?: MODE_OFF,
            last_tick_at = prefs.getLong(K_SEGMENT_SINCE, 0L),
            service_running = serviceAlive,
            standby_bucket = standbyBucket(app),
            // Se saca de lo que ya calcula ConfigCheck en cada ping, que es la misma pregunta.
            ignoring_battery_optimizations = ConfigCheck.BATTERY !in status.configIssues.orEmpty(),
            power_save = power.isPowerSaveMode,
            device_idle = power.isDeviceIdleMode,
            config_issues = status.configIssues.orEmpty(),
            exit_count = exits.size,
            last_exit_at = exits.firstOrNull()?.first ?: 0L,
            last_exit_reason = exits.firstOrNull()?.second ?: 0,
            last_exit_description = exits.firstOrNull()?.third,
        )
    }

    // ------------------------------------------------------------------------------- tripas

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun accumulate(prefs: SharedPreferences) {
        val now = System.currentTimeMillis()
        val since = prefs.getLong(K_SEGMENT_SINCE, 0L)
        val edit = prefs.edit().putLong(K_SEGMENT_SINCE, now)
        val elapsed = now - since
        // Un hueco enorme no es tiempo de ningún modo: o el proceso estaba muerto, o el sistema
        // tenía la app en reposo profundo sin dejar correr ni el worker, o alguien movió el reloj
        // hacia atrás. Antes se le sumaba al último modo (hasta seis horas de golpe) y eso inflaba
        // "en movimiento" con ratos en los que la app no hizo absolutamente nada. Ahora no se
        // atribuye: el informe lo enseña como la diferencia entre el periodo y el tiempo medido.
        if (since > 0L && elapsed in 1 until DEAD_GAP_MS) {
            val mode = prefs.getString(K_MODE, MODE_OFF) ?: MODE_OFF
            edit.putLong(msKey(mode), prefs.getLong(msKey(mode), 0L) + elapsed)
            // El tiempo de GPS va por separado del modo a propósito: son dos preguntas distintas
            // ("cuánto ha estado en reposo" y "cuánto ha tenido el GPS fino en marcha").
            if (mode != MODE_OFF) {
                val key = if (prefs.getBoolean(K_HIGH, false)) K_MS_HIGH else K_MS_BALANCED
                edit.putLong(key, prefs.getLong(key, 0L) + elapsed)
            }
        }
        edit.apply()
    }

    @Synchronized
    private fun bump(context: Context, key: String) {
        val prefs = prefs(context)
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + 1L).apply()
    }

    /** [fromCharge] = el periodo empieza en un cargador (que es lo que el informe promete).
     * Si empieza porque se acaba de instalar la app, el informe lo dice con otras palabras en vez
     * de mentir con un "desde la última carga" de dos minutos. */
    private fun resetPeriod(prefs: SharedPreferences, level: Int?, fromCharge: Boolean) {
        val now = System.currentTimeMillis()
        val edit = prefs.edit()
        for (key in COUNTERS) edit.remove(key)
        edit.putLong(K_PERIOD_START, now)
            .putBoolean(K_FROM_CHARGE, fromCharge)
            .putInt(K_START_PCT, level ?: -1)
            .putLong(K_SEGMENT_SINCE, now)
            .apply()
    }

    /** El nivel de batería a secas. DeviceStatusUtils.read() hace bastante más (wifi, permisos)
     * y esto se llama al arrancar el proceso, donde no hace falta nada de eso. */
    private fun batteryLevel(context: Context): Int? =
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    /** Décimas de grado a grados. -1 = este móvil no lo publica. */
    private fun temperature(context: Context): Double {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1.0
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        return if (tenths <= 0) -1.0 else tenths / 10.0
    }

    /** En qué cubo de reposo nos tiene el sistema (10 activo … 45 restringido). -1 = no se sabe. */
    private fun standbyBucket(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return -1
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return -1
        return runCatching { usage.appStandbyBucket }.getOrDefault(-1)
    }

    /** Las muertes del proceso desde que empezó el periodo (cuándo, código y explicación del
     * sistema), de la más reciente a la más vieja. Es lo que delata al fabricante que mata la
     * app por su cuenta. Android 11 en adelante; por debajo, lista vacía. */
    private fun exitReasons(context: Context): List<Triple<Long, Int, String?>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        val since = prefs(context).getLong(K_PERIOD_START, 0L)
        return runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
                .filter { it.timestamp >= since }
                .map { Triple(it.timestamp, it.reason, it.description) }
        }.getOrDefault(emptyList())
    }

    private fun msKey(mode: String) = "ms_" + mode

    private const val PREFS = "battery_stats"
    private const val K_MODE = "mode"
    private const val K_HIGH = "high_accuracy"
    private const val K_SEGMENT_SINCE = "segment_since"
    private const val K_PERIOD_START = "period_start"
    private const val K_FROM_CHARGE = "from_charge"
    private const val K_START_PCT = "start_pct"
    private const val K_MS_HIGH = "ms_gps_high"
    private const val K_MS_BALANCED = "ms_gps_balanced"
    private const val K_FIX_OK = "n_fix_ok"
    private const val K_FIX_DROP = "n_fix_drop"
    private const val K_PING_OK = "n_ping_ok"
    private const val K_PING_FAIL = "n_ping_fail"
    private const val K_ONE_SHOT = "n_one_shot"
    private const val K_LIVE_SESSION = "n_live"
    private const val K_WORKER = "n_worker"
    private const val K_GEO_EVENT = "n_geo_event"
    private const val K_GEO_REGISTER = "n_geo_register"
    private const val K_PUSH = "n_push"

    /** Todo lo que se pone a cero al empezar un periodo (o sea, al cargar el móvil). */
    private val COUNTERS = listOf(
        msKey(MODE_LIVE), msKey(MODE_MOVE), msKey(MODE_IDLE), msKey(MODE_OFF),
        K_MS_HIGH, K_MS_BALANCED, K_FIX_OK, K_FIX_DROP, K_PING_OK, K_PING_FAIL,
        K_ONE_SHOT, K_LIVE_SESSION, K_WORKER, K_GEO_EVENT, K_GEO_REGISTER, K_PUSH,
    )

    /** A partir de aquí, un trozo de tiempo deja de creerse. El worker pasa cada 15 minutos y
     * es quien marca el ritmo mínimo; el doble deja sitio a que el sistema lo retrase un poco sin
     * dar por muerta a la app. Lo que pase de ahí se queda sin medir a propósito.
     *
     * ponytail: se podría afinar con la hora exacta de la última muerte del proceso
     * (getHistoricalProcessExitReasons ya se lee más arriba), pero eso solo lo hay de Android 11
     * para arriba y este listón ya separa "dormida" de "trabajando". */
    private const val DEAD_GAP_MS = 30 * 60_000L
    private const val MAX_EXITS = 10
}
