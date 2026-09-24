package com.dskmusic.lokate.location

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.dskmusic.lokate.data.remote.dto.ZoneDto
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.util.PermissionUtils
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Le pasa las zonas del grupo al sistema para que sea EL MÓVIL quien vigile los bordes.
 *
 * Sin esto, una entrada o una salida no se detecta hasta que llega el siguiente ping: con el
 * móvil quieto eso son hasta 15 minutos, y una visita corta (entrar y salir) puede no caer en
 * ningún ping y no avisar nunca. Registradas aquí, las vigila el mismo subsistema de bajo
 * consumo que ya lleva la detección de actividad — el proceso puede estar muerto y el GPS
 * apagado — y despierta a [GeofenceReceiver] en cuanto se cruza un borde.
 *
 * NO sustituye a la comprobación del servidor: es el camino rápido. Quien decide de verdad si
 * alguien está dentro o fuera sigue siendo geofence.py con cada ping que llega, porque hay
 * fabricantes que se cargan las geocercas registradas al matar la app.
 */
object ZoneGeofencing {

    /** Qué lista está registrada ahora mismo y desde cuándo, para el freno de [refresh]. En
     * memoria a propósito: si el proceso ha muerto (que es justo cuando un fabricante puede
     * haberse cargado las geocercas) vuelve a cero y la primera llamada las registra otra vez
     * sin esperar. */
    @Volatile
    private var lastRegisteredAt = 0L

    @Volatile
    private var lastSignature: String? = null

    /** Vuelve a registrarlas leyendo la caché de Room. Para quien no tiene ya la lista a mano
     * (el worker de respaldo, el arranque del móvil). El freno lo pone el otro [refresh]. */
    suspend fun refresh(context: Context) {
        val zones = runCatching {
            ServiceLocator.getInstance(context).zoneRepository.observeZones().first()
        }.getOrNull() ?: return
        refresh(context, zones)
    }

    /**
     * Idempotente a propósito: se manda siempre la lista entera y el sistema reemplaza lo que
     * hubiera. Así no hay estado que se pueda desincronizar y da igual llamarlo de más.
     *
     * Freno compartido por TODOS los que llaman (el servicio cuando Room emite, el worker cada
     * 15 minutos, el arranque): si la lista es la misma que ya está registrada y hace menos de
     * [ROUTINE_MIN_GAP_MS] que se registró, no se toca nada. Un cambio de verdad entra siempre
     * al instante — cambia la firma — y una lista repetida vuelve a registrarse igual una vez
     * por hora, que es lo que protege de los fabricantes que borran las geocercas por su cuenta.
     *
     * Hacía falta porque ZoneRepository.refresh() reescribe las zonas en Room aunque el servidor
     * haya devuelto lo mismo, y Room reemite: cada 15 minutos se borraban y volvían a registrar
     * 100 geocercas en Play Services sin que hubiera cambiado nada.
     */
    suspend fun refresh(context: Context, zones: List<ZoneDto>) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val signature = zones.joinToString("|") { "${it.id}:${it.lat}:${it.lng}:${it.radius_m}" }
        val now = System.currentTimeMillis()
        if (signature == lastSignature && now - lastRegisteredAt < ROUTINE_MIN_GAP_MS) return@withContext
        // Sin permiso de segundo plano el sistema rechaza el registro. No es un fallo que haya
        // que avisar aquí: ya sale en los avisos de configuración (ver ConfigCheck).
        if (!PermissionUtils.hasBackgroundLocationPermission(app)) return@withContext

        val client = LocationServices.getGeofencingClient(app)
        val pending = pendingIntent(app)
        runCatching { Tasks.await(client.removeGeofences(pending)) }
        if (zones.isEmpty()) {
            lastSignature = signature
            lastRegisteredAt = now
            return@withContext
        }

        // ponytail: si un grupo llegara a tener más de 100 zonas se quedan las primeras por
        // nombre, que es como llegan de Room. Si eso pasa alguna vez, ordenar por cercanía.
        val fences = zones.take(MAX_FENCES).map { zone ->
            Geofence.Builder()
                .setRequestId(zone.id)
                // Por debajo de unos 100 m el sistema avisa tarde y mal (la posición en segundo
                // plano la ponen wifi y antenas): se vigila un círculo más grande y ya afina el
                // servidor con el radio real cuando llegue el ping.
                .setCircularRegion(zone.lat, zone.lng, maxOf(zone.radius_m, MIN_RADIUS_M).toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                // Cuánto puede tardar el sistema en darse por enterado. Un minuto deja que
                // agrupe el aviso con otros trabajos y siga saliendo casi gratis; bajarlo a 0
                // gana segundos y cuesta batería.
                .setNotificationResponsiveness(RESPONSIVENESS_MS)
                .build()
        }

        val request = GeofencingRequest.Builder()
            // Sin disparo inicial: si no, cada re-registro (cada 15 min, cada arranque) avisaría
            // de que se está dentro de las zonas donde ya se estaba.
            .setInitialTrigger(0)
            .addGeofences(fences)
            .build()
        // Solo se apunta si Play Services lo aceptó: si falló (sin red, sin Play Services,
        // el sistema ocupado) el freno no debe tapar el siguiente intento.
        runCatching { Tasks.await(client.addGeofences(request, pending)) }.onSuccess {
            lastSignature = signature
            lastRegisteredAt = now
            BatteryStats.onGeofenceRegister(app)
        }
    }

    /** MUTABLE porque el sistema escribe dentro el resultado de la transición. Explícito al
     * receptor, así no hace falta filtro en el manifiesto. */
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** Tope del sistema: 100 geocercas por app. */
    private const val MAX_FENCES = 100
    private const val MIN_RADIUS_M = 100.0
    private const val ROUTINE_MIN_GAP_MS = 60 * 60_000L
    private const val RESPONSIVENESS_MS = 60_000
}
