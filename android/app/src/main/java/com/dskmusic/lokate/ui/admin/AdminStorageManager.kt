package com.dskmusic.lokate.ui.admin

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.absoluteMediaUrl
import com.dskmusic.lokate.data.remote.dto.AdminFileDto

/** Carpetas que el backend deja explorar y borrar (ver _MANAGED_DIRS en admin_api.py). */
private const val FOLDER_AVATARS = "avatars"
private const val FOLDER_ATTACHMENTS = "attachments"

/**
 * Explorador de los archivos borrables del servidor: avatares y adjuntos de mensajes. Ni la base
 * de datos, ni el APK, ni la web estática, ni las copias de seguridad (esas tienen su pestaña).
 *
 * Va sobre el panel como diálogo a pantalla completa y no como ruta propia a propósito: así
 * comparte el [AdminViewModel] del panel y el desglose de espacio de debajo se recalcula solo
 * al borrar, sin navegar ni recargar nada a mano.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AdminStorageManager(state: AdminUiState, viewModel: AdminViewModel, onClose: () -> Unit) {
    var folder by remember { mutableStateOf(FOLDER_AVATARS) }
    var previewing by remember { mutableStateOf<AdminFileDto?>(null) }
    var deleting by remember { mutableStateOf<AdminFileDto?>(null) }
    var deletingAll by remember { mutableStateOf(false) }

    LaunchedEffect(folder) { viewModel.loadFiles(folder) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.admin_storage_manage)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = null) }
                    },
                    actions = {
                        // Solo en adjuntos: vaciar los avatares de golpe dejaría a medio grupo sin
                        // foto de un toque, y no es lo que se pide nunca.
                        if (folder == FOLDER_ATTACHMENTS && state.files.isNotEmpty()) {
                            IconButton(onClick = { deletingAll = true }, enabled = !state.actionInProgress) {
                                Icon(
                                    Icons.Filled.DeleteSweep,
                                    contentDescription = stringResource(R.string.admin_storage_delete_all),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = folder == FOLDER_AVATARS,
                        onClick = { folder = FOLDER_AVATARS },
                        label = { Text(stringResource(R.string.admin_storage_avatars)) },
                    )
                    FilterChip(
                        selected = folder == FOLDER_ATTACHMENTS,
                        onClick = { folder = FOLDER_ATTACHMENTS },
                        label = { Text(stringResource(R.string.admin_storage_attachments)) },
                    )
                }

                when {
                    state.loadingFiles -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    state.files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.admin_storage_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.files) { file ->
                            ListItem(
                                leadingContent = { Icon(iconFor(file.kind), contentDescription = null) },
                                headlineContent = { Text(file.name, maxLines = 1) },
                                supportingContent = {
                                    Text(
                                        formatBytes(file.size_bytes) + " · " + file.modified.replace("T", " ").take(16) +
                                            if (file.in_use) " · " + stringResource(R.string.admin_storage_in_use) else "",
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = { deleting = file }, enabled = !state.actionInProgress) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.backups_delete),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                                modifier = Modifier.clickable { previewing = file },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    previewing?.let { file ->
        AlertDialog(
            onDismissRequest = { previewing = null },
            title = { Text(file.name, maxLines = 1) },
            text = { FilePreview(file) },
            confirmButton = {
                TextButton(onClick = { previewing = null }) { Text(stringResource(R.string.close)) }
            },
        )
    }

    if (deletingAll) {
        AlertDialog(
            onDismissRequest = { deletingAll = false },
            title = { Text(stringResource(R.string.admin_storage_delete_all_confirm_title)) },
            text = {
                Text(stringResource(R.string.admin_storage_delete_all_confirm_body, state.files.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllFiles(folder)
                        deletingAll = false
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingAll = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    deleting?.let { file ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.admin_storage_delete_confirm_title)) },
            text = {
                Text(
                    if (file.in_use) {
                        stringResource(R.string.admin_storage_delete_confirm_in_use, file.name)
                    } else {
                        stringResource(R.string.admin_storage_delete_confirm_body, file.name)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFile(folder, file.name)
                        previewing = null
                        deleting = null
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Imagen en línea; vídeo y audio con los controles del sistema (VideoView vale para los dos,
 * igual que en el visor de mensajes prioritarios). Lo demás no se previsualiza. */
@Composable
private fun FilePreview(file: AdminFileDto) {
    val url = absoluteMediaUrl(file.url)
    when {
        url == null -> Text(stringResource(R.string.admin_storage_no_preview))
        file.kind == "image" -> AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(320.dp),
        )
        file.kind == "video" || file.kind == "audio" -> Box(Modifier.fillMaxWidth().height(240.dp)) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(Uri.parse(url))
                        setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                        setOnPreparedListener { start() }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        else -> Text(stringResource(R.string.admin_storage_no_preview))
    }
}

private fun iconFor(kind: String) = when (kind) {
    "image" -> Icons.Filled.Image
    "video" -> Icons.Filled.Videocam
    "audio" -> Icons.Filled.Audiotrack
    else -> Icons.Filled.InsertDriveFile
}
