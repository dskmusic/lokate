package com.dskmusic.lokate.ui.member

import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.absoluteAvatarUrl
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.util.FileUtils
import com.dskmusic.lokate.util.LocationSharing
import com.dskmusic.lokate.util.formatTimestamp

private enum class AttachmentKind { PHOTO, VIDEO, FILE }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MemberDetailScreen(
    locator: ServiceLocator,
    userId: String,
    onBack: () -> Unit,
    onOpenHistory: (String, String) -> Unit,
    onOpenMap: (String) -> Unit,
) {
    val context = LocalContext.current
    val viewModel = remember(userId) {
        MemberDetailViewModel(locator.locationRepository, locator.messageRepository, userId)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showRingConfirm by remember { mutableStateOf(false) }

    var showEmergencyCompose by remember { mutableStateOf(false) }
    var showEmergencyConfirm by remember { mutableStateOf(false) }
    var emergencyText by remember { mutableStateOf("") }
    var attachmentUri by remember { mutableStateOf<Uri?>(null) }
    var attachmentMime by remember { mutableStateOf<String?>(null) }
    var attachmentKind by remember { mutableStateOf<AttachmentKind?>(null) }

    fun resetEmergencyCompose() {
        emergencyText = ""
        attachmentUri = null
        attachmentMime = null
        attachmentKind = null
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            attachmentUri = uri
            attachmentMime = context.contentResolver.getType(uri) ?: "image/*"
            attachmentKind = AttachmentKind.PHOTO
        }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            attachmentUri = uri
            attachmentMime = context.contentResolver.getType(uri) ?: "video/*"
            attachmentKind = AttachmentKind.VIDEO
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            attachmentUri = uri
            attachmentMime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            attachmentKind = AttachmentKind.FILE
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.location?.display_name ?: stringResource(R.string.member_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val location = state.location
        if (location == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_location_yet))
            }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = absoluteAvatarUrl(location.avatar_url),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(location.display_name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.last_seen_label, formatTimestamp(location.timestamp)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.requestingLocation) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    IconButton(onClick = { viewModel.requestFreshLocation() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh_location))
                    }
                }
            }

            if (state.requestingLocation) {
                Text(
                    stringResource(R.string.location_request_in_progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.BatteryFull, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    location.battery_level?.let { "$it% · ${stringResource(R.string.battery_label)}" + if (location.is_charging == true) " (${stringResource(R.string.charging_label)})" else "" }
                        ?: stringResource(R.string.battery_unknown),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (location.wifi_connected == true) Icons.Filled.Wifi else Icons.Filled.WifiOff, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (location.wifi_connected) {
                        true -> location.wifi_ssid?.let { "${stringResource(R.string.wifi_connected_label)} ($it)" }
                            ?: stringResource(R.string.wifi_connected_label)
                        false -> stringResource(R.string.wifi_not_connected_label)
                        null -> stringResource(R.string.wifi_unknown_label)
                    },
                )
            }

            Spacer(Modifier.height(32.dp))

            OutlinedButton(
                onClick = { LocationSharing.openInGoogleMaps(context, location.lat, location.lng, location.display_name) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Map, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.open_in_google_maps))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { onOpenHistory(location.user_id, location.display_name) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.History, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.history_title))
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { showRingConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.ringing,
            ) {
                Icon(Icons.Filled.NotificationsActive, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ring_device_button))
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { showEmergencyCompose = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.sendingMessage,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.emergency_message_button))
            }

            if (state.ringSent) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.ring_sent_confirmation), color = MaterialTheme.colorScheme.primary)
            }
            if (state.messageSent) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.emergency_message_sent), color = MaterialTheme.colorScheme.primary)
            }
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showRingConfirm) {
        AlertDialog(
            onDismissRequest = { showRingConfirm = false },
            title = { Text(stringResource(R.string.ring_confirm_title)) },
            text = { Text(stringResource(R.string.ring_confirm_body, state.location?.display_name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = { showRingConfirm = false; viewModel.ringDevice() }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRingConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showEmergencyCompose) {
        AlertDialog(
            onDismissRequest = { showEmergencyCompose = false; resetEmergencyCompose() },
            title = { Text(stringResource(R.string.emergency_compose_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = emergencyText,
                        onValueChange = { emergencyText = it },
                        placeholder = { Text(stringResource(R.string.emergency_compose_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickImage.launch("image/*") }) {
                            Text(stringResource(R.string.emergency_attach_photo))
                        }
                        OutlinedButton(onClick = { pickVideo.launch("video/*") }) {
                            Text(stringResource(R.string.emergency_attach_video))
                        }
                        OutlinedButton(onClick = { pickFile.launch("*/*") }) {
                            Text(stringResource(R.string.emergency_attach_file))
                        }
                    }
                    attachmentKind?.let { kind ->
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (kind) {
                                    AttachmentKind.PHOTO -> stringResource(R.string.emergency_attachment_photo_selected)
                                    AttachmentKind.VIDEO -> stringResource(R.string.emergency_attachment_video_selected)
                                    AttachmentKind.FILE -> stringResource(R.string.emergency_attachment_file_selected)
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { attachmentUri = null; attachmentMime = null; attachmentKind = null }) {
                                Text(stringResource(R.string.emergency_remove_attachment))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showEmergencyCompose = false; showEmergencyConfirm = true },
                    enabled = emergencyText.isNotBlank() || attachmentUri != null,
                ) {
                    Text(stringResource(R.string.emergency_send_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmergencyCompose = false; resetEmergencyCompose() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showEmergencyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmergencyConfirm = false },
            title = { Text(stringResource(R.string.emergency_confirm_title)) },
            text = { Text(stringResource(R.string.emergency_confirm_body, state.location?.display_name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    showEmergencyConfirm = false
                    val file = attachmentUri?.let { uri ->
                        val ext = attachmentMime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin"
                        FileUtils.uriToCacheFile(context, uri, fileName = "emergency_attachment.$ext")
                    }
                    viewModel.sendEmergencyMessage(emergencyText, file, attachmentMime)
                    resetEmergencyCompose()
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmergencyConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    when (state.locationRequestResult) {
        LocationRequestResult.SUCCESS -> AlertDialog(
            onDismissRequest = viewModel::clearLocationRequestResult,
            title = { Text(stringResource(R.string.location_request_success_title)) },
            text = { Text(stringResource(R.string.location_request_success_body, state.location?.display_name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearLocationRequestResult()
                    onOpenMap(userId)
                }) { Text(stringResource(R.string.location_request_view_on_map)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::clearLocationRequestResult) { Text(stringResource(R.string.confirm)) }
            },
        )
        LocationRequestResult.FAILURE -> AlertDialog(
            onDismissRequest = viewModel::clearLocationRequestResult,
            title = { Text(stringResource(R.string.location_request_failure_title)) },
            text = { Text(stringResource(R.string.location_request_failure_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.requestFreshLocation() }) { Text(stringResource(R.string.location_request_retry)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::clearLocationRequestResult) { Text(stringResource(R.string.confirm)) }
            },
        )
        null -> {}
    }
}
