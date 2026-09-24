package com.dskmusic.lokate

import android.app.Application
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.location.BatteryStats
import com.dskmusic.lokate.push.NotificationHelper
import com.dskmusic.lokate.util.AppUpdater
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.util.MapTileCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.util.concurrent.TimeUnit

class LokateApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        // El informe de batería empieza a contar con el proceso, no con el primer ping: si no,
        // un móvil quieto se pasa quince minutos sin medir nada.
        BatteryStats.start(this)
        // Si el arranque viene de haber instalado una actualización, el admin que la pidió se
        // entera aquí (no hace nada si no había ninguna a medias).
        AppUpdater.confirmPendingInstall(this)

        // Configuración recomendada por osmdroid: User-Agent propio y caché en almacenamiento de la app.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = getExternalFilesDir(null) ?: filesDir
            osmdroidTileCache = java.io.File(osmdroidBasePath, "tiles")

            // Por defecto osmdroid solo descarga con 2 hebras a la vez — con zoom inicial alto
            // (nivel 20) hacen falta muchas teselas de golpe al abrir el mapa, y con solo 2 en
            // paralelo se notaba la carga lenta. Más hebras + más cola + más teselas en memoria
            // (menos recarga al mover el mapa) suaviza bastante esa primera carga.
            tileDownloadThreads = 8
            // Cuando la cola se llena, osmdroid TIRA las peticiones que no caben (sin reintento):
            // esas teselas se quedan dibujadas con la del zoom de arriba estirada — el mapa
            // "pixelado" hasta que lo tocas. Una pantalla grande con el anillo de overshoot pide
            // bastante más de 40 de golpe, así que la cola se llenaba en cada apertura.
            tileDownloadMaxQueueSize = 200
            cacheMapTileCount = 12
            // osmdroid dimensiona la caché en memoria justo a las teselas que caben en pantalla,
            // así que al arrastrar el mapa las que acaban de salir por un borde ya no están y
            // hay que volver a leerlas. Este margen guarda un anillo extra alrededor.
            cacheMapTileOvershoot = 12
        }

        // Vaciado automático de la caché de teselas cada MAP_CACHE_MAX_AGE_DAYS: no comprueba
        // tesela a tesela si el servidor tiene algo más nuevo (osmdroid no lo soporta de
        // fábrica), simplemente refresca la caché entera de vez en cuando para que nunca se
        // quede desactualizada indefinidamente. También se puede vaciar a mano desde Ajustes.
        CoroutineScope(Dispatchers.IO).launch {
            val settings = ServiceLocator.getInstance(applicationContext).settings
            val lastClear = settings.mapCacheClearedAt.first()
            val maxAgeMs = TimeUnit.DAYS.toMillis(Constants.MAP_CACHE_MAX_AGE_DAYS)
            if (System.currentTimeMillis() - lastClear >= maxAgeMs) {
                MapTileCache.clear(applicationContext)
                settings.setMapCacheClearedAt(System.currentTimeMillis())
            }
        }
    }
}
