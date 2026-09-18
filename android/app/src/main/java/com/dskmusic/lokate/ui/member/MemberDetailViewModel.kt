package com.dskmusic.lokate.ui.member

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.data.repository.LocationRepository
import com.dskmusic.lokate.data.repository.MessageRepository
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class LocationRequestResult { SUCCESS, FAILURE }

private const val LOCATION_REQUEST_POLL_INTERVAL_MS = 2_500L
private const val LOCATION_REQUEST_TIMEOUT_MS = 20_000L

data class MemberDetailUiState(
    val loading: Boolean = true,
    val location: LocationDto? = null,
    val requestingLocation: Boolean = false,
    val locationRequestResult: LocationRequestResult? = null,
    val ringing: Boolean = false,
    val ringSent: Boolean = false,
    val sendingMessage: Boolean = false,
    val messageSent: Boolean = false,
    val error: String? = null,
)

class MemberDetailViewModel(
    private val locationRepository: LocationRepository,
    private val messageRepository: MessageRepository,
    private val userId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemberDetailUiState())
    val uiState: StateFlow<MemberDetailUiState> = _uiState

    init {
        loadCached()
    }

    /** Carga silenciosa inicial: lo último que ya sabía el servidor, sin pedir nada al
     * dispositivo del miembro (para eso está requestFreshLocation). */
    private fun loadCached() {
        viewModelScope.launch {
            val member = runCatching { locationRepository.groupLatest() }.getOrNull()?.find { it.user_id == userId }
            _uiState.value = _uiState.value.copy(loading = false, location = member ?: _uiState.value.location)
        }
    }

    /** Pide al dispositivo de este miembro una ubicación fresca (push silencioso) y sondea el
     * servidor hasta que llegue una posición más reciente que la que ya teníamos, o hasta
     * agotar el tiempo de espera — ver LOCATION_REQUEST_TIMEOUT_MS. */
    fun requestFreshLocation() {
        _uiState.value = _uiState.value.copy(requestingLocation = true, locationRequestResult = null)
        viewModelScope.launch {
            val previousTimestamp = _uiState.value.location?.timestamp
            val sent = runCatching { locationRepository.requestLocation(userId) }.isSuccess

            var freshMember: LocationDto? = null
            if (sent) {
                val deadline = System.currentTimeMillis() + LOCATION_REQUEST_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline && freshMember == null) {
                    delay(LOCATION_REQUEST_POLL_INTERVAL_MS)
                    val members = runCatching { locationRepository.groupLatest() }.getOrNull()
                    freshMember = members?.find { it.user_id == userId && it.timestamp != previousTimestamp }
                }
            }

            _uiState.value = if (freshMember != null) {
                _uiState.value.copy(
                    requestingLocation = false,
                    location = freshMember,
                    locationRequestResult = LocationRequestResult.SUCCESS,
                )
            } else {
                _uiState.value.copy(requestingLocation = false, locationRequestResult = LocationRequestResult.FAILURE)
            }
        }
    }

    fun clearLocationRequestResult() {
        _uiState.value = _uiState.value.copy(locationRequestResult = null)
    }

    fun ringDevice() {
        _uiState.value = _uiState.value.copy(ringing = true, ringSent = false)
        viewModelScope.launch {
            runCatching { locationRepository.ringDevice(userId) }
                .onSuccess { _uiState.value = _uiState.value.copy(ringing = false, ringSent = true) }
                .onFailure { _uiState.value = _uiState.value.copy(ringing = false, error = it.message) }
        }
    }

    /** Mensaje de emergencia: el backend lo entrega con prioridad forzada, sin mirar preferencias del destinatario. */
    fun sendEmergencyMessage(text: String, attachment: File?, attachmentMimeType: String?) {
        _uiState.value = _uiState.value.copy(sendingMessage = true, messageSent = false)
        viewModelScope.launch {
            runCatching { messageRepository.sendEmergencyMessage(userId, text, attachment, attachmentMimeType) }
                .onSuccess { _uiState.value = _uiState.value.copy(sendingMessage = false, messageSent = true) }
                .onFailure { _uiState.value = _uiState.value.copy(sendingMessage = false, error = it.message) }
        }
    }
}
