package com.dskmusic.lokate.ui.auth

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.lokate.data.repository.AuthRepository
import com.dskmusic.lokate.data.repository.BackupRepository
import com.dskmusic.lokate.util.FileUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null,
    /** Fecha ISO de la copia de ajustes que este usuario tiene en el servidor. Mientras no sea
     * null la pantalla pregunta si restaurarla, y la entrada a la app espera a la respuesta:
     * es el único momento en que ofrecerla tiene sentido (móvil nuevo o recién reinstalado). */
    val backupDate: String? = null,
)

class AuthViewModel(
    private val authRepository: AuthRepository,
    private val backupRepository: BackupRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState

    /** La navegación de [login], en espera mientras se pregunta por la copia. */
    private var pendingEntry: (() -> Unit)? = null

    fun login(username: String, password: String, onSuccess: () -> Unit) {
        _uiState.value = AuthUiState(loading = true)
        viewModelScope.launch {
            runCatching { authRepository.login(username, password) }
                .onSuccess {
                    // Sin esperar: registrar el token FCM no debe retrasar la entrada a la
                    // app (si Firebase tarda o falla, el usuario no debe notarlo aquí).
                    viewModelScope.launch { runCatching { authRepository.registerCurrentDeviceToken() } }
                    // La copia sí se espera (es una petición corta): preguntar después de haber
                    // entrado significaría preguntar con los ajustes de fábrica ya por medio.
                    val backup = runCatching { backupRepository.fetch() }.getOrNull()
                    if (backup != null && backup.exists) {
                        pendingEntry = onSuccess
                        _uiState.value = AuthUiState(backupDate = backup.updated_at)
                    } else {
                        _uiState.value = AuthUiState()
                        onSuccess()
                    }
                }
                .onFailure { _uiState.value = AuthUiState(error = it.message) }
        }
    }

    /** Restaura la copia y entra. Si falla se entra igual: quedarse fuera por esto sería peor. */
    fun restoreBackupAndEnter() {
        _uiState.value = AuthUiState(loading = true)
        viewModelScope.launch {
            runCatching { backupRepository.restore() }
            enterWithoutRestoring()
        }
    }

    fun enterWithoutRestoring() {
        _uiState.value = AuthUiState()
        pendingEntry?.invoke()
        pendingEntry = null
    }

    fun register(
        username: String,
        password: String,
        displayName: String,
        avatarUri: Uri?,
        cacheContext: android.content.Context,
        onSuccess: () -> Unit,
    ) {
        _uiState.value = AuthUiState(loading = true)
        viewModelScope.launch {
            runCatching {
                authRepository.register(username, password, displayName)
                if (avatarUri != null) {
                    val file = FileUtils.uriToCacheFile(cacheContext, avatarUri)
                    authRepository.uploadAvatar(file)
                }
            }
                .onSuccess {
                    _uiState.value = AuthUiState()
                    viewModelScope.launch { runCatching { authRepository.registerCurrentDeviceToken() } }
                    onSuccess()
                }
                .onFailure { _uiState.value = AuthUiState(error = it.message) }
        }
    }
}
