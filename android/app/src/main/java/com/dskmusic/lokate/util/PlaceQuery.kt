package com.dskmusic.lokate.util

import com.dskmusic.lokate.data.remote.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLDecoder

/** Coordenadas reconocidas en el texto del buscador. */
data class Coordinates(val lat: Double, val lng: Double)

/** Lo que ha salido de interpretar el texto del buscador: o son coordenadas y no hay que
 * preguntar a nadie, o queda un texto con el que buscar por nombre. */
data class ResolvedQuery(val coordinates: Coordinates?, val searchText: String)

/**
 * Traduce a un punto del mapa lo que se pega desde otras apps, para no mandárselo tal cual a
 * Nominatim (que con un enlace no encuentra nada). Reconoce:
 *  - coordenadas sueltas: `40.4168, -3.7038`, `40,4168 -3,7038`, separadas por `,`, `;` o espacios
 *  - enlaces de Google Maps: `!3d…!4d…` (el punto exacto del sitio), `?q=`/`?ll=`/`&query=`
 *    y `/@lat,lng,17z`
 *  - `geo:40.4,-3.7`, enlaces de OpenStreetMap (`#map=17/lat/lng`) y de Apple Maps (`?ll=`)
 *  - enlaces cortos de "compartir" (`maps.app.goo.gl/…`): solo se sabe a dónde apuntan siguiendo
 *    la redirección, así que eso necesita red
 *
 * Si el enlace no llevaba coordenadas pero sí el nombre del sitio (`/place/Nombre/`), devuelve ese
 * nombre para buscarlo por texto en vez de la URL entera.
 *
 * ponytail: no entiende grados/minutos/segundos (`40°24'59"N`) porque ni Google Maps ni Lokate
 * copian así; si alguna app de las que usáis lo hace, se añade otro regex y listo.
 */
object PlaceQuery {

    private val GOOGLE_PLACE_POINT = Regex("""!3d(-?\d{1,3}(?:\.\d+)?)!4d(-?\d{1,3}(?:\.\d+)?)""")
    private val GEO_POINT = Regex("""geo:(-?\d{1,3}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val OSM_POINT = Regex("""#map=\d+(?:\.\d+)?/(-?\d{1,3}(?:\.\d+)?)/(-?\d{1,3}(?:\.\d+)?)""")
    private val URL_PARAM_POINT = Regex(
        """[?&](?:q|query|ll|sll|daddr|saddr|center|destination)=(-?\d{1,3}(?:\.\d+)?)(?:,|%2C|\+|\s)+(-?\d{1,3}(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE,
    )
    private val AT_POINT = Regex("""@(-?\d{1,3}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)""")
    private val NUMBER = Regex("""-?\d{1,3}(?:[.,]\d+)?""")
    private val URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val GOOGLE_PLACE_NAME = Regex("""/place/([^/@?]+)""")

    /** Separadores admitidos entre dos coordenadas pegadas a pelo. */
    private const val SEPARATORS = " ,;/|\t\n"

    /** Sin red: lo que se puede reconocer mirando solo el texto. */
    fun parse(text: String): Coordinates? =
        point(GOOGLE_PLACE_POINT, text)
            ?: point(GEO_POINT, text)
            ?: point(OSM_POINT, text)
            ?: point(URL_PARAM_POINT, text)
            ?: point(AT_POINT, text)
            ?: bareCoordinates(text)

    /** Con red, y solo si hace falta: un enlace corto no dice nada hasta seguir su redirección. */
    suspend fun resolve(text: String): ResolvedQuery {
        parse(text)?.let { return ResolvedQuery(it, text) }

        val url = URL.find(text)?.value ?: return ResolvedQuery(null, text)
        val expanded = expand(url) ?: return ResolvedQuery(null, text)
        parse(expanded)?.let { return ResolvedQuery(it, text) }
        return ResolvedQuery(null, placeName(expanded) ?: text)
    }

    private fun point(regex: Regex, text: String): Coordinates? =
        regex.find(text)?.let { coordinates(it.groupValues[1], it.groupValues[2]) }

    /** Dos números y nada más que separadores: descarta de paso cosas como "Calle 40, 3". */
    private fun bareCoordinates(text: String): Coordinates? {
        val trimmed = text.trim()
        if (trimmed.any { it.isLetter() }) return null
        val numbers = NUMBER.findAll(trimmed).map { it.value }.toList()
        if (numbers.size != 2) return null
        if (NUMBER.replace(trimmed, "").any { it !in SEPARATORS }) return null
        return coordinates(numbers[0], numbers[1])
    }

    /** La coma como separador decimal es lo normal al copiar desde una app en español. */
    private fun coordinates(lat: String, lng: String): Coordinates? {
        val latValue = lat.replace(',', '.').toDoubleOrNull() ?: return null
        val lngValue = lng.replace(',', '.').toDoubleOrNull() ?: return null
        return if (latValue in -90.0..90.0 && lngValue in -180.0..180.0) {
            Coordinates(latValue, lngValue)
        } else {
            null
        }
    }

    /** El mismo cliente que Nominatim: ya trae User-Agent propio y timeouts de 15s. */
    private val client by lazy { NetworkModule.createNominatimClient() }

    private suspend fun expand(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            // OkHttp sigue las redirecciones por defecto: response.request es ya la última.
            client.newCall(Request.Builder().url(url).build()).execute()
                .use { it.request.url.toString() }
        }.getOrNull()
    }

    private fun placeName(url: String): String? =
        GOOGLE_PLACE_NAME.find(url)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it.replace('+', ' '), "UTF-8") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
}
