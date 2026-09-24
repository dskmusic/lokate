package com.dskmusic.lokate.ui.member

import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.dskmusic.lokate.ui.common.UpdateStatusDialog
import com.dskmusic.lokate.ui.common.UpdateStatusSummary
import com.dskmusic.lokate.ui.common.rememberMyLocation
import com.dskmusic.lokate.util.ConfigCheck
import com.dskmusic.lokate.util.FileUtils
import com.dskmusic.lokate.util.LocationFrequency
import com.dskmusic.lokate.util.LocationSharing
import com.dskmusic.lokate.util.MediaSaver
import com.dskmusic.lokate.util.distanceMeters
import com.dskmusic.lokate.util.formatDistance
import com.dskmusic.lokate.util.formatRelativeTime
import com.dskmusic.lokate.util.formatTimestamp
import kotlinx.coroutines.launch

private enum class AttachmentKind { PHOTO, VIDEO, FILE }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MemberDetailScreen(
    locator: ServiceLocator,
    userId: String,
    onBack: () -> Unit,
    onOpenHistory: (String, String) -> Unit,
    onOpenMap: (String) -> Unit,
    onFixPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = remember(userId) {
        MemberDetailViewModel(
            locator.locationRepository, locator.messageRepository, locator.adminRepository, userId,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val notifySilent by locator.settings.notifySilentEnabled.collectAsStateWithLifecycle(initialValue = true)
    val silentAlertUsers by locator.settings.silentAlertUserIds.collectAsStateWithLifecycle(initialValue = emptySet())
    var showRingConfirm by remember { mutableStateOf(false) }
    var showStopRingConfirm by remember { mutableStateOf(false) }
    /** Wifi pendiente de confirmar para añadir a las "wifis de casa" del miembro; null = ninguna. */
    var addWifiSsid by remember { mutableStateOf<String?>(null) }
    var showUpdateInfo by remember { mutableStateOf(false) }
    var showPhoto by remember { mutableStateOf(false) }
    // Tocar las "wifis de casa" de otro es cosa de admins (el endpoint devuelve 403 al resto),
    // así que eso solo se ofrece si lo eres — mismo criterio que la sección admin de Ajustes.
    var isAdmin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isAdmin = runCatching { locator.authRepository.me() }.getOrNull()?.is_admin == true
        // Sus wifis de casa solo las mira un admin, y solo para no mandarle una repetida.
        if (isAdmin) viewModel.loadKnownWifi()
    }

    var showEmergencyCompose by remember { mutableStateOf(false) }
    var showEmergencyConfirm by remember { mutableStateOf(false) }
    var emergencyText by remember { mutableStateOf("") }
    var attachmentUri by remember { mutableStateOf<Uri?>(null) }
    var attachmentMime by remember { mutableStateOf<String?>(null) }
    var attachmentKind by remember { mutableStateOf<AttachmentKind?>(null) }

    val myLocation = rememberMyLocation()

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
                actions = {
                    // Solo admins: pedirle a su móvil el informe de batería. Va aquí arriba, a
                    // la izquierda de Google Maps, porque es una herramienta de diagnóstico y no
                    // una acción sobre esa persona (a ella no le sale nada: es un push silencioso).
                    if (isAdmin) {
                        IconButton(
                            onClick = { viewModel.requestBatteryReport() },
                            enabled = !state.requestingBatteryReport,
                        ) {
                            if (state.requestingBatteryReport) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    Icons.Filled.BatteryAlert,
                                    contentDescription = stringResource(R.string.battery_report_action),
                                )
                            }
                        }
                    }
                    // Abrir en Google Maps es lo único de esta pantalla que no toca a la otra
                    // persona (ni le pide nada ni le hace sonar nada), así que va arriba y no
                    // entre los botones de acción.
                    state.location?.let { current ->
                        IconButton(
                            onClick = {
                                LocationSharing.openInGoogleMaps(
                                    context, current.lat, current.lng, current.display_name,
                                )
                            },
                        ) {
                            Icon(
                                Icons.Filled.Map,
                                contentDescription = stringResource(R.string.open_in_google_maps),
                            )
                        }
                    }
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
            // Miembro que aún no ha mandado nada (o ubicaciones recién vaciadas): no hay ficha
            // que enseñar, pero sí se le puede pedir una — que es justo lo que hace falta aquí.
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.no_location_yet))
                Spacer(Modifier.height(16.dp))
                if (state.requestingLocation) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.location_request_in_progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Button(onClick = { viewModel.requestFreshLocation() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.refresh_location))
                    }
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = absoluteAvatarUrl(location.avatar_url),
                    contentDescription = stringResource(R.string.people_photo_view_title),
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showPhoto = true },
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(location.display_name, style = MaterialTheme.typography.titleLarge)
                    // Hora exacta y "hace cuanto" juntos: la hora sola obliga a restar de
                    // cabeza para saber si el dato es de ahora o de esta manana.
                    val ago = stringResource(R.string.last_seen_ago, formatRelativeTime(location.timestamp))
                    Text(
                        stringResource(
                            R.string.last_seen_label,
                            "${formatTimestamp(location.timestamp)} - $ago",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
                val charging = location.is_charging == true
                Icon(
                    if (charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    location.battery_level?.let {
                        "$it% · ${stringResource(if (charging) R.string.charging_label else R.string.battery_label)}"
                    } ?: stringResource(R.string.battery_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    myLocation?.let { me ->
                        val meters = distanceMeters(me.latitude, me.longitude, location.lat, location.lng)
                        stringResource(R.string.distance_from_you, formatDistance(meters))
                    } ?: stringResource(R.string.distance_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (location.wifi_connected == true) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when (location.wifi_connected) {
                        true -> location.wifi_ssid?.let { "${stringResource(R.string.wifi_connected_label)} ($it)" }
                            ?: stringResource(R.string.wifi_connected_label)
                        false -> stringResource(R.string.wifi_not_connected_label)
                        null -> stringResource(R.string.wifi_unknown_label)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Solo admins: meter esta wifi en las "wifis de casa" del miembro sin tener que
                // pedirle a él que entre en sus ajustes. Si ya la tiene (según su copia en la
                // nube) no hay botón: mandarla otra vez no haría nada y confunde.
                val ssid = location.wifi_ssid
                if (isAdmin && location.wifi_connected == true && !ssid.isNullOrBlank() &&
                    state.knownWifis?.contains(ssid) != true && state.knownWifiAdded != ssid
                ) {
                    IconButton(onClick = { addWifiSsid = ssid }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.AddCircle,
                            contentDescription = stringResource(R.string.wifi_add_known_button),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            if (isAdmin && location.wifi_connected == true && !location.wifi_ssid.isNullOrBlank()) {
                val ssid = location.wifi_ssid.orEmpty()
                val note = when {
                    state.knownWifis?.contains(ssid) == true -> R.string.wifi_add_known_already
                    state.knownWifiAdded == ssid -> R.string.wifi_add_known_pending
                    else -> null
                }
                note?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 28.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    style = MaterialTheme.typography.bodyMedium,
                    // Mientras alguien lo tenga en seguimiento en vivo, su móvil está en tiempo
                    // real mande lo que mande su ajuste: se tapa el valor configurado, no se
                    // toca — al caducar la marca vuelve a verse el suyo sin restaurar nada.
                    color = if (location.live_seconds > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    text = stringResource(
                        R.string.update_frequency_label,
                        if (location.live_seconds > 0) {
                            stringResource(R.string.frequency_short_live)
                        } else {
                            // Puede venir una frecuencia que esta versión de la app aún no conoce.
                            stringResource(
                                LocationFrequency.entries.find { it.name == location.location_frequency }
                                    ?.shortLabelRes ?: R.string.frequency_short_unknown,
                            )
                        },
                    ),
                )
            }
            // El ritmo elegido solo manda mientras esa persona se mueve: en reposo su móvil
            // espacia a una cada 15 minutos. Aquí va el estado de ahora, y tocándolo sale la
            // explicación entera (la misma ventana que en la lista de Gente).
            UpdateStatusSummary(location, Modifier.padding(start = 28.dp, top = 4.dp)) {
                showUpdateInfo = true
            }
            if (showUpdateInfo) {
                UpdateStatusDialog(location, onDismiss = { showUpdateInfo = false })
            }
            Spacer(Modifier.height(12.dp))
            // Arreglar permisos solo tiene sentido en la ficha de uno mismo: en la de otro
            // miembro, lo que hay que tocar es SU móvil, no este.
            ConfigStatusRow(
                raw = location.config_issues,
                onFix = if (location.user_id == locator.session.userId) onFixPermissions else null,
            )

            Spacer(Modifier.height(32.dp))

            // La única forma de pedir una ubicación al momento: está con el resto de acciones y
            // con el color de acento, que es la que más se usa.
            FilledTonalButton(
                onClick = { viewModel.requestFreshLocation() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.requestingLocation,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                if (state.requestingLocation) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.refresh_location))
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

            Spacer(Modifier.height(20.dp))
            // Aviso de "lleva X sin dar señal" de ESTA persona. Vive aquí y no en Ajustes porque
            // depende de quién sea: del móvil del crío que se queda sin batería a diario se
            // quiere saber, y del que trabaja en un sótano sin cobertura, no.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.member_silent_alert), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.member_silent_alert_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = notifySilent && location.user_id in silentAlertUsers,
                    // Apagado el ajuste general, esto no decide nada: se enseña apagado y no se
                    // deja tocar, en vez de mentir con un interruptor encendido que no avisa.
                    enabled = notifySilent,
                    onCheckedChange = {
                        scope.launch { locator.settings.setSilentAlertForUser(location.user_id, it) }
                    },
                )
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

    // El informe de bateria que acaba de subir su movil (o el ultimo que subiera, si no ha
    // contestado). Solo lo pide un admin, asi que aqui no hace falta volver a comprobarlo.
    state.batteryReport?.let { report ->
        BatteryReportDialog(
            memberName = state.location?.display_name ?: stringResource(R.string.member_detail_title),
            report = report,
            receivedAt = state.batteryReportAt,
            stale = state.batteryReportStale,
            onDismiss = { viewModel.dismissBatteryReport() },
        )
    }

    if (state.batteryReportFailed) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissBatteryReport() },
            title = { Text(stringResource(R.string.battery_report_action)) },
            text = { Text(stringResource(R.string.battery_report_failed)) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissBatteryReport() }) { Text(stringResource(R.string.close)) }
            },
        )
    }

    // Solo con posicion hay foto que mirar: sin ella esta pantalla ni llega a pintar el avatar.
    state.location?.takeIf { showPhoto }?.let { photoOf ->
        AvatarPreviewDialog(
            name = photoOf.display_name,
            avatarUrl = absoluteAvatarUrl(photoOf.avatar_url),
            onDismiss = { showPhoto = false },
        )
    }

    addWifiSsid?.let { ssid ->
        AlertDialog(
            onDismissRequest = { addWifiSsid = null },
            title = { Text(stringResource(R.string.wifi_add_known_title)) },
            text = {
                Text(
                    stringResource(R.string.wifi_add_known_body, ssid, state.location?.display_name.orEmpty()) +
                        // Sin copia en la nube suya no hay forma de saber qué wifis tiene ya: se
                        // dice, en vez de dar a entender que se ha comprobado.
                        if (state.knownWifis == null) "\n\n" + stringResource(R.string.wifi_add_known_unknown) else "",
                )
            },
            confirmButton = {
                TextButton(onClick = { addWifiSsid = null; viewModel.addKnownWifi(ssid) }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { addWifiSsid = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // El cambio ocurre en el otro móvil (va por push), así que aquí solo se puede confirmar que
    // la orden salió; si su móvil está apagado no llegará y habrá que repetirlo. El valor NO se
    // limpia: es también lo que deja la línea de "orden enviada" bajo la wifi.
    LaunchedEffect(state.knownWifiAdded) {
        state.knownWifiAdded?.let {
            Toast.makeText(context, context.getString(R.string.wifi_add_known_sent, it), Toast.LENGTH_LONG).show()
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

    // Mientras suena, el diálogo no se puede descartar tocando fuera: la única salida es
    // "Detener", que es también lo que para el sonido en el otro móvil.
    if (state.ringing || state.ringSent) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.ring_progress_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (state.ringing) {
                            stringResource(R.string.ring_progress_connecting)
                        } else {
                            stringResource(R.string.ring_progress_ringing, state.location?.display_name.orEmpty())
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showStopRingConfirm = true }, enabled = !state.ringing) {
                    Text(stringResource(R.string.ring_stop_action))
                }
            },
        )
    }

    if (showStopRingConfirm) {
        AlertDialog(
            onDismissRequest = { showStopRingConfirm = false },
            title = { Text(stringResource(R.string.ring_stop_confirm_title)) },
            text = { Text(stringResource(R.string.ring_stop_confirm_body, state.location?.display_name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = { showStopRingConfirm = false; viewModel.stopRing() }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopRingConfirm = false }) { Text(stringResource(R.string.cancel)) }
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

/**
 * Estado de configuración del móvil de ese miembro (permisos, batería, GPS del sistema). El
 * dato lo manda su propia app en cada ping y al arrancar, así que refleja la ÚLTIMA vez que
 * conectó — igual que la batería o el wifi de arriba, no es tiempo real.
 *
 * [raw] null = su app es anterior a esta versión y no lo manda; cadena vacía = todo correcto.
 */
@Composable
private fun ConfigStatusRow(raw: String?, onFix: (() -> Unit)? = null) {
    val issues = ConfigCheck.parse(raw)
    val icon = when {
        issues == null -> Icons.Filled.HelpOutline
        issues.isEmpty() -> Icons.Filled.CheckCircle
        else -> Icons.Filled.Warning
    }
    val tint = when {
        issues.isNullOrEmpty() -> LocalContentColor.current
        else -> MaterialTheme.colorScheme.error
    }

    // Lo de abajo es lo que mandó su móvil en el último ping, así que puede estar desfasado:
    // al tocar, el onboarding vuelve a comprobar de verdad lo que falta en ESTE momento.
    val fixable = onFix != null && !issues.isNullOrEmpty()
    Row(
        verticalAlignment = Alignment.Top,
        modifier = if (fixable) Modifier.fillMaxWidth().clickable(onClick = onFix!!) else Modifier,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                stringResource(
                    when {
                        issues == null -> R.string.config_status_unknown
                        issues.isEmpty() -> R.string.config_status_ok
                        else -> R.string.config_status_issues
                    },
                ),
                color = tint,
                style = MaterialTheme.typography.bodyMedium,
            )
            issues.orEmpty().forEach { issue ->
                Text(
                    "• " + stringResource(
                        when (issue) {
                            ConfigCheck.LOCATION -> R.string.config_issue_location
                            ConfigCheck.BACKGROUND_LOCATION -> R.string.config_issue_bg_location
                            ConfigCheck.NOTIFICATIONS -> R.string.config_issue_notifications
                            ConfigCheck.NOTIFICATION_CHANNEL -> R.string.config_issue_notif_channel
                            ConfigCheck.BATTERY -> R.string.config_issue_battery
                            ConfigCheck.DND -> R.string.config_issue_dnd
                            ConfigCheck.ACTIVITY -> R.string.config_issue_activity
                            else -> R.string.config_issue_gps_off
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (fixable) {
                Text(
                    stringResource(R.string.config_status_fix_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun AvatarPreviewDialog(name: String, avatarUrl: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.people_photo_view_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(240.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                )
                if (saving) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
                saveMessage?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = {
                    val url = avatarUrl ?: return@OutlinedButton
                    saving = true
                    scope.launch {
                        val ok = runCatching {
                            val file = MediaSaver.downloadToCache(context, url, "$name.jpg")
                            MediaSaver.saveToDevice(context, file, "image/jpeg", "$name.jpg")
                        }.getOrDefault(false)
                        saveMessage = context.getString(if (ok) R.string.emergency_media_saved else R.string.emergency_media_error)
                        saving = false
                    }
                },
                enabled = avatarUrl != null && !saving,
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.emergency_save_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
