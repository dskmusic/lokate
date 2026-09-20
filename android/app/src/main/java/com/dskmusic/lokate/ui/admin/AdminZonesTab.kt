package com.dskmusic.lokate.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.dto.AdminGroupDto
import com.dskmusic.lokate.data.remote.dto.AdminZoneDto

@Composable
internal fun AdminZonesTab(state: AdminUiState, viewModel: AdminViewModel) {
    var editing by remember { mutableStateOf<AdminZoneDto?>(null) }
    var deleting by remember { mutableStateOf<AdminZoneDto?>(null) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (state.groups.isEmpty()) viewModel.loadGroups()
    }

    Box(Modifier.fillMaxSize()) {
        if (state.loadingZones && state.zones.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.zones, key = { it.id }) { zone ->
                    // Sin el grupo, dos zonas con el mismo nombre en grupos distintos son iguales.
                    val groupName = state.groups.firstOrNull { it.id == zone.group_id }?.name
                    ListItem(
                        headlineContent = { Text(zone.name) },
                        supportingContent = {
                            Text(listOfNotNull(groupName, "${zone.radius_m.toInt()} m").joinToString(" · "))
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { editing = zone }) { Icon(Icons.Filled.Edit, contentDescription = null) }
                                IconButton(onClick = { deleting = zone }) { Icon(Icons.Filled.Delete, contentDescription = null) }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }

        FloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.admin_add)) }
    }

    editing?.let { zone ->
        val groupName = state.groups.firstOrNull { it.id == zone.group_id }?.name
        var name by remember(zone.id) { mutableStateOf(zone.name) }
        var radius by remember(zone.id) { mutableStateOf(zone.radius_m.toInt().toString()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(listOfNotNull(zone.name, groupName).joinToString(" · ")) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.zone_name_label)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = radius,
                        onValueChange = { radius = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.zone_radius_manual_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val radiusM = radius.toDoubleOrNull() ?: zone.radius_m
                    viewModel.updateZone(zone.id, name, zone.lat, zone.lng, radiusM) { editing = null }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    deleting?.let { zone ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(zone.name) },
            text = { Text(stringResource(R.string.zone_delete_confirm_body, zone.name)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteZone(zone.id) { deleting = null } }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (creating) {
        CreateZoneDialog(
            groups = state.groups,
            onDismiss = { creating = false },
            onCreate = { groupId, name, lat, lng, radiusM ->
                viewModel.createZone(groupId, name, lat, lng, radiusM) { creating = false }
            },
        )
    }
}

@Composable
private fun CreateZoneDialog(
    groups: List<AdminGroupDto>,
    onDismiss: () -> Unit,
    onCreate: (groupId: String, name: String, lat: Double, lng: Double, radiusM: Double) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lng by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("100") }
    var expanded by remember { mutableStateOf(false) }
    var selectedGroup by remember(groups) { mutableStateOf(groups.firstOrNull()) }

    val latValue = lat.toDoubleOrNull()
    val lngValue = lng.toDoubleOrNull()
    val canCreate = selectedGroup != null && name.isNotBlank() &&
        latValue != null && latValue in -90.0..90.0 &&
        lngValue != null && lngValue in -180.0..180.0 &&
        (radius.toDoubleOrNull() ?: 0.0) > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.admin_create_zone_title)) },
        text = {
            Column {
                Box {
                    OutlinedTextField(
                        value = selectedGroup?.name.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.group_title)) },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(Modifier.matchParentSize().clickable { expanded = true })
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        groups.forEach { group ->
                            DropdownMenuItem(
                                text = { Text(group.name) },
                                onClick = { selectedGroup = group; expanded = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.zone_name_label)) },
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = lat,
                        onValueChange = { lat = it },
                        label = { Text(stringResource(R.string.admin_zone_lat)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = lng,
                        onValueChange = { lng = it },
                        label = { Text(stringResource(R.string.admin_zone_lng)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = radius,
                    onValueChange = { radius = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.zone_radius_manual_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canCreate,
                onClick = { onCreate(selectedGroup!!.id, name, latValue!!, lngValue!!, radius.toDouble()) },
            ) { Text(stringResource(R.string.admin_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
