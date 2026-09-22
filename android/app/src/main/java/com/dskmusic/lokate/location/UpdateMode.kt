package com.dskmusic.lokate.location

import com.dskmusic.lokate.util.LocationFrequency

/**
 * Cómo está mandando posición este móvil ahora mismo. Viaja en cada ping para que el resto del
 * grupo pueda ver POR QUÉ la última actualización de alguien es de hace 12 minutos teniendo
 * puesto "cada minuto": casi siempre es que no se está moviendo, no que algo se haya roto.
 *
 * ponytail: en memoria y no en DataStore, igual que [LiveTracking] — lo escribe el servicio y lo
 * lee el repositorio en el mismo proceso, y si el proceso muere el siguiente ping sale de un
 * servicio recién arrancado que lo vuelve a calcular en la primera pasada.
 */
object UpdateMode {
    /** Moviéndose: manda al ritmo elegido en Ajustes. */
    const val MOVING = "moving"

    /** Sin moverse (lo dice el sensor de actividad o que su posición no cambia): una cada 15 min. */
    const val STILL = "still"

    /** En una wifi marcada como sitio fijo: una cada 15 min, se mueva o no. */
    const val HOME_WIFI = "home_wifi"

    /** Alguien lo tiene en seguimiento en vivo: cada pocos segundos, por encima de todo. */
    const val LIVE = "live"

    /** No manda nada por su cuenta, solo contesta a quien le pide la posición. */
    const val ON_DEMAND = "on_demand"

    @Volatile private var still = false
    @Volatile private var homeWifi = false
    @Volatile private var stationary = false

    /** Las dos señales de reposo que vigila el servicio (ver applyLocationRequest). */
    fun setIdle(still: Boolean, homeWifi: Boolean) {
        this.still = still
        this.homeWifi = homeWifi
    }

    /** Si la última posición aceptada NO venía de haberse desplazado. Cubre a quien denegó el
     * permiso de actividad: su móvil no sabe que está quieto, pero el filtro de distancia de
     * [LocationForegroundService.worthSending] le espacia los pings igual, y sin esto el grupo
     * lo vería como "en movimiento" llevando 15 minutos sin dar señales. */
    fun setStationary(value: Boolean) {
        stationary = value
    }

    /**
     * El modo que va en el ping. El seguimiento en vivo y los modos que no mandan nada por su
     * cuenta se deciden aquí y no en el servicio porque valen para todos los que pingean: la
     * ubicación puntual se manda desde un móvil que puede no tener servicio corriendo.
     */
    fun forPing(frequency: LocationFrequency): String = when {
        LiveTracking.until.value > System.currentTimeMillis() -> LIVE
        !frequency.sendsPeriodicUpdates -> ON_DEMAND
        // El wifi manda sobre lo demás cuando coinciden: "está en su casa" explica más que
        // "está quieto", y estar en casa parado es justo el caso normal de los dos a la vez.
        homeWifi -> HOME_WIFI
        still || stationary -> STILL
        else -> MOVING
    }
}
