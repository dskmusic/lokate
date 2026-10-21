package com.dskmusic.lokate.ui.admin

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dskmusic.lokate.R

/**
 * Diagnóstico de zonas: el repaso de app/zone_replay.py, pero aquí dentro.
 *
 * Vuelve a pasar los pings ya guardados de alguien por la misma regla que decide entradas y
 * salidas, y enseña ping a ping la distancia, la precisión, el umbral que le tocaba y qué
 * decidió. Lo mismo que el comando `docker compose exec lokate-api python -m app.zone_replay`
 * y que la página "Diagnóstico" del panel web: el informe lo arma el servidor con la misma
 * función, así que los tres dicen lo mismo.
 *
 * La diferencia con [AdminZoneLogScreen]: el registro enseña lo que PASÓ (tabla zone_events);
 * esto reconstruye lo que habría pasado, que es lo que explica un aviso que no llegó.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AdminZoneReplayScreen(state: AdminUiState, viewModel: AdminViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Las dos listas alimentan los desplegables y puede que su pestaña no se haya abierto aún.
    LaunchedEffect(Unit) {
        if (state.users.isEmpty()) viewModel.loadUsers()
        if (state.zones.isEmpty()) viewModel.loadZones()
    }

    val report = state.replayLines.joinToString(System.lineSeparator())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_zone_replay_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    // Copiar es media razón de ser de esta pantalla: el informe se acaba pegando
                    // en un mensaje para mirarlo con calma.
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(report))
                            Toast.makeText(
                                context,
                                R.string.admin_zone_replay_copied,
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        enabled = report.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.admin_zone_replay_copy),
                        )
                    }
                    IconButton(onClick = { viewModel.runZoneReplay() }, enabled = !state.loadingReplay) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Filters(state, viewModel)
            HorizontalDivider()
            when {
                state.loadingReplay ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                report.isEmpty() -> Hint()
                else -> Report(report)
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
                selected = state.users.firstOrNull { it.id == state.replayUserId }?.display_name
                    ?: stringResource(R.string.admin_zone_replay_person),
                active = state.replayUserId != null,
                options = state.users.map { it.id to it.display_name },
                onPick = { viewModel.setReplayFilters(userId = it) },
            )
            FilterDropdown(
                selected = state.zones.firstOrNull { it.id == state.replayZoneId }?.name
                    ?: stringResource(R.string.admin_zone_replay_zone),
                active = state.replayZoneId != null,
                options = state.zones.map { it.id to it.name },
                onPick = { viewModel.setReplayFilters(zoneId = it) },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 72 h es el tope del servidor (zone_replay.MAX_HOURS): pedir más no devuelve más.
            listOf(3, 6, 24, 72).forEach { hours ->
                FilterChip(
                    selected = state.replayHours == hours,
                    onClick = { viewModel.setReplayFilters(hours = hours) },
                    label = { Text(stringResource(R.string.admin_zone_replay_hours, hours)) },
                )
            }
            FilterChip(
                selected = state.replayAll,
                onClick = { viewModel.setReplayFilters(all = !state.replayAll) },
                label = { Text(stringResource(R.string.admin_zone_replay_all)) },
            )
        }
    }
}

@Composable
private fun Hint() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.admin_zone_replay_pick), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.admin_zone_replay_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * El informe entero en un solo texto monoespaciado, sin cortar líneas: las columnas de
 * distancia, precisión y umbral solo se leen si quedan alineadas, así que se desplaza a lo
 * ancho en vez de partirse.
 *
 * ponytail: un Text y no una lista; son unos cientos de líneas como mucho (el servidor tiene su
 * tope de horas) y así copiarlo es un joinToString y no recomponer una lista perezosa.
 */
@Composable
private fun Report(report: String) {
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            report,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
        )
    }
}
