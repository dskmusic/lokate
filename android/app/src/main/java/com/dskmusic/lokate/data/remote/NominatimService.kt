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

interface NominatimService {
    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 5,
    ): List<NominatimResultDto>
}

fun createNominatimService(): NominatimService =
    Retrofit.Builder()
        .baseUrl(com.dskmusic.lokate.util.Constants.NOMINATIM_BASE_URL)
        .client(NetworkModule.createNominatimClient())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(NominatimService::class.java)
