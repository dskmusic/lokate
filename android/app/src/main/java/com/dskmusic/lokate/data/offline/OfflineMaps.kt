package com.dskmusic.lokate.data.offline

import android.content.Context
import com.dskmusic.lokate.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mapsforge.map.reader.MapFile
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Descarga en curso. [totalBytes] es 0 mientras el servidor no haya dicho cuánto pesa. */
data class OfflineMapDownload(
    val regionId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
}

/**
 * Mapas sin conexión: archivos .map de Mapsforge guardados en el almacenamiento externo de la app.
 *
 * Objeto global y no una clase en el ServiceLocator porque el estado que guarda (qué hay
 * descargado, qué se está descargando) lo miran a la vez el mapa, el historial y los ajustes, y
 * porque la descarga tiene que seguir viva aunque el usuario se vaya de la pantalla.
 */
object OfflineMaps {

    private const val EXTENSION = ".map"
    private const val PARTIAL_EXTENSION = ".map.part"

    // Fuera del ciclo de vida de cualquier pantalla a propósito: perder 50 MB a medias porque
    // alguien vuelve atrás sería absurdo. Solo muere con el proceso.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // Generoso: son archivos grandes y la conexión puede ir a ratos.
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val _installed = MutableStateFlow<List<File>>(emptyList())
    val installed: StateFlow<List<File>> = _installed.asStateFlow()

    private val _download = MutableStateFlow<OfflineMapDownload?>(null)
    val download: StateFlow<OfflineMapDownload?> = _download.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile
    private var listLoaded = false

    /** Carpeta propia de la app: se borra al desinstalar y no necesita permisos. */
    fun directory(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "offline_maps").apply { mkdirs() }

    fun refresh(context: Context) {
        listLoaded = true
        _installed.value = directory(context).listFiles()
            ?.filter { it.name.endsWith(EXTENSION) }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    /** Archivos que hay ahora mismo. Lee el disco la primera vez; después va del cacheado, que
     * esto lo llama el mapa en cada refresco de posición. */
    fun files(context: Context): List<File> {
        if (!listLoaded) refresh(context)
        return _installed.value
    }

    fun regionOf(file: File): OfflineRegion? = regionById(file.name.removeSuffix(EXTENSION))

    /** Qué trozo de mundo cubre cada .map, leído de la cabecera del propio archivo (el catálogo
     * oficial no publica los límites). Se cachea: abrirlo son milisegundos, pero esto se consulta
     * cada vez que se mueve el mapa. */
    private val boundsCache = HashMap<String, OfflineBounds?>()

    private fun boundsOf(file: File): OfflineBounds? = boundsCache.getOrPut(file.name + file.length()) {
        runCatching {
            val map = MapFile(file)
            try {
                val box = map.boundingBox()
                OfflineBounds(box.minLatitude, box.minLongitude, box.maxLatitude, box.maxLongitude)
            } finally {
                map.close()
            }
        }.getOrNull()
    }

    /** true si algún mapa descargado cubre ese punto. Con false no hay nada que dibujar ahí y el
     * mapa tira de internet (ver [com.dskmusic.lokate.ui.map.applyMapStyle]). */
    fun covers(lat: Double, lng: Double): Boolean =
        _installed.value.any { boundsOf(it)?.contains(lat, lng) == true }

    fun delete(context: Context, file: File) {
        file.delete()
        refresh(context)
    }

    /** Tamaño exacto que dice el servidor, para avisar antes de tirar de datos. `null` si no se
     * puede preguntar (sin red, o el archivo ya no está en el catálogo). */
    suspend fun remoteSizeBytes(region: OfflineRegion): Long? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(urlOf(region)).head().build()).execute().use { response ->
                if (response.isSuccessful) response.header("Content-Length")?.toLongOrNull() else null
            }
        }.getOrNull()
    }

    /** Arranca la descarga si no hay otra en marcha. El progreso sale por [download]. */
    fun start(context: Context, region: OfflineRegion) {
        if (job?.isActive == true) return
        _error.value = null
        job = scope.launch {
            val target = File(directory(context), region.id + EXTENSION)
            // Se baja a un .part y se renombra al final: si se corta a medias, no queda un
            // archivo truncado que Mapsforge intentaría abrir y reventaría el mapa.
            val partial = File(directory(context), region.id + PARTIAL_EXTENSION)
            _download.value = OfflineMapDownload(region.id, 0, 0)
            try {
                client.newCall(Request.Builder().url(urlOf(region)).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val body = checkNotNull(response.body) { "respuesta sin contenido" }
                    val total = body.contentLength()
                    partial.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                done += read
                                _download.value = OfflineMapDownload(region.id, done, total)
                            }
                        }
                    }
                }
                target.delete()
                check(partial.renameTo(target)) { "no se pudo guardar el archivo" }
            } catch (cancelled: CancellationException) {
                partial.delete()
                throw cancelled
            } catch (e: Exception) {
                partial.delete()
                _error.value = e.message ?: e.javaClass.simpleName
            } finally {
                _download.value = null
                refresh(context)
            }
        }
    }

    // ponytail: cancelar tira lo bajado y hay que empezar de cero. Reanudar por rangos (HTTP
    // Range) solo merece la pena si alguien se pone a bajar países de gigas.
    fun cancel() {
        job?.cancel()
    }

    fun clearError() {
        _error.value = null
    }

    private fun urlOf(region: OfflineRegion) = "${Constants.MAPSFORGE_BASE_URL}${region.path}.map"
}

/** "48 MB", "1,2 GB". */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%d MB", bytes / 1_000_000)
    else -> String.format(Locale.getDefault(), "%d KB", bytes / 1000)
}
