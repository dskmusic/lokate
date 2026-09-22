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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.absoluteAvatarUrl
import com.dskmusic.lokate.data.remote.dto.LocationDto
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.ui.common.ListSearchField
import com.dskmusic.lokate.ui.common.UpdateStatusDialog
import com.dskmusic.lokate.ui.common.isOverdue
import com.dskmusic.lokate.ui.common.rememberMyLocation
import com.dskmusic.lokate.ui.common.updateModeShortLabel
import com.dskmusic.lokate.ui.map.MapViewModel
import com.dskmusic.lokate.util.MediaSaver
import com.dskmusic.lokate.util.distanceMeters
import com.dskmusic.lokate.util.formatDistance
import com.dskmusic.lokate.util.parseIsoDate
import com.dskmusic.lokate.util.formatRelativeTime
import kotlinx.coroutines.launch

private enum class PeopleSort { NAME, DISTANCE, RECENT }

/** Un miembro del grupo con su última posición, si la hay. Sin posición también sale en la
 * lista: es justo cuando hace falta entrar en su ficha a pedírsela. */
private data class Person(
    val id: String,
    val displayName: String,
    val avatarUrl: String?,
    val location: LocationDto?,
)

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
    var photoMember by remember { mutableStateOf<Person?>(null) }
    var sort by remember { mutableStateOf(PeopleSort.NAME) }
    // null = buscador cerrado; "" = abierto y vacío.
    var query by remember { mutableStateOf<String?>(null) }
    val myLocation = rememberMyLocation()

    val members = remember(state.members, state.groupMembers, sort, myLocation, query) {
        val byId = state.members.associateBy { it.user_id }
        // La lista manda la del grupo; las posiciones solo rellenan. Si aún no ha llegado (o
        // falló), se tira de las posiciones para no dejar la pantalla vacía.
        val all = if (state.groupMembers.isEmpty()) {
            state.members.map { Person(it.user_id, it.display_name, it.avatar_url, it) }
        } else {
            state.groupMembers.map { Person(it.id, it.display_name, it.avatar_url, byId[it.id]) }
        }
        val text = query?.trim().orEmpty()
        val found = if (text.isEmpty()) all
        else all.filter { it.displayName.contains(text, ignoreCase = true) }
        when (sort) {
            PeopleSort.NAME -> found.sortedBy { it.displayName.lowercase() }
            PeopleSort.RECENT -> found.sortedByDescending {
                it.location?.let { l -> parseIsoDate(l.timestamp)?.time } ?: 0L
            }
            // Quien no tiene posición no tiene distancia: al final de la lista.
            PeopleSort.DISTANCE -> myLocation?.let { me ->
                found.sortedBy {
                    it.location?.let { l -> distanceMeters(me.latitude, me.longitude, l.lat, l.lng) }
                        ?: Float.MAX_VALUE
                }
            } ?: found
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val text = query
                    if (text == null) Text(stringResource(R.string.people_title))
                    else ListSearchField(text) { query = it }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    // Refrescar a todos: pide ubicacion fresca a todo el grupo de una vez, sin
                    // tener que entrar en la ficha de cada uno.
                    if (state.refreshingAll) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    } else {
                        IconButton(onClick = { viewModel.refreshAll() }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.people_refresh_all),
                            )
                        }
                    }
                    IconButton(onClick = { query = if (query == null) "" else null }) {
                        Icon(
                            if (query == null) Icons.Filled.Search else Icons.Filled.Close,
                            contentDescription = stringResource(
                                if (query == null) R.string.place_search_action else R.string.list_search_close,
                            ),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }

        if (members.isEmpty()) {
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
            if (state.refreshingAll) {
                Text(
                    stringResource(R.string.location_request_in_progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(members, key = { it.id }) { member ->
                    PersonRow(
                        member = member,
                        distance = myLocation?.let { me ->
                            member.location?.let { distanceMeters(me.latitude, me.longitude, it.lat, it.lng) }
                        },
                        onClick = { onSelectMember(member.id) },
                        onAvatarClick = { photoMember = member },
                        onOpenDetail = { onOpenDetail(member.id) },
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
    member: Person,
    distance: Float?,
    onClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    var showUpdateInfo by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = absoluteAvatarUrl(member.avatarUrl),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onAvatarClick),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(member.displayName, style = MaterialTheme.typography.titleMedium)
            val location = member.location
            if (location == null) {
                // Sin posición no hay batería, hora ni wifi que enseñar: queda entrar en la
                // ficha y pedirle una con el botón de actualizar.
                Spacer(Modifier.width(2.dp))
                Text(stringResource(R.string.people_no_location), style = MaterialTheme.typography.bodySmall)
            } else {
                Spacer(Modifier.width(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        if (location.is_charging == true) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        location.battery_level?.let { "$it%" } ?: stringResource(R.string.battery_unknown),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("·", style = MaterialTheme.typography.bodySmall)
                    // La hora y el porqué van juntos y se tocan juntos: "7 min" a secas deja con
                    // la duda de si eso es normal teniendo puesto "cada 30 segundos" (lo es: en
                    // reposo su móvil espacia a 15 min). Subrayado para que se vea que se toca.
                    val modeLabel = updateModeShortLabel(location)?.let { " · ${stringResource(it)}" }.orEmpty()
                    Text(
                        formatRelativeTime(location.timestamp) + modeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        textDecoration = TextDecoration.Underline,
                        color = if (isOverdue(location)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.clickable { showUpdateInfo = true },
                    )
                    // Alguien lo tiene en seguimiento en vivo: su móvil está en tiempo real
                    // ahora mismo, tenga puesto lo que tenga puesto en sus ajustes.
                    if (location.live_seconds > 0) {
                        Text("·", style = MaterialTheme.typography.bodySmall)
                        Text(
                            stringResource(R.string.frequency_short_live),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
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
                        if (location.wifi_connected == true) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        if (location.wifi_connected == true) location.wifi_ssid ?: stringResource(R.string.wifi_connected_label)
                        else stringResource(R.string.wifi_not_connected_label),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (showUpdateInfo) {
                    UpdateStatusDialog(location, onDismiss = { showUpdateInfo = false })
                }
            }
        }
        IconButton(onClick = onOpenDetail) {
            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.people_open_detail))
        }
    }
}

@Composable
private fun AvatarPreviewDialog(member: Person, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    val avatarUrl = absoluteAvatarUrl(member.avatarUrl)

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
                            val file = MediaSaver.downloadToCache(context, url, "${member.displayName}.jpg")
                            MediaSaver.saveToDevice(context, file, "image/jpeg", "${member.displayName}.jpg")
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
