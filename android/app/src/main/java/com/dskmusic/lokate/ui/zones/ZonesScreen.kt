package com.dskmusic.lokate.ui.zones

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.absoluteAvatarUrl
import com.dskmusic.lokate.data.remote.dto.ZoneDto
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.ui.common.ListSearchField
import com.dskmusic.lokate.ui.common.rememberMyLocation
import com.dskmusic.lokate.util.distanceMeters
import com.dskmusic.lokate.util.parseIsoDate

private enum class ZoneSort { NAME, DISTANCE, RECENT }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ZonesScreen(
    locator: ServiceLocator,
    onAddZone: () -> Unit,
    onEditZone: (String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = remember { ZonesViewModel(locator.zoneRepository, locator.groupRepository) }
    val allZones by viewModel.zones.collectAsStateWithLifecycle(initialValue = emptyList())
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    var zoneToDelete by remember { mutableStateOf<ZoneDto?>(null) }
    var sort by remember { mutableStateOf(ZoneSort.NAME) }
    // null = buscador cerrado; "" = abierto y vacío.
    var query by remember { mutableStateOf<String?>(null) }
    val myLocation = rememberMyLocation()

    val zones = remember(allZones, sort, myLocation, query) {
        val text = query?.trim().orEmpty()
        val found = if (text.isEmpty()) allZones
        else allZones.filter { it.name.contains(text, ignoreCase = true) }
        when (sort) {
            ZoneSort.NAME -> found.sortedBy { it.name.lowercase() }
            ZoneSort.RECENT -> found.sortedByDescending { z ->
                z.created_at?.let { parseIsoDate(it)?.time } ?: 0L
            }
            ZoneSort.DISTANCE -> myLocation?.let { me ->
                found.sortedBy { distanceMeters(me.latitude, me.longitude, it.lat, it.lng) }
            } ?: found
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val text = query
                    if (text == null) Text(stringResource(R.string.zones_title))
                    else ListSearchField(text) { query = it }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
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
        floatingActionButton = {
            FloatingActionButton(onClick = onAddZone) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.zone_add))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZoneSort.entries.forEach { option ->
                    FilterChip(
                        selected = sort == option,
                        // Sin saber dónde estás no hay nada que ordenar por cercanía.
                        enabled = option != ZoneSort.DISTANCE || myLocation != null,
                        onClick = { sort = option },
                        label = {
                            Text(
                                stringResource(
                                    when (option) {
                                        ZoneSort.NAME -> R.string.people_sort_name
                                        ZoneSort.DISTANCE -> R.string.people_sort_distance
                                        ZoneSort.RECENT -> R.string.people_sort_recent
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(zones, key = { it.id }) { zone ->
                    val pref = prefs[zone.id]
                    // Lista vacía en el servidor = me avisa de todo el grupo.
                    val watched = remember(pref?.watched_user_ids, members) {
                        val ids = pref?.watched_user_ids.orEmpty()
                        if (ids.isEmpty()) members else members.filter { it.id in ids }
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable { onEditZone(zone.id) }
                                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                        ) {
                            // El nombre manda: linea propia a todo el ancho, y si no cabe, puntos suspensivos.
                            Text(
                                zone.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp, bottom = 8.dp),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(40.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.LocationOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                                Text(
                                    if (zone.is_public) "${zone.radius_m.toInt()} m"
                                    else stringResource(R.string.zone_private_badge, zone.radius_m.toInt()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                                )
                                ZoneNotifyToggle(
                                    icon = Icons.Filled.Login,
                                    enabled = pref?.notify_on_enter ?: false,
                                    contentDescription = stringResource(R.string.zone_notify_enter),
                                    onClick = { viewModel.setNotifyOnEnter(zone.id, !(pref?.notify_on_enter ?: false)) },
                                )
                                ZoneNotifyToggle(
                                    icon = Icons.Filled.Logout,
                                    enabled = pref?.notify_on_exit ?: false,
                                    contentDescription = stringResource(R.string.zone_notify_exit),
                                    onClick = { viewModel.setNotifyOnExit(zone.id, !(pref?.notify_on_exit ?: false)) },
                                )
                                IconButton(onClick = { zoneToDelete = zone }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.zone_delete))
                                }
                            }
                            // De un vistazo, de quién me avisa cada zona.
                            if (watched.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.align(Alignment.End).padding(top = 6.dp, bottom = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    watched.forEach { member ->
                                        AsyncImage(
                                            model = absoluteAvatarUrl(member.avatar_url),
                                            contentDescription = member.display_name,
                                            modifier = Modifier.size(24.dp).clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    zoneToDelete?.let { zone ->
        AlertDialog(
            onDismissRequest = { zoneToDelete = null },
            title = { Text(stringResource(R.string.zone_delete_confirm_title)) },
            text = { Text(stringResource(R.string.zone_delete_confirm_body, zone.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteZone(zone.id)
                    zoneToDelete = null
                }) { Text(stringResource(R.string.zone_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { zoneToDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ZoneNotifyToggle(
    icon: ImageVector,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        )
    }
}
