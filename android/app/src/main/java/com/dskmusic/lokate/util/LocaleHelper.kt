package com.dskmusic.lokate.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Idioma de la app: "es"/"en" fuerza ese idioma, "auto" deja pasar el Context tal cual (sigue
 * el idioma del sistema). Sin AppCompat: en vez de depender de esa librería solo para esto,
 * se envuelve el Context con su propia Configuration — funciona igual en cualquier API desde
 * la 26 (minSdk de esta app).
 */
object LocaleHelper {
    fun wrap(context: Context, language: String): Context {
        if (language == "auto" || language.isBlank()) return context
        val locale = Locale(language)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
