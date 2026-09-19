package com.dskmusic.lokate.ui.settings

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.content.Intent
import android.media.RingtoneManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dskmusic.lokate.BuildConfig
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.prefs.SettingsDataStore
import com.dskmusic.lokate.data.remote.absoluteAvatarUrl
import com.dskmusic.lokate.data.remote.absoluteMediaUrl
import com.dskmusic.lokate.ui.common.AppFooter
import com.dskmusic.lokate.util.AppUpdater
import com.dskmusic.lokate.util.Constants
import kotlinx.coroutines.launch
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.location.LocationServiceController
import com.dskmusic.lokate.ui.common.AvatarPicker
import com.dskmusic.lokate.ui.theme.AccentPresets
import com.dskmusic.lokate.util.LocationFrequency
import com.dskmusic.lokate.util.PermissionUtils
import com.dskmusic.lokate.util.ThemeMode
import com.dskmusic.lokate.util.VibrationPattern

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(locator: ServiceLocator, onBack: () -> Unit, onLoggedOut: () -> Unit) {
    val context = LocalContext.current
    val viewModel = remember {
        SettingsViewModel(locator.settings, locator.authRepository, locator.groupRepository, locator.zoneRepository, locator.locationRepository)
    }

    val themeMode by locator.settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val accentArgb by locator.settings.accentColor.collectAsStateWithLifecycle(initialValue = SettingsDataStore.DEFAULT_ACCENT)
    val frequency by locator.settings.locationFrequency.collectAsStateWithLifecycle(initialValue = LocationFrequency.REAL_TIME)
    val notifyZone by locator.settings.notifyZoneEnabled.collectAsStateWithLifecycle(initialValue = true)
    val notifySystem by locator.settings.notifySystemEnabled.collectAsStateWithLifecycle(initialValue = true)
    val serverUrlOverride by locator.settings.serverBaseUrlOverride.collectAsStateWithLifecycle(initialValue = null)
    val testNotificationSent by viewModel.testNotificationSent.collectAsStateWithLifecycle()
    val ringSoundUri by locator.settings.ringSoundUri.collectAsStateWithLifecycle(initialValue = null)
    val vibrationPattern by locator.settings.vibrationPattern.collectAsStateWithLifecycle(initialValue = VibrationPattern.SOFT)
    val mapZoom by locator.settings.mapInitialZoom.collectAsStateWithLifecycle(initialValue = Constants.MAP_DEFAULT_ZOOM)
    val appLanguage by locator.settings.appLanguage.collectAsStateWithLifecycle(initialValue = "auto")
    val mapCacheClearedBytes by viewModel.mapCacheClearedBytes.collectAsStateWithLifecycle()
    val updateFlagEnabled by viewModel.updateFlagEnabled.collectAsStateWithLifecycle()
    val updateFlagError by viewModel.updateFlagError.collectAsStateWithLifecycle()
    val groupMembers by viewModel.groupMembers.collectAsStateWithLifecycle()

    var showColorPicker by remember { mutableStateOf(false) }
    var showClearDataConfirm by remember { mutableStateOf(false) }
    var showResetZoomConfirm by remember { mutableStateOf(false) }
    var showDisableUpdatesConfirm by remember { mutableStateOf(false) }
    var showTestNotificationPicker by remember { mutableStateOf(false) }
    var showTestNotificationAllConfirm by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var updating by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var currentAvatarUrl by remember { mutableStateOf<String?>(null) }
    var currentDisplayName by remember { mutableStateOf("") }
    var isAdmin by remember { mutableStateOf(false) }
    var serverUrlInput by remember(serverUrlOverride) {
        mutableStateOf(serverUrlOverride ?: BuildConfig.API_BASE_URL)
    }
    LaunchedEffect(Unit) {
        val me = runCatching { locator.authRepository.me() }.getOrNull()
        currentAvatarUrl = me?.avatar_url?.let { absoluteAvatarUrl(it) }
        currentDisplayName = me?.display_name.orEmpty()
        isAdmin = me?.is_admin == true
        if (isAdmin) {
            viewModel.loadUpdateFlag()
            viewModel.loadGroupMembers()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { SectionTitle(stringResource(R.string.settings_account)) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AvatarPicker(
                        localPreviewUri = null,
                        remoteAvatarUrl = currentAvatarUrl,
                        size = 64.dp,
                        onCropped = { viewModel.uploadAvatar(it, context); currentAvatarUrl = it.toString() },
                    )
                    TextButton(onClick = { viewModel.logout(context) { onLoggedOut() } }) {
                        Text(stringResource(R.string.settings_logout))
                    }
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_display_name)) },
                    supportingContent = { Text(currentDisplayName) },
                    modifier = Modifier.clickable { showEditNameDialog = true },
                )
            }

            item { HorizontalDivider() }
            item { SectionTitle(stringResource(R.string.settings_theme)) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(themeModeLabel(mode), maxLines = 1) },
                        )
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.settings_language)) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "auto" to stringResource(R.string.settings_language_auto),
                        "es" to stringResource(R.string.settings_language_es),
                        "en" to stringResource(R.string.settings_language_en),
                    ).forEach { (code, label) ->
                        FilterChip(
                            selected = appLanguage == code,
                            onClick = {
                                viewModel.setAppLanguage(code)
                                (context as? Activity)?.recreate()
                            },
                            label = { Text(label, maxLines = 1) },
                        )
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.settings_accent_color)) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AccentPresets.forEach { preset ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(preset)
                                .clickable { viewModel.setAccentColor(preset.toArgbCompat()) },
                        )
                    }
                    // Sustituye al antiguo swatch "+" — mismo tamaño que el resto, y el degradado
                    // deja claro de un vistazo que abre el selector de color personalizado.
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.sweepGradient(
                                    listOf(
                                        Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
                                        Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
                                    ),
                                ),
                            )
                            .clickable { showColorPicker = true },
                    )
                }
            }
            item { Spacer(Modifier.height(20.dp)) }

            item { HorizontalDivider() }
            item { SectionTitle(stringResource(R.string.settings_update_frequency)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    // Desactivar pide confirmación; volver a una frecuencia normal rearranca el
                    // servicio en el momento, sin esperar al worker de respaldo.
                    val pick: (LocationFrequency) -> Unit = { freq ->
                        if (freq == LocationFrequency.DISABLED) {
                            showDisableUpdatesConfirm = true
                        } else {
                            viewModel.setLocationFrequency(freq)
                            LocationServiceController.ensureStarted(context)
                        }
                    }
                    listOf(
                        LocationFrequency.REAL_TIME to stringResource(R.string.frequency_high),
                        LocationFrequency.BALANCED to stringResource(R.string.frequency_balanced),
                        LocationFrequency.BATTERY_SAVER to stringResource(R.string.frequency_battery_saver),
                        LocationFrequency.DISABLED to stringResource(R.string.frequency_disabled),
                    ).forEach { (freq, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { pick(freq) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = frequency == freq, onClick = { pick(freq) })
                            Text(label)
                        }
                    }
                }
            }

            item { HorizontalDivider() }
            item { SectionTitle(stringResource(R.string.settings_map_zoom)) }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        stringResource(R.string.settings_map_zoom_value, mapZoom),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // Del 19 para arriba las teselas ya son aproximadas (ver MAP_DEFAULT_ZOOM):
                    // se puede elegir, pero abrir ahí carga más lento y con menos nitidez.
                    Slider(
                        value = mapZoom.toFloat(),
                        onValueChange = { viewModel.setMapInitialZoom(it.toInt()) },
                        valueRange = 3f..Constants.MAP_MAX_ZOOM.toFloat(),
                        steps = Constants.MAP_MAX_ZOOM - 4,
                    )
                    OutlinedButton(onClick = { showResetZoomConfirm = true }) {
                        Text(stringResource(R.string.settings_map_zoom_reset))
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        stringResource(R.string.settings_map_cache_desc, Constants.MAP_CACHE_MAX_AGE_DAYS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { viewModel.clearMapCache(context) }) {
                        Text(stringResource(R.string.settings_map_cache_clear))
                    }
                    mapCacheClearedBytes?.let { bytes ->
                        Text(
                            stringResource(R.string.settings_map_cache_cleared, bytes / 1024.0 / 1024.0),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            item { HorizontalDivider() }
            item { SectionTitle(stringResource(R.string.settings_notifications)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_notifications_zone)) },
                    trailingContent = { Switch(checked = notifyZone, onCheckedChange = viewModel::setNotifyZone) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_notifications_system)) },
                    trailingContent = { Switch(checked = notifySystem, onCheckedChange = viewModel::setNotifySystem) },
                )
            }
            item { Spacer(Modifier.height(20.dp)) }

            item { HorizontalDivider() }
            item { SectionTitle(stringResource(R.string.settings_permissions)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_permissions)) },
                    modifier = Modifier.clickable { context.startActivity(PermissionUtils.appSettingsIntent(context)) },
                )
            }

            item { HorizontalDivider() }
            item { SectionTitle(stringResource(R.string.settings_ring_section)) }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        stringResource(R.string.settings_ring_section_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    val notificationDefaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.toString()
                    val ringPresets = listOf(
                        Constants.RING_SOUND_ALARM_DEFAULT to stringResource(R.string.settings_ring_sound_alarm),
                        notificationDefaultUri to stringResource(R.string.settings_ring_sound_notification),
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)?.toString()
                            to stringResource(R.string.settings_ring_sound_ringtone),
                    )
                    // null (el ajuste nunca se ha tocado) se trata como "sonido de notificación",
                    // que es el nuevo valor por defecto — así el chip correcto sale ya marcado.
                    val effectiveRingSoundUri = ringSoundUri ?: notificationDefaultUri
                    val pickRingtone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                        val uri = result.data?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                        viewModel.setRingSoundUri(uri?.toString())
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ringPresets.forEach { (uri, label) ->
                            FilterChip(
                                selected = effectiveRingSoundUri == uri,
                                onClick = { viewModel.setRingSoundUri(uri) },
                                label = { Text(label, maxLines = 1) },
                            )
                        }
                        FilterChip(
                            selected = effectiveRingSoundUri != null && ringPresets.none { it.first == effectiveRingSoundUri },
                            onClick = {
                                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                                }
                                pickRingtone.launch(intent)
                            },
                            label = { Text(stringResource(R.string.settings_ring_sound_custom), maxLines = 1) },
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.settings_vibration_pattern), style = MaterialTheme.typography.titleMedium)
                    listOf(
                        VibrationPattern.SOFT to stringResource(R.string.vibration_soft),
                        VibrationPattern.STRONG to stringResource(R.string.vibration_strong),
                        VibrationPattern.SOS to stringResource(R.string.vibration_sos),
                        VibrationPattern.HEARTBEAT to stringResource(R.string.vibration_heartbeat),
                    ).forEach { (pattern, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.setVibrationPattern(pattern) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = vibrationPattern == pattern, onClick = { viewModel.setVibrationPattern(pattern) })
                            Text(label)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { viewModel.testRing(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_ring_test))
                    }
                }
            }

            item { HorizontalDivider() }
            item { SectionTitle(stringResource(R.string.settings_server_url)) }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = serverUrlInput,
                        onValueChange = { serverUrlInput = it },
                        placeholder = { Text(stringResource(R.string.settings_server_url_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { viewModel.setServerBaseUrl(serverUrlInput) }) {
                            Text(stringResource(R.string.settings_server_url_save))
                        }
                        TextButton(onClick = {
                            serverUrlInput = BuildConfig.API_BASE_URL
                            viewModel.setServerBaseUrl(null)
                        }) {
                            Text(stringResource(R.string.settings_server_url_reset))
                        }
                    }
                }
            }

            item { HorizontalDivider() }
            item { SectionTitle(stringResource(R.string.settings_update_section)) }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    OutlinedButton(
                        onClick = {
                            updating = true
                            updateMessage = null
                            scope.launch {
                                if (!AppUpdater.canRequestInstall(context)) {
                                    AppUpdater.openInstallPermissionSettings(context)
                                    updating = false
                                    return@launch
                                }
                                val url = absoluteMediaUrl("/lokate.apk").orEmpty()
                                runCatching { AppUpdater.downloadAndInstall(context, url) }
                                    .onFailure { updateMessage = it.message }
                                updating = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !updating,
                    ) {
                        Text(stringResource(R.string.settings_update_button))
                    }
                    if (updating) {
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    updateMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (isAdmin) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_update_flag_title)) },
                        supportingContent = { Text(stringResource(R.string.settings_update_flag_desc)) },
                        trailingContent = {
                            if (updateFlagEnabled == null) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            } else {
                                Switch(
                                    checked = updateFlagEnabled == true,
                                    onCheckedChange = { viewModel.setUpdateFlag(it) },
                                )
                            }
                        },
                    )
                }
                updateFlagError?.let {
                    item {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }

            if (isAdmin) {
                item { HorizontalDivider() }
                item { SectionTitle(stringResource(R.string.settings_admin_section)) }
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        OutlinedButton(
                            onClick = { showTestNotificationPicker = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.settings_send_test_notification))
                        }
                        if (testNotificationSent) {
                            Text(
                                stringResource(R.string.settings_test_notification_sent),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }

            item { HorizontalDivider() }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_clear_local_data)) },
                    modifier = Modifier.clickable { showClearDataConfirm = true },
                )
            }

            item { AppFooter() }
        }
    }

    if (showColorPicker) {
        AlertDialog(
            onDismissRequest = { showColorPicker = false },
            confirmButton = { TextButton(onClick = { showColorPicker = false }) { Text(stringResource(R.string.onboarding_continue)) } },
            text = {
                ColorPickerHSV(
                    initialColor = Color(accentArgb),
                    onColorChanged = { viewModel.setAccentColor(it.toArgbCompat()) },
                )
            },
        )
    }

    if (showEditNameDialog) {
        var nameInput by remember { mutableStateOf(currentDisplayName) }
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = { Text(stringResource(R.string.settings_display_name)) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEditNameDialog = false
                        viewModel.updateDisplayName(nameInput.trim()) { currentDisplayName = nameInput.trim() }
                    },
                    enabled = nameInput.isNotBlank(),
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showResetZoomConfirm) {
        AlertDialog(
            onDismissRequest = { showResetZoomConfirm = false },
            title = { Text(stringResource(R.string.settings_map_zoom_reset)) },
            text = { Text(stringResource(R.string.settings_map_zoom_reset_confirm, Constants.MAP_DEFAULT_ZOOM)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetZoomConfirm = false
                    viewModel.resetMapInitialZoom()
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetZoomConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showClearDataConfirm) {
        AlertDialog(
            onDismissRequest = { showClearDataConfirm = false },
            title = { Text(stringResource(R.string.settings_clear_local_data)) },
            text = { Text(stringResource(R.string.settings_clear_local_data_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearDataConfirm = false
                    viewModel.clearLocalData { onLoggedOut() }
                }) { Text(stringResource(R.string.settings_clear_local_data)) }
            },
            dismissButton = { TextButton(onClick = { showClearDataConfirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showDisableUpdatesConfirm) {
        AlertDialog(
            onDismissRequest = { showDisableUpdatesConfirm = false },
            title = { Text(stringResource(R.string.frequency_disabled_confirm_title)) },
            text = { Text(stringResource(R.string.frequency_disabled_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisableUpdatesConfirm = false
                    viewModel.setLocationFrequency(LocationFrequency.DISABLED)
                    LocationServiceController.stop(context)
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisableUpdatesConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showTestNotificationPicker) {
        AlertDialog(
            onDismissRequest = { showTestNotificationPicker = false },
            title = { Text(stringResource(R.string.settings_test_notification_pick)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    groupMembers.forEach { member ->
                        Text(
                            member.display_name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showTestNotificationPicker = false
                                    viewModel.sendTestNotification(listOf(member.id))
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.settings_test_notification_all),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showTestNotificationPicker = false
                                showTestNotificationAllConfirm = true
                            }
                            .padding(vertical = 12.dp),
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTestNotificationPicker = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showTestNotificationAllConfirm) {
        AlertDialog(
            onDismissRequest = { showTestNotificationAllConfirm = false },
            title = { Text(stringResource(R.string.settings_test_notification_all_confirm_title)) },
            text = {
                Text(stringResource(R.string.settings_test_notification_all_confirm_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    showTestNotificationAllConfirm = false
                    viewModel.sendTestNotification(null)
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showTestNotificationAllConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
    ThemeMode.AMOLED -> stringResource(R.string.settings_theme_amoled)
    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
}

private fun Color.toArgbCompat(): Int = this.toArgb()
