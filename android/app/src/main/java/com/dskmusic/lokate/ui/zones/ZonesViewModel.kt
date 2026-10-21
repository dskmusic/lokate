package com.dskmusic.lokate.ui.zones

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.lokate.data.remote.dto.GroupMemberDto
import com.dskmusic.lokate.data.remote.dto.ZoneNotificationPrefDto
import com.dskmusic.lokate.data.repository.GroupRepository
import com.dskmusic.lokate.data.repository.ZoneRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ZonesViewModel(
    private val zoneRepository: ZoneRepository,
    private val groupRepository: GroupRepository,
) : ViewModel() {

    val zones = zoneRepository.observeZones()

    private val _prefs = MutableStateFlow<Map<String, ZoneNotificationPrefDto>>(emptyMap())
    val prefs: StateFlow<Map<String, ZoneNotificationPrefDto>> = _prefs

    /** ¿Sabemos ya qué avisos tengo puestos? Hasta que no se hayan leído, los interruptores se
     * quedan bloqueados: un mapa vacío se pinta igual que "todo apagado", y el primer toque
     * guardaba ese apagado en el servidor de verdad, llevándose por delante lo que hubiera. */
    private val _prefsLoaded = MutableStateFlow(false)
    val prefsLoaded: StateFlow<Boolean> = _prefsLoaded

    /** Lo que se ve no es lo que hay en el servidor: no se pudo leer, o un cambio no se pudo
     * guardar. Se enseña, porque un aviso que crees puesto y no lo está no avisa de nada. */
    private val _prefsError = MutableStateFlow(false)
    val prefsError: StateFlow<Boolean> = _prefsError

    /** Para poner cara a los miembros de los que me avisa cada zona. */
    private val _members = MutableStateFlow<List<GroupMemberDto>>(emptyList())
    val members: StateFlow<List<GroupMemberDto>> = _members

    init {
        viewModelScope.launch { runCatching { zoneRepository.refresh() } }
        viewModelScope.launch {
            _members.value = runCatching { groupRepository.members() }.getOrDefault(emptyList())
        }
        refreshPrefs()
    }

    fun refreshPrefs() {
        viewModelScope.launch {
            runCatching { zoneRepository.notificationPrefs() }
                .onSuccess { list ->
                    _prefs.value = list.associateBy { it.zone_id }
                    _prefsLoaded.value = true
                    _prefsError.value = false
                }
                .onFailure { _prefsError.value = true }
        }
    }

    fun deleteZone(id: String) {
        viewModelScope.launch { runCatching { zoneRepository.deleteZone(id) } }
    }

    fun setNotifyOnEnter(zoneId: String, value: Boolean) {
        val current = _prefs.value[zoneId]
        updatePref(zoneId, value, current?.notify_on_exit ?: false, current?.watched_user_ids.orEmpty())
    }

    fun setNotifyOnExit(zoneId: String, value: Boolean) {
        val current = _prefs.value[zoneId]
        updatePref(zoneId, current?.notify_on_enter ?: false, value, current?.watched_user_ids.orEmpty())
    }

    private fun updatePref(
        zoneId: String,
        notifyOnEnter: Boolean,
        notifyOnExit: Boolean,
        watchedUserIds: List<String>,
    ) {
        viewModelScope.launch {
            runCatching { zoneRepository.updateNotificationPref(zoneId, notifyOnEnter, notifyOnExit, watchedUserIds) }
                .onSuccess { updated ->
                    _prefs.value = _prefs.value + (zoneId to updated)
                    _prefsError.value = false
                }
                // Antes el fallo se tragaba entero: el interruptor volvía a su sitio y nadie se
                // enteraba de que el aviso no había quedado guardado.
                .onFailure { _prefsError.value = true }
        }
    }
}
