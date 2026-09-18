package com.dskmusic.lokate.util

import com.dskmusic.lokate.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Resultado de comprobar si hay una versión nueva del APK en el servidor (ver
 * AuthRepository.checkForUpdate). [com.dskmusic.lokate.ui.map.MapScreen] dispara [check] cada
 * vez que la app pasa a primer plano (no solo al arrancar en frío: Android no mata el proceso
 * al "cerrarla" con atrás/inicio, así que un arranque en frío real es poco frecuente) — y
 * muestra el aviso cada vez que [updateAvailable] sea true, sin recordar que ya se cerró antes:
 * mientras el servidor siga marcando que hay actualización, se vuelve a preguntar en cada
 * apertura, hasta que se instale o hasta que se apague la bandera en el servidor. */
object UpdateCheckState {

    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable

    suspend fun check(authRepository: AuthRepository) {
        _updateAvailable.value = authRepository.checkForUpdate()
    }
}
