package com.dskmusic.lokate.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.absoluteAvatarUrl
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.ui.common.rememberMyLocation
import com.dskmusic.lokate.ui.map.MapViewModel
import com.dskmusic.lokate.util.MediaSaver
import com.dskmusic.lokate.util.distanceMeters
import com.dskmusic.lokate.util.formatDistance
import com.dskmusic.lokate.util.parseIsoDate
import com.dskmusic.lokate.util.formatRelativeTime
import kotlinx.coroutines.launch

private enum class PeopleSort { NAME, DISTANCE, RECENT }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    locator: ServiceLocator,
    onBack: () -> Unit,
    onSelectMember: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val viewModel = remember { MapViewModel(locator.locationRepository, locator.zoneRepository, locator.groupRepository) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var photoMember by remember { mutableStateOf<LocationDto?>(null) }
    var sort by remember { mutableStateOf(PeopleSort.NAME) }
    val myLocation = rememberMyLocation()

    val members = remember(state.members, sort, myLocation) {
        when (sort) {
            PeopleSort.NAME -> state.members.sortedBy { it.display_name.lowercase() }
            PeopleSort.RECENT -> state.members.sortedByDescending { parseIsoDate(it.timestamp)?.time ?: 0L }
            PeopleSort.DISTANCE -> myLocation?.let { me ->
                state.members.sortedBy { distanceMeters(me.latitude, me.longitude, it.lat, it.lng) }
            } ?: state.members
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.people_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }

        if (state.members.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.people_empty))
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PeopleSort.entries.forEach { option ->
                    FilterChip(
                        selected = sort == option,
                        // Sin saber dónde estás no hay nada que ordenar por cercanía.
                        enabled = option != PeopleSort.DISTANCE || myLocation != null,
                        onClick = { sort = option },
                        label = {
                            Text(
                                stringResource(
                                    when (option) {
                                        PeopleSort.NAME -> R.string.people_sort_name
                                        PeopleSort.DISTANCE -> R.string.people_sort_distance
                                        PeopleSort.RECENT -> R.string.people_sort_recent
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(members, key = { it.user_id }) { member ->
                    PersonRow(
                        member = member,
                        distance = myLocation?.let { distanceMeters(it.latitude, it.longitude, member.lat, member.lng) },
                        onClick = { onSelectMember(member.user_id) },
                        onAvatarClick = { photoMember = member },
                        onOpenDetail = { onOpenDetail(member.user_id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    photoMember?.let { member ->
        AvatarPreviewDialog(member = member, onDismiss = { photoMember = null })
    }
}

@Composable
private fun PersonRow(
    member: LocationDto,
    distance: Float?,
    onClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = absoluteAvatarUrl(member.avatar_url),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onAvatarClick),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(member.display_name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    if (member.is_charging == true) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    member.battery_level?.let { "$it%" } ?: stringResource(R.string.battery_unknown),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("·", style = MaterialTheme.typography.bodySmall)
                Text(formatRelativeTime(member.timestamp), style = MaterialTheme.typography.bodySmall)
            }
            distance?.let {
                Spacer(Modifier.width(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.distance_from_you, formatDistance(it)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.width(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    if (member.wifi_connected == true) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    if (member.wifi_connected == true) member.wifi_ssid ?: stringResource(R.string.wifi_connected_label)
                    else stringResource(R.string.wifi_not_connected_label),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        IconButton(onClick = onOpenDetail) {
            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.people_open_detail))
        }
    }
}

@Composable
private fun AvatarPreviewDialog(member: LocationDto, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    val avatarUrl = absoluteAvatarUrl(member.avatar_url)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.people_photo_view_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(240.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                )
                if (saving) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
                saveMessage?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = {
                    val url = avatarUrl ?: return@OutlinedButton
                    saving = true
                    scope.launch {
                        val ok = runCatching {
                            val file = MediaSaver.downloadToCache(context, url, "${member.display_name}.jpg")
                            MediaSaver.saveToDevice(context, file, "image/jpeg", "${member.display_name}.jpg")
                        }.getOrDefault(false)
                        saveMessage = context.getString(if (ok) R.string.emergency_media_saved else R.string.emergency_media_error)
                        saving = false
                    }
                },
                enabled = avatarUrl != null && !saving,
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.emergency_save_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
