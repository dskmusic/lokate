package com.dskmusic.lokate.data.remote

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

data class NominatimResultDto(
    val display_name: String,
    val lat: String,
    val lon: String,
)

/** El desglose de la dirección (addressdetails=1). Es lo que permite enseñar "Calle Mayor 12"
 * en grande y el resto —barrio, ciudad, código postal— debajo, en vez del chorizo entero de
 * display_name. Cada campo falta cuando no aplica: en un pueblo no hay suburb, y en medio del
 * campo no hay road. */
data class NominatimAddressPartsDto(
    val house_number: String? = null,
    val road: String? = null,
    val neighbourhood: String? = null,
    val suburb: String? = null,
    val village: String? = null,
    val town: String? = null,
    val city: String? = null,
)

/** Lo que devuelve "reverse" (de un punto a una dirección). display_name viene null cuando
 * Nominatim no conoce nada de ese sitio — en medio del mar, por ejemplo —, y entonces contesta
 * con un "error" en vez de con la dirección. */
data class NominatimAddressDto(
    val display_name: String? = null,
    val address: NominatimAddressPartsDto? = null,
    val error: String? = null,
) {
    /** La primera línea: calle y número ("Calle Mayor 12"). Sin portal, la calle sola; sin
     * calle, lo más fino que conozca de ese sitio (barrio, pueblo, ciudad) — que es la respuesta
     * aproximada cuando donde se ha pulsado no hay una dirección exacta. null = no sabe nada. */
    val streetLine: String?
        get() {
            val a = address ?: return null
            val calle = a.road ?: a.neighbourhood ?: a.suburb ?: a.village ?: a.town ?: a.city
            return when {
                calle == null -> null
                a.house_number == null -> calle
                else -> "$calle ${a.house_number}"
            }
        }
}

interface NominatimService {
    /** [viewbox] es "lonOeste,latNorte,lonEste,latSur" — el trozo de mundo que se está mirando.
     * Con bounded=1 Nominatim solo contesta lo que cae dentro, que es lo que hace que buscar
     * "McDonald's" mirando a Irlanda devuelva el de Irlanda y no el de Kentucky. Retrofit se
     * salta los parámetros a null, así que sin ellos la búsqueda es la de siempre. */
    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 5,
        @Query("viewbox") viewbox: String? = null,
        @Query("bounded") bounded: Int? = null,
    ): List<NominatimResultDto>

    /** La dirección de un punto del mapa. zoom=18 es el detalle de número de portal; con menos
     * contesta el barrio o la calle, que es justo lo que se quiere cuando no hay portal. */
    @GET("reverse")
    suspend fun reverse(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("format") format: String = "json",
        @Query("zoom") zoom: Int = 18,
        @Query("addressdetails") addressDetails: Int = 1,
    ): NominatimAddressDto
}

fun createNominatimService(): NominatimService =
    Retrofit.Builder()
        .baseUrl(com.dskmusic.lokate.util.Constants.NOMINATIM_BASE_URL)
        .client(NetworkModule.createNominatimClient())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(NominatimService::class.java)
