package com.dskmusic.lokate.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dskmusic.lokate.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * El usuario ha descartado el aviso de "actualiza la app" sin actualizar. Se lo cuenta al
 * servidor, que avisa a los administradores.
 *
 * ponytail: es lo unico que se puede saber del descarte, y solo cuando la notificacion la dibujo
 * la app (si algun dia el aviso pasara a pintarlo el sistema, este receptor deja de recibir).
 * Si el envio falla no se reintenta: no vale la pena un WorkManager para un dato informativo.
 */
class UpdateNoticeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val locator = ServiceLocator.getInstance(context.applicationContext)
        if (!locator.authRepository.isLoggedIn()) return
        // goAsync: onReceive vuelve enseguida y el proceso podria morir a mitad de la llamada.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                locator.authRepository.reportUpdateNoticeStatus("dismissed")
            } finally {
                pending.finish()
            }
        }
    }
}
