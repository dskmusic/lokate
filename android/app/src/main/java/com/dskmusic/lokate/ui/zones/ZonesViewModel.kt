package com.dskmusic.lokate.ui.zones

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.lokate.data.remote.dto.ZoneNotificationPrefDto
import com.dskmusic.lokate.data.repository.ZoneRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ZonesViewModel(private val zoneRepository: ZoneRepository) : ViewModel() {

    val zones = zoneRepository.observeZones()

    private val _prefs = MutableStateFlow<Map<String, ZoneNotificationPrefDto>>(emptyMap())
    val prefs: StateFlow<Map<String, ZoneNotificationPrefDto>> = _prefs

    init {
        viewModelScope.launch { runCatching { zoneRepository.refresh() } }
        refreshPrefs()
    }

    private fun refreshPrefs() {
        viewModelScope.launch {
            val list = runCatching { zoneRepository.notificationPrefs() }.getOrDefault(emptyList())
            _prefs.value = list.associateBy { it.zone_id }
        }
    }

    fun deleteZone(id: String) {
        viewModelScope.launch { runCatching { zoneRepository.deleteZone(id) } }
    }

    fun setNotifyOnEnter(zoneId: String, value: Boolean) {
        val current = _prefs.value[zoneId]
        val notifyOnExit = current?.notify_on_exit ?: false
        updatePref(zoneId, value, notifyOnExit)
    }

    fun setNotifyOnExit(zoneId: String, value: Boolean) {
        val current = _prefs.value[zoneId]
        val notifyOnEnter = current?.notify_on_enter ?: false
        updatePref(zoneId, notifyOnEnter, value)
    }

    private fun updatePref(zoneId: String, notifyOnEnter: Boolean, notifyOnExit: Boolean) {
        viewModelScope.launch {
            runCatching { zoneRepository.updateNotificationPref(zoneId, notifyOnEnter, notifyOnExit) }
                .onSuccess { updated -> _prefs.value = _prefs.value + (zoneId to updated) }
        }
    }
}
