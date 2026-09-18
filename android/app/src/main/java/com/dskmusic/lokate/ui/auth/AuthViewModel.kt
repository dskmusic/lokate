package com.dskmusic.lokate.ui.auth

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.lokate.data.repository.AuthRepository
import com.dskmusic.lokate.util.FileUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null,
)

class AuthViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState

    fun login(username: String, password: String, onSuccess: () -> Unit) {
        _uiState.value = AuthUiState(loading = true)
        viewModelScope.launch {
            runCatching { authRepository.login(username, password) }
                .onSuccess {
                    _uiState.value = AuthUiState()
                    // Sin esperar: registrar el token FCM no debe retrasar la entrada a la
                    // app (si Firebase tarda o falla, el usuario no debe notarlo aquí).
                    viewModelScope.launch { runCatching { authRepository.registerCurrentDeviceToken() } }
                    onSuccess()
                }
                .onFailure { _uiState.value = AuthUiState(error = it.message) }
        }
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
