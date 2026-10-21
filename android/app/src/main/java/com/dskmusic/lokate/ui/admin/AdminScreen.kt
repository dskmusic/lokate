package com.dskmusic.lokate.ui.admin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Troubleshoot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dskmusic.lokate.R
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.util.MapStyle
import com.dskmusic.lokate.ui.common.ListSearchField

private enum class AdminTab { DASHBOARD, USERS, GROUPS, ZONES, BACKUPS }

/** Panel de administración nativo — mismas acciones que la web /admin (backend admin_api.py),
 * en Compose. Solo llega aquí quien ya se comprobó que es admin (ver el candado en MapScreen). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(locator: ServiceLocator, onBack: () -> Unit, onOpenMember: (String) -> Unit) {
    val viewModel = remember { AdminViewModel(locator.adminRepository) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // El estilo del mapa, para la vista previa al editar una zona desde aquí.
    val mapStyle by locator.settings.mapStyle.collectAsStateWithLifecycle(initialValue = MapStyle.STANDARD)
    // rememberSaveable y no remember: abrir la ficha de alguien saca esta pantalla de la
    // composición, y con un remember al volver se perdía la pestaña y aparecía el panel de
    // inicio en vez de la lista de la que se había salido.
    var tab by rememberSaveable { mutableStateOf(AdminTab.DASHBOARD) }
    var showZoneLog by remember { mutableStateOf(false) }
    var showZoneReplay by remember { mutableStateOf(false) }
    // null = buscador cerrado. Se cierra al cambiar de pestaña: cada lista se busca por lo suyo,
    // y arrastrar el texto de una a otra solo deja la siguiente pareciendo vacía.
    var query by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tab) {
        query = null
        when (tab) {
            AdminTab.DASHBOARD -> { viewModel.loadDashboard(); viewModel.loadDiskUsage() }
            AdminTab.USERS -> if (state.users.isEmpty()) viewModel.loadUsers()
            AdminTab.GROUPS -> if (state.groups.isEmpty()) viewModel.loadGroups()
            AdminTab.ZONES -> if (state.zones.isEmpty()) viewModel.loadZones()
            AdminTab.BACKUPS -> if (state.backups.isEmpty()) viewModel.loadBackups()
        }
    }

    // El registro de zonas es pantalla aparte y no otra pestaña: se entra desde el botón de la
    // barra y al volver sigues donde estabas.
    if (showZoneLog) {
        AdminZoneLogScreen(state, viewModel, onBack = { showZoneLog = false })
    } else if (showZoneReplay) {
        AdminZoneReplayScreen(state, viewModel, onBack = { showZoneReplay = false })
    } else Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val text = query
                    if (text == null) Text(stringResource(R.string.admin_title))
                    else ListSearchField(text) { query = it }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    // Solo en las pestañas que son una lista: en el panel y en las copias no hay
                    // nada que filtrar.
                    if (tab in setOf(AdminTab.USERS, AdminTab.GROUPS, AdminTab.ZONES)) {
                        IconButton(onClick = { query = if (query == null) "" else null }) {
                            Icon(
                                if (query == null) Icons.Filled.Search else Icons.Filled.Close,
                                contentDescription = stringResource(
                                    if (query == null) R.string.place_search_action else R.string.list_search_close,
                                ),
                            )
                        }
                    }
                    // Al lado del registro a proposito: los dos contestan a "por que paso
                    // esto con una zona", uno con lo que paso y otro con lo que habria pasado.
                    IconButton(onClick = { showZoneReplay = true }) {
                        Icon(
                            Icons.Filled.Troubleshoot,
                            contentDescription = stringResource(R.string.admin_zone_replay_title),
                        )
                    }
                    IconButton(onClick = { showZoneLog = true }) {
                        Icon(
                            Icons.Filled.NotificationsActive,
                            contentDescription = stringResource(R.string.admin_zone_log_title),
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == AdminTab.DASHBOARD,
                    onClick = { tab = AdminTab.DASHBOARD },
                    icon = { Icon(Icons.Filled.Dashboard, contentDescription = null) },
                    label = { Text(stringResource(R.string.admin_tab_dashboard)) },
                )
                NavigationBarItem(
                    selected = tab == AdminTab.USERS,
                    onClick = { tab = AdminTab.USERS },
                    icon = { Icon(Icons.Filled.People, contentDescription = null) },
                    label = { Text(stringResource(R.string.people_title)) },
                )
                NavigationBarItem(
                    selected = tab == AdminTab.GROUPS,
                    onClick = { tab = AdminTab.GROUPS },
                    icon = { Icon(Icons.Filled.Groups, contentDescription = null) },
                    label = { Text(stringResource(R.string.admin_tab_groups)) },
                )
                NavigationBarItem(
                    selected = tab == AdminTab.ZONES,
                    onClick = { tab = AdminTab.ZONES },
                    icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                    label = { Text(stringResource(R.string.zones_title)) },
                )
                NavigationBarItem(
                    selected = tab == AdminTab.BACKUPS,
                    onClick = { tab = AdminTab.BACKUPS },
                    icon = { Icon(Icons.Filled.Backup, contentDescription = null) },
                    label = { Text(stringResource(R.string.admin_tab_backups)) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                AdminTab.DASHBOARD -> AdminDashboardTab(
                    state,
                    viewModel,
                    onRefresh = { viewModel.loadDashboard(); viewModel.loadDiskUsage() },
                )
                AdminTab.USERS -> AdminUsersTab(state, viewModel, query.orEmpty(), onOpenMember)
                AdminTab.GROUPS -> AdminGroupsTab(state, viewModel, query.orEmpty())
                AdminTab.ZONES -> AdminZonesTab(state, viewModel, query.orEmpty(), mapStyle)
                AdminTab.BACKUPS -> AdminBackupsTab(state, viewModel)
            }
        }
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text(stringResource(R.string.error_generic)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::clearError) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
