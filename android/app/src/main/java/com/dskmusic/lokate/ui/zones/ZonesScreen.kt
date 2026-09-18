package com.dskmusic.lokate.ui.zones

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.dto.ZoneDto
import com.dskmusic.lokate.di.ServiceLocator

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ZonesScreen(
    locator: ServiceLocator,
    onAddZone: () -> Unit,
    onEditZone: (String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = remember { ZonesViewModel(locator.zoneRepository) }
    val zones by viewModel.zones.collectAsStateWithLifecycle(initialValue = emptyList())
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    var zoneToDelete by remember { mutableStateOf<ZoneDto?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.zones_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddZone) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.zone_add))
            }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(zones, key = { it.id }) { zone ->
                val pref = prefs[zone.id]
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onEditZone(zone.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                        Column(
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        ) {
                            Text(zone.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(
                                "${zone.radius_m.toInt()} m",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
