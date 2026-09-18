package com.dskmusic.lokate.data.remote

import com.dskmusic.lokate.BuildConfig

/**
 * URL del backend en uso ahora mismo, en memoria. Retrofit se construye una sola vez con la URL
 * por defecto; [DynamicBaseUrlInterceptor] reescribe cada petición contra este valor, así que
 * cambiarlo desde Ajustes se aplica de inmediato, sin reiniciar la app.
 */
object ServerConfig {
    @Volatile
    var baseUrl: String = BuildConfig.API_BASE_URL
        private set

    fun update(url: String?) {
        val trimmed = url?.trim().orEmpty()
        baseUrl = when {
            trimmed.isBlank() -> BuildConfig.API_BASE_URL
            trimmed.endsWith("/") -> trimmed
            else -> "$trimmed/"
        }
    }
}
