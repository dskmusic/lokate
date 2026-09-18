package com.dskmusic.lokate.ui.admin

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.dto.AdminBackupDto
import com.dskmusic.lokate.util.MediaSaver
import com.dskmusic.lokate.util.formatTimestamp
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun AdminBackupsTab(state: AdminUiState, viewModel: AdminViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<AdminBackupDto?>(null) }
    var deleting by remember { mutableStateOf<AdminBackupDto?>(null) }

    Box(Modifier.fillMaxSize()) {
        when {
            state.loadingBackups && state.backups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.backups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.backups_none))
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.backups, key = { it.id }) { backup ->
                    ListItem(
                        headlineContent = {
                            Text(backup.description.ifBlank { stringResource(R.string.backups_no_description) })
                        },
                        supportingContent = {
                            Text("${formatTimestamp(backup.created_at)} · ${formatBytes(backup.size_bytes)}")
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = {
                                    val dest = File(context.cacheDir, "lokate-backup-${backup.id}.tar.gz")
                                    viewModel.downloadBackup(backup.id, dest) { success ->
                                        scope.launch {
                                            val saved = success &&
                                                MediaSaver.saveToDevice(context, dest, "application/gzip", dest.name, forceDownloads = true)
                                            Toast.makeText(
                                                context,
                                                if (saved) R.string.backups_download_success else R.string.backups_download_failure,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                }) { Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.backups_download)) }
                                IconButton(onClick = { restoring = backup }) {
                                    Icon(Icons.Filled.Restore, contentDescription = stringResource(R.string.backups_restore))
                                }
                                IconButton(onClick = { deleting = backup }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.backups_delete))
                                }
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

    if (creating) {
        var description by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.backups_create)) },
            text = {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.backups_description_placeholder)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.createBackup(description.trim()) { creating = false } }) {
                    Text(stringResource(R.string.backups_create_button))
                }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    restoring?.let { backup ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text(stringResource(R.string.backups_restore)) },
            text = { Text(stringResource(R.string.backups_restore_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restoreBackup(backup.id) { success ->
                        restoring = null
                        Toast.makeText(
                            context,
                            if (success) R.string.backups_restore_success else R.string.backups_restore_failure,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }) { Text(stringResource(R.string.backups_restore)) }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    deleting?.let { backup ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(backup.description.ifBlank { stringResource(R.string.backups_no_description) }) },
            text = { Text(stringResource(R.string.backups_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteBackup(backup.id) { deleting = null } }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
