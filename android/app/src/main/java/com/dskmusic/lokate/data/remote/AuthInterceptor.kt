package com.dskmusic.lokate.data.remote

import com.dskmusic.lokate.data.prefs.SessionManager
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val session: SessionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = session.token ?: return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().addHeader("Authorization", "Bearer $token").build(),
        )
    }
}
