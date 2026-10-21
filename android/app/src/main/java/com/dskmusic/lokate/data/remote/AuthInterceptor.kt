package com.dskmusic.lokate.data.remote

import com.dskmusic.lokate.data.prefs.SessionManager
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val session: SessionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = session.token ?: return chain.proceed(request)
        val response = chain.proceed(
            request.newBuilder().addHeader("Authorization", "Bearer $token").build(),
        )
        // Token deslizante: cuando al JWT le queda poco, el servidor devuelve otro nuevo en esta
        // cabecera (ver backend/app/auth.py). Guardarlo aqui es lo que evita que la sesion de un
        // movil vigilado caduque a los 30 dias y deje de mandar posiciones sin que nadie se
        // entere. Si la cabecera no viene (servidor antiguo), no pasa nada: sigue el de siempre.
        response.header(REFRESH_HEADER)?.takeIf { it.isNotBlank() }?.let { session.token = it }
        return response
    }

    private companion object {
        const val REFRESH_HEADER = "X-Refresh-Token"
    }
}
