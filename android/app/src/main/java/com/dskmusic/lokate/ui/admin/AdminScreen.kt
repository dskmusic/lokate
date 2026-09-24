package com.dskmusic.lokate.ui.admin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dskmusic.lokate.R
import com.dskmusic.lokate.di.ServiceLocator

private enum class AdminTab { DASHBOARD, USERS, GROUPS, ZONES, BACKUPS }

/** Panel de administración nativo — mismas acciones que la web /admin (backend admin_api.py),
 * en Compose. Solo llega aquí quien ya se comprobó que es admin (ver el candado en MapScreen). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(locator: ServiceLocator, onBack: () -> Unit) {
    val viewModel = remember { AdminViewModel(locator.adminRepository) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(AdminTab.DASHBOARD) }
    var showZoneLog by remember { mutableStateOf(false) }

    LaunchedEffect(tab) {
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
    } else Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
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
                AdminTab.USERS -> AdminUsersTab(state, viewModel)
                AdminTab.GROUPS -> AdminGroupsTab(state, viewModel)
                AdminTab.ZONES -> AdminZonesTab(state, viewModel)
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
