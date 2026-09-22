package com.dskmusic.lokate.ui.member

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.data.repository.AdminRepository
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
    /** Wifi que un admin acaba de mandar a las "wifis de casa" de este miembro, para avisar. */
    val knownWifiAdded: String? = null,
    /** Sus "wifis de casa" según la última copia en la nube, para no mandarle una que ya tiene.
     * null = todavía no se ha mirado o no hay copia (entonces no se puede saber). */
    val knownWifis: List<String>? = null,
    val error: String? = null,
)

class MemberDetailViewModel(
    private val locationRepository: LocationRepository,
    private val messageRepository: MessageRepository,
    private val adminRepository: AdminRepository,
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

    /** Para la alarma en el dispositivo del miembro (push "stop_ring"). Cierra el diálogo de
     * progreso al instante, sin esperar al servidor: el sonido ya está sonando allí y reabrirlo
     * por un fallo de red confundiría más que ayudar — el error se muestra igual debajo. */
    fun stopRing() {
        _uiState.value = _uiState.value.copy(ringing = false, ringSent = false)
        viewModelScope.launch {
            runCatching { locationRepository.stopRing(userId) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    /** Solo admins: qué wifis de casa tiene ya, para no volver a mandarle la misma. Sale de su
     * copia en la nube, que su móvil sube al aplicar uno de estos cambios. */
    fun loadKnownWifi() {
        viewModelScope.launch {
            val response = runCatching { adminRepository.knownWifi(userId) }.getOrNull()
            // Sin copia (o sin respuesta) se queda en null: "no se sabe" no es "no la tiene".
            _uiState.value = _uiState.value.copy(knownWifis = response?.ssids?.takeIf { response.known })
        }
    }

    /** Solo admins: mete la wifi a la que está conectado ahora mismo en sus "wifis de casa".
     * El servidor se lo manda por push a su móvil, que es donde vive esa lista. */
    fun addKnownWifi(ssid: String) {
        viewModelScope.launch {
            runCatching { adminRepository.addKnownWifi(userId, ssid) }
                .onSuccess { _uiState.value = _uiState.value.copy(knownWifiAdded = ssid) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
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
