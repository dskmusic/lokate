package com.dskmusic.lokate.data.remote

import android.content.Context
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.prefs.SessionManager
import com.dskmusic.lokate.push.NotificationHelper
import com.dskmusic.lokate.util.Constants
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Un 401 teniendo token guardado solo puede significar una cosa: la sesion ha caducado (el
 * token dura 30 dias y no se renueva solo). Sin avisar, el movil se queda encendiendo el GPS
 * cada pocos minutos para tirar los pings a la basura, y en el grupo esa persona simplemente
 * se congela sin que nadie --ni ella-- sepa por que.
 */
class SessionExpiredInterceptor(
    private val context: Context,
    private val session: SessionManager,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (session.token != null) {
            if (response.code == 401) {
                SessionAlert.onUnauthorized(context)
            } else if (response.isSuccessful) {
                // Cualquier respuesta buena desmiente el aviso: si ya ha vuelto a iniciar sesion,
                // el vigilante de pings puede volver a avisar por su cuenta.
                SessionAlert.clear()
            }
        }
        return response
    }
}

object SessionAlert {
    /** Se sabe que la sesion esta caducada. Lo mira el vigilante de pings del worker para no
     * soltar dos avisos distintos por la misma causa. */
    @Volatile
    var expired = false
        private set

    @Volatile private var lastNotifiedAt = 0L

    fun onUnauthorized(context: Context) {
        expired = true
        val now = System.currentTimeMillis()
        // El aviso se repite de vez en cuando (se puede descartar sin leerlo), pero no en cada
        // ping: en tiempo real serian veinte notificaciones por minuto.
        if (now - lastNotifiedAt < REPEAT_MS) return
        lastNotifiedAt = now
        NotificationHelper.showLocalAlert(
            context,
            Constants.SESSION_EXPIRED_NOTIFICATION_ID,
            context.getString(R.string.session_expired_title),
            context.getString(R.string.session_expired_body),
        )
    }

    fun clear() {
        expired = false
        lastNotifiedAt = 0L
    }

    private const val REPEAT_MS = 12 * 3600_000L
}
