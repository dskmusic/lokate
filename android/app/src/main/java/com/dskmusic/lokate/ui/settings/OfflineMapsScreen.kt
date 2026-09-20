package com.dskmusic.lokate.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.offline.OfflineMapCatalog
import com.dskmusic.lokate.data.offline.OfflineMaps
import com.dskmusic.lokate.data.offline.OfflineRegion
import com.dskmusic.lokate.data.offline.formatBytes
import com.dskmusic.lokate.data.offline.suggestedRegion
import com.dskmusic.lokate.ui.common.rememberMyLocation
import java.io.File
import java.text.Normalizer

/** Zonas de mapa guardadas en el móvil: lo que hay, lo que ocupa, y descargar o borrar más. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val installed by OfflineMaps.installed.collectAsStateWithLifecycle()
    val download by OfflineMaps.download.collectAsStateWithLifecycle()
    val error by OfflineMaps.error.collectAsStateWithLifecycle()
    val myLocation = rememberMyLocation()

    var query by remember { mutableStateOf("") }
    var pendingDownload by remember { mutableStateOf<OfflineRegion?>(null) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    // Tamaño real que dice el servidor, que los mapas se regeneran y engordan. Mientras no
    // conteste se enseña el aproximado del catálogo.
    var exactSize by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) { OfflineMaps.refresh(context) }
    LaunchedEffect(pendingDownload) {
        exactSize = null
        exactSize = pendingDownload?.let { OfflineMaps.remoteSizeBytes(it) }
    }

    val installedIds = installed.map { it.name.removeSuffix(".map") }.toSet()
    val needle = query.forSearch()
    val available = OfflineMapCatalog.filter {
        // Se busca también por el nombre original y por el grupo: el catálogo viene en inglés y
        // alguien puede escribir "germany" igual que "Alemania", o "europe" para ver el bloque.
        it.id !in installedIds && (
            needle.isBlank() ||
                it.displayName().forSearch().contains(needle) ||
                it.name.forSearch().contains(needle) ||
                it.group.forSearch().contains(needle)
            )
    }
    val suggestion = myLocation
        ?.takeIf { installed.isEmpty() && download == null }
        ?.let { suggestedRegion(it.latitude, it.longitude) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.offline_maps_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            download?.let { progress ->
                item {
                    val name = OfflineMapCatalog.firstOrNull { it.id == progress.regionId }?.displayName().orEmpty()
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(R.string.offline_maps_downloading, name))
                        Spacer(Modifier.height(8.dp))
                        if (progress.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { progress.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                formatBytes(progress.downloadedBytes) +
                                    if (progress.totalBytes > 0) " / " + formatBytes(progress.totalBytes) else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { OfflineMaps.cancel() }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                }
            }

            error?.let { message ->
                item {
                    Text(
                        stringResource(R.string.offline_maps_error, message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            suggestion?.let { region ->
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.offline_maps_suggested, region.displayName())) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.offline_maps_suggested_desc,
                                    formatBytes(region.approxBytes),
                                ),
                            )
                        },
                        trailingContent = {
                            Button(onClick = { pendingDownload = region }) {
                                Text(stringResource(R.string.offline_maps_download))
                            }
                        },
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.offline_maps_downloaded)) }
            if (installed.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.offline_maps_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(installed, key = { it.name }) { file ->
                    ListItem(
                        headlineContent = { Text(OfflineMaps.regionOf(file)?.displayName() ?: file.name) },
                        supportingContent = { Text(formatBytes(file.length())) },
                        trailingContent = {
                            IconButton(onClick = { pendingDelete = file }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                            }
                        },
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.offline_maps_available)) }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.offline_maps_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
            if (available.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.offline_maps_search_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(available, key = { it.id }) { region ->
                    ListItem(
                        headlineContent = { Text(region.displayName()) },
                        supportingContent = { Text("${region.group} · ${formatBytes(region.approxBytes)}") },
                        trailingContent = { Icon(Icons.Filled.Download, contentDescription = null) },
                        modifier = Modifier.clickable { pendingDownload = region },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    pendingDownload?.let { region ->
        val size = exactSize
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text(stringResource(R.string.offline_maps_download_title, region.displayName())) },
            text = {
                Text(
                    if (size != null) {
                        stringResource(R.string.offline_maps_download_body, formatBytes(size))
                    } else {
                        stringResource(R.string.offline_maps_download_body_approx, formatBytes(region.approxBytes))
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    OfflineMaps.start(context, region)
                    pendingDownload = null
                }) { Text(stringResource(R.string.offline_maps_download)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDownload = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    pendingDelete?.let { file ->
        val name = OfflineMaps.regionOf(file)?.displayName() ?: file.name
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.offline_maps_delete_title, name)) },
            text = { Text(stringResource(R.string.offline_maps_delete_body, formatBytes(file.length()))) },
            confirmButton = {
                TextButton(onClick = {
                    OfflineMaps.delete(context, file)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Sin tildes y en minúsculas: buscar "andalucia" tiene que encontrar "Andalucía". */
private fun String.forSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
