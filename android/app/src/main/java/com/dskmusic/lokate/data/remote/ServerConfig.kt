package com.dskmusic.lokate.data.remote

import com.dskmusic.lokate.BuildConfig

/**
 * URL del backend en uso ahora mismo, en memoria. Retrofit se construye una sola vez con la URL
 * por defecto; [DynamicBaseUrlInterceptor] reescribe cada petición contra este valor, así que
 * cambiarlo desde Ajustes se aplica de inmediato, sin reiniciar la app.
 */
object ServerConfig {
    /**
     * Relleno para cuando todavía no hay servidor configurado. Retrofit exige una URL válida al
     * construirse aunque luego no salga ninguna petición contra ella: la pantalla de entrar no
     * deja pasar sin una URL de verdad.
     */
    private const val UNSET = "http://localhost/"

    @Volatile
    var baseUrl: String = normalize(BuildConfig.API_BASE_URL)
        private set

    /** false = la app no sabe todavía a qué servidor hablar (compilación sin URL y sin ajuste guardado). */
    val isSet: Boolean get() = baseUrl != UNSET

    fun update(url: String?) {
        baseUrl = normalize(url)
    }

    private fun normalize(url: String?): String {
        var trimmed = url?.trim().orEmpty().ifBlank { BuildConfig.API_BASE_URL.trim() }
        if (trimmed.isBlank()) return UNSET
        // Escrita a mano se teclea "casa.ejemplo.com" sin más; sin esquema el interceptor no
        // sabría reescribir nada y las peticiones se irían al relleno de arriba.
        if (!trimmed.startsWith("http")) trimmed = "https://$trimmed"
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}
