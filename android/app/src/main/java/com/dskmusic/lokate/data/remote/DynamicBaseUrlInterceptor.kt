package com.dskmusic.lokate.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/** Reescribe scheme/host/puerto de cada petición contra [ServerConfig.baseUrl], manteniendo la ruta relativa que generó Retrofit. */
class DynamicBaseUrlInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val configured = ServerConfig.baseUrl.toHttpUrlOrNull() ?: return chain.proceed(request)

        val newUrl = request.url.newBuilder()
            .scheme(configured.scheme)
            .host(configured.host)
            .port(configured.port)
            .build()

        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}
