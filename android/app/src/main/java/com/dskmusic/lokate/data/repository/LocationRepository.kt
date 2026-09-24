package com.dskmusic.lokate.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.dskmusic.lokate.data.local.LocationHistoryDao
import com.dskmusic.lokate.data.local.LocationHistoryEntity
import com.dskmusic.lokate.data.local.PendingPingDao
import com.dskmusic.lokate.data.local.PendingPingEntity
import com.dskmusic.lokate.data.prefs.SettingsDataStore
import com.dskmusic.lokate.data.remote.ApiService
import com.dskmusic.lokate.data.remote.dto.BatteryReportDto
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.data.remote.dto.LocationPingBatchRequestDto
import com.dskmusic.lokate.data.remote.dto.LocationPingRequestDto
import com.dskmusic.lokate.data.remote.dto.QueuedPingDto
import com.dskmusic.lokate.location.BatteryStats
import com.dskmusic.lokate.location.LiveTracking
import com.dskmusic.lokate.location.UpdateMode
import com.dskmusic.lokate.util.LocationFrequency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class DeviceStatus(
    val batteryLevel: Int?,
    val isCharging: Boolean?,
    val wifiConnected: Boolean?,
    val wifiSsid: String? = null,
    /** Códigos de [com.dskmusic.lokate.util.ConfigCheck] separados por comas, vacío si todo
     * está bien. Va aquí y no como parámetro suelto de [LocationRepository.ping] porque se lee
     * en el mismo sitio que el resto del estado del dispositivo, en cada ping, sin tocar los
     * sitios que llaman. */
    val configIssues: String? = null,
)

class LocationRepository(
    private val api: ApiService,
    private val historyDao: LocationHistoryDao,
    private val pendingPingDao: PendingPingDao,
    private val settings: SettingsDataStore,
    private val context: Context,
) {

    /** Última vez que se guardó en disco la marca de "ping entregado". Ver [recordSuccess]. */
    @Volatile private var lastPingOkPersistedAt = 0L

    /** Si puede haber algo en la cola. Empieza en true porque al arrancar el proceso no se
     * sabe qué dejó pendiente la sesión anterior. Evita que [flushPending] toque la base de
     * datos en cada ping (uno cada 3 s en tiempo real) para encontrarla vacía casi siempre. */
    @Volatile private var mayHavePending = true

    /**
     * Manda la posición al servidor y, si no se puede, la guarda para más tarde.
     *
     * Devuelve true si el punto está a salvo (entregado o en la cola) y false solo si se ha
     * perdido del todo — quien llama lo necesita para no dar por enviado algo que no salió
     * (ver [com.dskmusic.lokate.location.LocationForegroundService]).
     */
    suspend fun ping(
        lat: Double,
        lng: Double,
        accuracy: Float?,
        status: DeviceStatus,
        frequency: LocationFrequency,
    ): Boolean = withContext(Dispatchers.IO) {
        val body = LocationPingRequestDto(
            lat = lat,
            lng = lng,
            accuracy = accuracy,
            battery_level = status.batteryLevel,
            is_charging = status.isCharging,
            wifi_connected = status.wifiConnected,
            wifi_ssid = status.wifiSsid,
            location_frequency = frequency.name,
            config_issues = status.configIssues,
            update_mode = UpdateMode.forPing(frequency),
        )
        // El nivel de batería ya está leído aquí: es el sitio natural para que el contador de
        // gasto se entere de que el móvil está cargando y empiece un periodo nuevo.
        BatteryStats.onBattery(context, status.batteryLevel, status.isCharging)
        // Sin red no se intenta siquiera: cada intento condenado enciende la radio móvil unos
        // segundos para nada, y eso en modo túnel/ascensor pasa continuamente.
        if (!isOnline()) return@withContext queueAndCount(body)
        repeat(PING_ATTEMPTS) { attempt ->
            // La respuesta del ping es por donde llegan tanto la renovación como el fin del
            // seguimiento en vivo: se aplica aquí, que es por donde pasan todos los que
            // pingean (servicio, worker de respaldo y petición puntual).
            val response = runCatching { api.ping(body) }.getOrNull()
            if (response != null) {
                LiveTracking.update(response.live_seconds)
                BatteryStats.onPing(context, delivered = true)
                recordSuccess()
                // Hay red y el servidor contesta: buen momento para soltar lo que quedó atrás.
                flushPending()
                return@withContext true
            }
            // Un reintento inmediato cae en el mismo agujero de cobertura; uno solo, corto y
            // luego a la cola. Más intentos no arreglan una red caída, solo gastan batería.
            if (attempt < PING_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        queueAndCount(body)
    }

    /** Lo mismo que [queue] pero apuntando el ping fallido en el informe de batería: un móvil
     * con mala cobertura enciende la radio para nada muchas veces al día, y eso se ve aquí. */
    private suspend fun queueAndCount(body: LocationPingRequestDto): Boolean {
        BatteryStats.onPing(context, delivered = false)
        return queue(body)
    }

    /** Sube el informe de batería de ESTE móvil (lo ha pedido un admin por push). */
    suspend fun uploadBatteryReport(report: BatteryReportDto) =
        withContext(Dispatchers.IO) { api.uploadBatteryReport(report) }

    /** Guarda el ping para el próximo vaciado. false = ni eso se ha podido (disco lleno,
     * base de datos rota): el punto se ha perdido y quien llama tiene que saberlo. */
    private suspend fun queue(body: LocationPingRequestDto): Boolean = runCatching {
        pendingPingDao.insert(
            PendingPingEntity(
                lat = body.lat,
                lng = body.lng,
                accuracy = body.accuracy,
                batteryLevel = body.battery_level,
                isCharging = body.is_charging,
                wifiConnected = body.wifi_connected,
                wifiSsid = body.wifi_ssid,
                locationFrequency = body.location_frequency,
                configIssues = body.config_issues,
                updateMode = body.update_mode,
                timestampMillis = System.currentTimeMillis(),
            ),
        )
        pendingPingDao.trim(PENDING_MAX_ROWS)
        mayHavePending = true
        true
    }.getOrDefault(false)

    /**
     * Suelta la cola en UNA petición. No tiene temporizador propio a propósito: se llama
     * cuando ya hay un despertar pagado por otro (un ping que ha ido bien, el worker de cada
     * 15 min). Un vaciado con su propia alarma sería justo el gasto de batería que la cola
     * viene a evitar.
     */
    suspend fun flushPending() = withContext(Dispatchers.IO) {
        if (!mayHavePending || !isOnline()) return@withContext
        // Lo viejo no se manda: un punto de anteayer no le dice nada a nadie y el servidor lo
        // tiraría igual al pasar la escoba de retención.
        runCatching { pendingPingDao.deleteOlderThan(System.currentTimeMillis() - PENDING_MAX_AGE_MS) }
        val pending = runCatching { pendingPingDao.oldest(PENDING_MAX_ROWS) }.getOrNull().orEmpty()
        if (pending.isEmpty()) {
            mayHavePending = false
            return@withContext
        }
        val newest = pending.last()
        val response = runCatching {
            api.pingBatch(
                LocationPingBatchRequestDto(
                    pings = pending.map {
                        QueuedPingDto(it.lat, it.lng, it.accuracy, formatIsoUtc(it.timestampMillis))
                    },
                    battery_level = newest.batteryLevel,
                    is_charging = newest.isCharging,
                    wifi_connected = newest.wifiConnected,
                    wifi_ssid = newest.wifiSsid,
                    location_frequency = newest.locationFrequency,
                    config_issues = newest.configIssues,
                    update_mode = newest.updateMode,
                ),
            )
        }.getOrNull() ?: return@withContext
        // Solo se borra lo que se mandó: si mientras tanto entró otro ping a la cola, se queda.
        runCatching { pendingPingDao.deleteIds(pending.map { it.id }) }
        LiveTracking.update(response.live_seconds)
        recordSuccess()
    }

    suspend fun clearPendingPings() = withContext(Dispatchers.IO) { pendingPingDao.clear() }

    /** Deja constancia de que el envío funciona, para el vigilante del worker. Escribir en
     * disco en cada ping sería absurdo en tiempo real (uno cada 3 s), así que se guarda como
     * mucho una vez por minuto: el vigilante mira márgenes de media hora, le sobra. */
    private suspend fun recordSuccess() {
        val now = System.currentTimeMillis()
        if (now - lastPingOkPersistedAt < PING_OK_PERSIST_MS) return
        lastPingOkPersistedAt = now
        runCatching { settings.setLastPingOkAt(now) }
    }

    /** Si el móvil cree tener red. No garantiza que el servidor conteste, pero descarta el
     * caso que más pasa: avión, túnel, sin cobertura. */
    private fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun formatIsoUtc(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))

    suspend fun groupLatest(): List<LocationDto> = withContext(Dispatchers.IO) { api.groupLatestLocations() }

    suspend fun ringDevice(userId: String) = withContext(Dispatchers.IO) { api.ringDevice(userId) }

    suspend fun stopRing(userId: String) = withContext(Dispatchers.IO) { api.stopRing(userId) }

    suspend fun requestLocation(userId: String) = withContext(Dispatchers.IO) { api.requestLocation(userId) }

    suspend fun setLiveTracking(userId: String, active: Boolean) =
        withContext(Dispatchers.IO) { api.setLiveTracking(userId, active) }

    /** [fromIso]/[toIso]: instantes UTC en ISO-8601 (calculados en el cliente para respetar
     * su huso horario) — un día concreto ("ayer", "hoy" o una fecha elegida). */
    suspend fun refreshHistoryRange(userId: String, fromIso: String, toIso: String) = withContext(Dispatchers.IO) {
        cacheHistory(userId, api.locationHistory(userId = userId, fromTs = fromIso, toTs = toIso))
    }

    private suspend fun cacheHistory(userId: String, points: List<com.dskmusic.lokate.data.remote.dto.LocationHistoryPointDto>) {
        historyDao.clearForUser(userId)
        historyDao.insertAll(
            points.map {
                LocationHistoryEntity(
                    userId = userId,
                    lat = it.lat,
                    lng = it.lng,
                    accuracy = it.accuracy,
                    timestampMillis = parseIsoToMillis(it.timestamp),
                )
            },
        )
    }

    fun observeLocalHistory(userId: String, sinceMillis: Long) = historyDao.observeHistory(userId, sinceMillis)

    suspend fun clearLocalHistory() = withContext(Dispatchers.IO) { historyDao.clear() }

    private companion object {
        /** Un reintento y a la cola (ver [ping]). */
        const val PING_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 2_000L
        /** Tope de la cola. Con el ritmo más lento son días de pings; con el más rápido, un
         * rato largo sin cobertura. Pasado eso se tira lo más viejo. */
        const val PENDING_MAX_ROWS = 300
        const val PENDING_MAX_AGE_MS = 24 * 3600_000L
        const val PING_OK_PERSIST_MS = 60_000L
    }

    private fun parseIsoToMillis(iso: String): Long {
        val formats = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", "yyyy-MM-dd'T'HH:mm:ss")
        for (pattern in formats) {
            runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                return sdf.parse(iso)!!.time
            }
        }
        return System.currentTimeMillis()
    }
}
