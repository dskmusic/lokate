package com.dskmusic.lokate.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hasta cuándo (reloj del sistema, en ms) alguien del grupo nos tiene en seguimiento en vivo
 * desde el mapa; 0 = nadie. Mientras corra, el servicio de ubicación va en tiempo real.
 *
 * La marca la pone el servidor: llega por push la primera vez y se renueva en la respuesta de
 * cada ping, así que dejar de seguir la apaga aunque se pierda algún mensaje.
 *
 * ponytail: en memoria y no en DataStore — el push, el servicio y el repositorio viven en el
 * mismo proceso, y si el proceso muere lo peor que pasa es volver al ritmo normal hasta el
 * siguiente ping, que es justo el lado seguro por el que fallar.
 */
object LiveTracking {
    private val _until = MutableStateFlow(0L)
    val until: StateFlow<Long> = _until

    /** Cuándo empezó el seguimiento actual, para no dejar que lo mate una respuesta vieja. */
    private var startedAt = 0L

    /** Margen en el que un "ya no" se ignora: es lo que puede tardar en contestar un ping que
     * salió ANTES de que nos pusieran en seguimiento, y que por tanto trae un cero caducado.
     * Sin esta guarda, ese cero apagaba el tiempo real nada más encenderlo. */
    private const val STALE_ZERO_MS = 10_000L

    /** [seconds] = lo que el servidor dice que queda de seguimiento; 0 lo corta. */
    fun update(seconds: Int) {
        val now = System.currentTimeMillis()
        if (seconds <= 0 && now - startedAt < STALE_ZERO_MS) return
        if (seconds > 0 && _until.value <= now) startedAt = now
        _until.value = if (seconds > 0) now + seconds * 1000L else 0L
    }
}
