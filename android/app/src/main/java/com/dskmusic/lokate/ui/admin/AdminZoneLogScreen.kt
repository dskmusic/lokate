package com.dskmusic.lokate.ui.admin

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.dto.AdminZoneEventDto
import com.dskmusic.lokate.util.parseIsoDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de entradas y salidas de zona: lo que el servidor decidió de verdad y a cuánta gente
 * avisó.
 *
 * Lo escribe geofence.check_zone_transitions en el momento en que pasa (tabla zone_events), así
 * que esta pantalla solo lee. La diferencia con app/zone_replay.py es justo esa: aquel reconstruye
 * lo que HABRÍA pasado repasando los pings, este enseña lo que pasó, incluido el motivo de los
 * avisos que no salieron.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AdminZoneLogScreen(state: AdminUiState, viewModel: AdminViewModel, onBack: () -> Unit) {
    // Las dos listas alimentan los desplegables del filtro y puede que la pestaña que las carga no
    // se haya abierto todavía.
    LaunchedEffect(Unit) {
        if (state.users.isEmpty()) viewModel.loadUsers()
        if (state.zones.isEmpty()) viewModel.loadZones()
        viewModel.loadZoneEvents()
    }

    // Modo selección: se entra manteniendo pulsada una fila y se sale al desmarcarlas todas o
     // con la X. Mientras está puesto, el botón de atrás del sistema también lo quita.
    val selecting = state.selectedEvents.isNotEmpty()
    var confirming by remember { mutableStateOf(false) }
    BackHandler(enabled = selecting) { viewModel.clearEventSelection() }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.admin_zone_log_delete_title)) },
            text = { Text(stringResource(R.string.admin_zone_log_delete_message, state.selectedEvents.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    viewModel.deleteSelectedEvents()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selecting) {
                            stringResource(R.string.admin_zone_log_selected, state.selectedEvents.size)
                        } else {
                            stringResource(R.string.admin_zone_log_title)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selecting) viewModel.clearEventSelection() else onBack() }) {
                        Icon(if (selecting) Icons.Filled.Close else Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { confirming = true }, enabled = !state.actionInProgress) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    } else {
                        IconButton(onClick = { viewModel.loadZoneEvents() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Filters(state, viewModel)
            HorizontalDivider()
            when {
                state.loadingZoneEvents && state.zoneEvents.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                state.zoneEvents.isEmpty() -> EmptyLog()
                else -> EventList(
                    events = state.zoneEvents,
                    selected = state.selectedEvents,
                    selecting = selecting,
                    onToggle = viewModel::toggleEventSelection,
                )
            }
        }
    }
}

@Composable
private fun Filters(state: AdminUiState, viewModel: AdminViewModel) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterDropdown(
                selected = state.users.firstOrNull { it.id == state.logUserId }?.display_name
                    ?: stringResource(R.string.admin_zone_log_all_people),
                active = state.logUserId != null,
                options = listOf(null to stringResource(R.string.admin_zone_log_all_people)) +
                    state.users.map { it.id to it.display_name },
                onPick = { viewModel.setLogFilters(userId = it) },
            )
            FilterDropdown(
                selected = state.zones.firstOrNull { it.id == state.logZoneId }?.name
                    ?: stringResource(R.string.admin_zone_log_all_zones),
                active = state.logZoneId != null,
                options = listOf(null to stringResource(R.string.admin_zone_log_all_zones)) +
                    state.zones.map { it.id to it.name },
                onPick = { viewModel.setLogFilters(zoneId = it) },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // El servidor guarda 30 días de eventos (lo mismo que los pings): más atrás no hay nada
            // que pedir.
            listOf(
                1 to R.string.admin_zone_log_days_1,
                7 to R.string.admin_zone_log_days_7,
                30 to R.string.admin_zone_log_days_30,
            ).forEach { (days, label) ->
                FilterChip(
                    selected = state.logDays == days,
                    onClick = { viewModel.setLogFilters(days = days) },
                    label = { Text(stringResource(label)) },
                )
            }
            FilterChip(
                selected = state.logOnlyMissed,
                onClick = { viewModel.setLogFilters(onlyMissed = !state.logOnlyMissed) },
                label = { Text(stringResource(R.string.admin_zone_log_only_missed)) },
            )
        }
    }
}

/** Un chip que abre su propia lista. ponytail: no es un ExposedDropdownMenuBox porque aquí no hay
 * nada que escribir, solo elegir, y el chip ya enseña si el filtro está puesto. */
@Composable
private fun FilterDropdown(
    selected: String,
    active: Boolean,
    options: List<Pair<String?, String>>,
    onPick: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = active,
            onClick = { expanded = true },
            label = { Text(selected) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onPick(id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyLog() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.admin_zone_log_empty), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.admin_zone_log_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Una fila con su día y su hora ya resueltos: las fechas se formatean una vez por carga y no en
 * cada recomposición de cada fila. */
private data class LogRow(val event: AdminZoneEventDto, val day: String, val time: String)

@Composable
private fun EventList(
    events: List<AdminZoneEventDto>,
    selected: Set<String>,
    selecting: Boolean,
    onToggle: (String) -> Unit,
) {
    val days = remember(events) {
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val hourFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        events
            .mapNotNull { event ->
                parseIsoDate(event.at)?.let { LogRow(event, dayFormat.format(it), hourFormat.format(it)) }
            }
            // El servidor los manda del más nuevo al más viejo y groupBy respeta ese orden.
            .groupBy { it.day }
            .toList()
    }

    LazyColumn(Modifier.fillMaxSize()) {
        days.forEach { (day, rows) ->
            item(key = day) { DayHeader(day, rows) }
            items(rows, key = { it.event.id }) { row ->
                EventRow(row, selecting, row.event.id in selected, onToggle)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DayHeader(day: String, rows: List<LogRow>) {
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val yesterday = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - 24 * 60 * 60_000L))
    }
    val label = when (day) {
        today -> stringResource(R.string.admin_zone_log_today)
        yesterday -> stringResource(R.string.admin_zone_log_yesterday)
        else -> day.split("-").reversed().joinToString("/")   // 2026-09-24 -> 24/09/2026
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.admin_zone_log_day_summary, rows.size, rows.count { it.event.notified == 0 }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventRow(row: LogRow, selecting: Boolean, checked: Boolean, onToggle: (String) -> Unit) {
    val event = row.event
    val headline = stringResource(
        if (event.entered) R.string.admin_zone_log_entered else R.string.admin_zone_log_exited,
        event.user_name,
        event.zone_name,
    )
    // Si hubo destinatarios no hay nada que explicar; el motivo solo interesa cuando no se avisó.
    val outcome = if (event.notified > 0) {
        stringResource(R.string.admin_zone_log_notified, event.notified)
    } else {
        stringResource(
            when (event.reason) {
                "no_prefs" -> R.string.admin_zone_log_reason_no_prefs
                "hidden" -> R.string.admin_zone_log_reason_hidden
                "private_zone" -> R.string.admin_zone_log_reason_private
                "test" -> R.string.admin_zone_log_reason_test
                "resync" -> R.string.admin_zone_log_reason_resync
                else -> R.string.admin_zone_log_reason_unknown
            }
        )
    }
    ListItem(
        modifier = Modifier.combinedClickable(
            // Fuera del modo selección un toque no hace nada (el registro es de solo lectura):
            // se entra manteniendo pulsado, que es el gesto de siempre para marcar.
            onClick = { if (selecting) onToggle(event.id) },
            onLongClick = { onToggle(event.id) },
        ),
        colors = if (checked) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        trailingContent = if (selecting) {
            { Checkbox(checked = checked, onCheckedChange = { onToggle(event.id) }) }
        } else {
            null
        },
        leadingContent = {
            Icon(
                if (event.entered) Icons.Filled.Login else Icons.Filled.Logout,
                contentDescription = null,
                tint = if (event.notified > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
        headlineContent = { Text(headline) },
        supportingContent = {
            Column {
                Text(row.time + " · " + outcome)
                // La distancia al centro y la precisión de ese momento: con las dos se ve si la
                // decisión fue holgada o de milímetros, que es lo primero que se mira cuando algo
                // no cuadra.
                val detail = listOfNotNull(
                    event.distance_m?.let { stringResource(R.string.admin_zone_log_distance, it.toInt()) },
                    event.accuracy?.let { stringResource(R.string.admin_zone_log_accuracy, it.toInt()) },
                ).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}
