package com.dskmusic.lokate.ui.settings

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.content.Intent
import android.net.Uri
import android.media.RingtoneManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Close
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
import com.dskmusic.lokate.data.offline.OfflineMaps
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
import com.dskmusic.lokate.ui.nav.NavOptionsDialog
import com.dskmusic.lokate.ui.theme.AccentPresets
import com.dskmusic.lokate.ui.theme.MapAccuracyPresets
import com.dskmusic.lokate.ui.theme.MapZoneMarginPresets
import com.dskmusic.lokate.ui.theme.MapZonePresets
import com.dskmusic.lokate.util.DeviceStatusUtils
import com.dskmusic.lokate.util.formatTimestamp
import com.dskmusic.lokate.util.LocationFrequency
import com.dskmusic.lokate.util.MapStyle
import com.dskmusic.lokate.util.PermissionUtils
import com.dskmusic.lokate.util.ThemeMode
import com.dskmusic.lokate.util.VibrationPattern

/** Los paneles de consumo de los dos proveedores de rutas e isocronas (ver backend routing.py).
 * Aquí y no en el servidor porque no son de la app: son las cuentas de quien la administra. */
private const val HEIGIT_DASHBOARD_URL = "https://account.heigit.org/manage/key"
private const val STADIA_DASHBOARD_URL = "https://client.stadiamaps.com/dashboard/overview/"

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    locator: ServiceLocator,
    onBack: () -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onLoggedOut: () -> Unit,
    onFixPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = remember {
        SettingsViewModel(locator.settings, locator.authRepository, locator.groupRepository, locator.zoneRepository, locator.locationRepository, locator.backupRepository, locator.adminRepository)
    }

    val themeMode by locator.settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val accentArgb by locator.settings.accentColor.collectAsStateWithLifecycle(initialValue = SettingsDataStore.DEFAULT_ACCENT)
    val frequency by locator.settings.locationFrequency.collectAsStateWithLifecycle(initialValue = LocationFrequency.EVERY_1_MIN)
    val knownWifi by locator.settings.knownWifiSsids.collectAsStateWithLifecycle(initialValue = emptySet())
    val wifiAnchor by locator.settings.wifiAnchor.collectAsStateWithLifecycle(initialValue = true)
    val currentSsid by remember { DeviceStatusUtils.wifiSsidFlow(context) }
        .collectAsStateWithLifecycle(initialValue = null)
    val placeSearchPelias by locator.settings.placeSearchPelias
        .collectAsStateWithLifecycle(initialValue = true)
    val notifyZone by locator.settings.notifyZoneEnabled.collectAsStateWithLifecycle(initialValue = true)
    val notifySystem by locator.settings.notifySystemEnabled.collectAsStateWithLifecycle(initialValue = true)
    val notifySilent by locator.settings.notifySilentEnabled.collectAsStateWithLifecycle(initialValue = true)
    val serverUrlOverride by locator.settings.serverBaseUrlOverride.collectAsStateWithLifecycle(initialValue = null)
    val testNotificationSent by viewModel.testNotificationSent.collectAsStateWithLifecycle()
    val ringSoundUri by locator.settings.ringSoundUri.collectAsStateWithLifecycle(initialValue = null)
    val vibrationPattern by locator.settings.vibrationPattern.collectAsStateWithLifecycle(initialValue = VibrationPattern.SOFT)
    val mapZoom by locator.settings.mapInitialZoom.collectAsStateWithLifecycle(initialValue = Constants.MAP_DEFAULT_ZOOM)
    val mapDefaultUserId by locator.settings.mapDefaultUserId.collectAsStateWithLifecycle(initialValue = "")
    val mapStyle by locator.settings.mapStyle.collectAsStateWithLifecycle(initialValue = MapStyle.STANDARD)
    val mapShowAccuracy by locator.settings.mapShowAccuracy.collectAsStateWithLifecycle(initialValue = true)
    val mapAccuracyIntensity by locator.settings.mapAccuracyIntensity
        .collectAsStateWithLifecycle(initialValue = Constants.MAP_ACCURACY_INTENSITY_DEFAULT)
    val mapAccuracyNames by locator.settings.mapAccuracyNames.collectAsStateWithLifecycle(initialValue = true)
    val mapZoneColor by locator.settings.mapZoneColor
        .collectAsStateWithLifecycle(initialValue = Constants.MAP_ZONE_COLOR_DEFAULT)
    val mapAccuracyColor by locator.settings.mapAccuracyColor
        .collectAsStateWithLifecycle(initialValue = Constants.MAP_ACCURACY_COLOR_DEFAULT)
    val mapZoneMarginColor by locator.settings.mapZoneMarginColor
        .collectAsStateWithLifecycle(initialValue = Constants.MAP_ZONE_MARGIN_COLOR_DEFAULT)
    val mapZoneFillIntensity by locator.settings.mapZoneFillIntensity
        .collectAsStateWithLifecycle(initialValue = Constants.MAP_ZONE_FILL_INTENSITY_DEFAULT)
    val mapShowZoneMargin by locator.settings.mapShowZoneMargin.collectAsStateWithLifecycle(initialValue = true)
    val appLanguage by locator.settings.appLanguage.collectAsStateWithLifecycle(initialValue = "auto")
    val mapCacheClearedBytes by viewModel.mapCacheClearedBytes.collectAsStateWithLifecycle()
    val updateFlagEnabled by viewModel.updateFlagEnabled.collectAsStateWithLifecycle()
    val updateFlagError by viewModel.updateFlagError.collectAsStateWithLifecycle()
    val groupMembers by viewModel.groupMembers.collectAsStateWithLifecycle()
    val backupState by viewModel.backup.collectAsStateWithLifecycle()

    var showRestoreConfirm by remember { mutableStateOf(false) }
    // Se mira al abrir Ajustes para poder enseñar de cuándo es la copia que hay arriba.
    LaunchedEffect(Unit) { viewModel.loadBackupInfo() }
    LaunchedEffect(backupState.message) {
        val message = backupState.message ?: return@LaunchedEffect
        val text = when (message) {
            SettingsViewModel.DONE_BACKUP -> context.getString(R.string.settings_backup_done)
            SettingsViewModel.DONE_RESTORE -> context.getString(R.string.settings_backup_restored)
            SettingsViewModel.DONE_EMPTY -> context.getString(R.string.settings_backup_none)
            else -> context.getString(R.string.settings_backup_error, message)
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        viewModel.clearBackupMessage()
    }

    // Selector personalizado, uno solo para los tres colores (acento, zonas y margen de
    // error): lo único que cambia entre ellos es de dónde sale el color y a dónde va.
    var customColorPicker by remember { mutableStateOf<Pair<Int, (Int) -> Unit>?>(null) }
    var showClearDataConfirm by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showResetZoomConfirm by remember { mutableStateOf(false) }
    var showMapDefaultPicker by remember { mutableStateOf(false) }
    var showDisableUpdatesConfirm by remember { mutableStateOf(false) }
    var showTestNotificationPicker by remember { mutableStateOf(false) }
    var showUpdateNotice by remember { mutableStateOf(false) }
    var showTestNotificationAllConfirm by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showNavOptions by remember { mutableStateOf(false) }
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
        // Los miembros del grupo los necesita todo el mundo, no solo los administradores: con
        // ellos se elige con quién se abre el mapa. /groups/me/members es de cualquier miembro
        // (y ya esconde a quien no deba verse), así que esto no ensancha nada por detrás.
        viewModel.loadGroupMembers()
        if (isAdmin) {
            viewModel.loadUpdateFlag()
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
                    TextButton(onClick = { showLogoutConfirm = true }) {
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
                ColorSwatchRow(
                    presets = AccentPresets,
                    selected = accentArgb,
                    onPick = { viewModel.setAccentColor(it) },
                    onCustom = { customColorPicker = accentArgb to { argb: Int -> viewModel.setAccentColor(argb) } },
                )
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
                            // Solo bajo demanda no manda nada por su cuenta: el servicio sobra,
                            // responde el push cuando alguien pide la ubicación.
                            if (freq.sendsPeriodicUpdates) {
                                LocationServiceController.ensureStarted(context)
                            } else {
                                LocationServiceController.stop(context)
                            }
                        }
                    }
                    LocationFrequency.entries.forEach { freq ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { pick(freq) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = frequency == freq, onClick = { pick(freq) })
                            Text(stringResource(freq.labelRes))
                        }
                    }
                }
            }

            item { HorizontalDivider() }
            item { SectionTitle(stringResource(R.string.settings_known_wifi)) }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        stringResource(R.string.settings_known_wifi_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    knownWifi.sorted().forEach { ssid ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(ssid, modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.setKnownWifiSsids(knownWifi - ssid) }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.delete))
                            }
                        }
                    }
                    val ssid = currentSsid
                    OutlinedButton(
                        onClick = { ssid?.let { viewModel.setKnownWifiSsids(knownWifi + it) } },
                        enabled = ssid != null && ssid !in knownWifi,
                    ) {
                        Text(
                            if (ssid == null) {
                                stringResource(R.string.settings_known_wifi_none)
                            } else {
                                stringResource(R.string.settings_known_wifi_add, ssid)
                            },
                        )
                    }
                    // Hay móviles en los que Android no suelta el nombre de la red ni con los
                    // permisos dados: ahí queda escribirlo a mano.
                    var manual by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it },
                        label = { Text(stringResource(R.string.settings_known_wifi_manual)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    viewModel.setKnownWifiSsids(knownWifi + manual.trim())
                                    manual = ""
                                },
                                enabled = manual.isNotBlank(),
                            ) {
                                Text(stringResource(R.string.settings_known_wifi_manual_add))
                            }
                        },
                    )
                }
            }

            // El servidor se lo cree mientras el móvil mande esa wifi en sus pings: no hace
            // falta volver a registrar el dispositivo al cambiarlo.
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_wifi_anchor)) },
                    supportingContent = { Text(stringResource(R.string.settings_wifi_anchor_desc)) },
                    trailingContent = {
                        Switch(
                            checked = wifiAnchor,
                            onCheckedChange = { scope.launch { locator.settings.setWifiAnchor(it) } },
                        )
                    },
                )
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
            // Lo que se ve al abrir la app. Es la primera vista y nada más: a partir de ahí el
            // mapa se mueve con la mano, y esto no sigue a nadie en vivo ni le pide la posición
            // a su móvil — se usa la última que haya mandado.
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_map_default_subject)) },
                    supportingContent = {
                        val nombre = groupMembers.firstOrNull { it.id == mapDefaultUserId }?.display_name
                        Text(nombre ?: stringResource(R.string.settings_map_default_subject_self))
                    },
                    modifier = Modifier.clickable { showMapDefaultPicker = true },
                )
            }
            // Quién contesta en el buscador del mapa. Está aquí por si Pelias contesta mal o
            // se queda sin cuota: con esto apagado se vuelve al buscador de siempre sin esperar
            // a nada.
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_place_search)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (placeSearchPelias) R.string.settings_place_search_pelias
                                else R.string.settings_place_search_nominatim,
                            ),
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = placeSearchPelias,
                            onCheckedChange = {
                                scope.launch { locator.settings.setPlaceSearchPelias(it) }
                            },
                        )
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_map_accuracy)) },
                    supportingContent = { Text(stringResource(R.string.settings_map_accuracy_desc)) },
                    trailingContent = {
                        Switch(
                            checked = mapShowAccuracy,
                            onCheckedChange = { scope.launch { locator.settings.setMapShowAccuracy(it) } },
                        )
                    },
                )
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        stringResource(R.string.settings_map_accuracy_intensity, mapAccuracyIntensity),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = mapAccuracyIntensity.toFloat(),
                        onValueChange = { scope.launch { locator.settings.setMapAccuracyIntensity(it.toInt()) } },
                        valueRange = 10f..100f,
                        steps = 8,
                        enabled = mapShowAccuracy,
                    )
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_map_accuracy_names)) },
                    supportingContent = { Text(stringResource(R.string.settings_map_accuracy_names_desc)) },
                    trailingContent = {
                        Switch(
                            checked = mapAccuracyNames,
                            onCheckedChange = { scope.launch { locator.settings.setMapAccuracyNames(it) } },
                            enabled = mapShowAccuracy,
                        )
                    },
                )
            }
            item {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.settings_map_accuracy_color),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    ColorSwatchRow(
                        presets = MapAccuracyPresets,
                        selected = mapAccuracyColor,
                        onPick = { scope.launch { locator.settings.setMapAccuracyColor(it) } },
                        onCustom = {
                            customColorPicker = mapAccuracyColor to { argb: Int ->
                                scope.launch { locator.settings.setMapAccuracyColor(argb) }
                            }
                        },
                    )
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        stringResource(R.string.settings_map_zone_fill, mapZoneFillIntensity),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.settings_map_zone_fill_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = mapZoneFillIntensity.toFloat(),
                        onValueChange = { scope.launch { locator.settings.setMapZoneFillIntensity(it.toInt()) } },
                        valueRange = 0f..100f,
                        steps = 9,
                    )
                }
            }
            item {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.settings_map_zone_color),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    ColorSwatchRow(
                        presets = MapZonePresets,
                        selected = mapZoneColor,
                        onPick = { scope.launch { locator.settings.setMapZoneColor(it) } },
                        onCustom = {
                            customColorPicker = mapZoneColor to { argb: Int ->
                                scope.launch { locator.settings.setMapZoneColor(argb) }
                            }
                        },
                    )
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_map_zone_margin)) },
                    supportingContent = {
                        Text(stringResource(
                            R.string.settings_map_zone_margin_desc,
                            Constants.ZONE_EXIT_MARGIN_METERS.toInt(),
                        ))
                    },
                    trailingContent = {
                        Switch(
                            checked = mapShowZoneMargin,
                            onCheckedChange = { scope.launch { locator.settings.setMapShowZoneMargin(it) } },
                        )
                    },
                )
            }
            item {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.settings_map_zone_margin_color),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    ColorSwatchRow(
                        presets = MapZoneMarginPresets,
                        selected = mapZoneMarginColor,
                        onPick = { scope.launch { locator.settings.setMapZoneMarginColor(it) } },
                        onCustom = {
                            customColorPicker = mapZoneMarginColor to { argb: Int ->
                                scope.launch { locator.settings.setMapZoneMarginColor(argb) }
                            }
                        },
                    )
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
            item { SectionTitle(stringResource(R.string.offline_maps_title)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.offline_maps_use)) },
                    supportingContent = { Text(stringResource(R.string.offline_maps_use_desc)) },
                    trailingContent = {
                        Switch(
                            checked = mapStyle.isOffline,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    // Se respeta si el mapa estaba en oscuro: el modo sin
                                    // conexión también lo tiene.
                                    locator.settings.setMapStyle(
                                        when {
                                            !enabled -> mapStyle.online
                                            mapStyle == MapStyle.DARK -> MapStyle.OFFLINE_DARK
                                            else -> MapStyle.OFFLINE
                                        },
                                    )
                                }
                                // Encenderlo sin nada descargado no sirve de nada: se va directo a
                                // elegir zona en vez de dejar que el aviso salte luego en el mapa.
                                if (enabled && OfflineMaps.files(context).isEmpty()) onOpenOfflineMaps()
                            },
                        )
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.offline_maps_manage)) },
                    supportingContent = { Text(stringResource(R.string.offline_maps_manage_desc)) },
                    modifier = Modifier.clickable { onOpenOfflineMaps() },
                )
            }
            item { Spacer(Modifier.height(20.dp)) }

            item { HorizontalDivider() }
            item { SectionTitle(stringResource(R.string.nav_options_title)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_nav_options)) },
                    supportingContent = { Text(stringResource(R.string.settings_nav_options_desc)) },
                    modifier = Modifier.clickable { showNavOptions = true },
                )
            }
            item { Spacer(Modifier.height(20.dp)) }

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
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_notifications_silent)) },
                    supportingContent = { Text(stringResource(R.string.settings_notifications_silent_desc)) },
                    trailingContent = {
                        Switch(
                            checked = notifySilent,
                            onCheckedChange = { scope.launch { locator.settings.setNotifySilentEnabled(it) } },
                        )
                    },
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
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_permissions_fix)) },
                    supportingContent = { Text(stringResource(R.string.settings_permissions_fix_desc)) },
                    modifier = Modifier.clickable { onFixPermissions() },
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
            item { SectionTitle(stringResource(R.string.settings_backup_section)) }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        stringResource(R.string.settings_backup_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val savedAt = backupState.savedAt
                    Text(
                        when {
                            savedAt != null -> stringResource(R.string.settings_backup_last, formatTimestamp(savedAt))
                            backupState.checked -> stringResource(R.string.settings_backup_last_never)
                            else -> stringResource(R.string.settings_backup_checking)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { viewModel.backupNow() }, enabled = !backupState.working) {
                            Text(stringResource(R.string.settings_backup_now))
                        }
                        TextButton(
                            onClick = { showRestoreConfirm = true },
                            enabled = !backupState.working && backupState.savedAt != null,
                        ) {
                            Text(stringResource(R.string.settings_backup_restore))
                        }
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
                        OutlinedButton(
                            onClick = {
                                viewModel.clearUpdateNotice()
                                viewModel.loadUpdateNoticeTargets()
                                showUpdateNotice = true
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            Text(stringResource(R.string.settings_update_notice))
                        }
                        // Los paneles de las dos cuentas de mapas, para ver cuánta cuota queda.
                        // En el navegador y no dentro de la app: hay que iniciar sesión en cada
                        // una, y la sesión del navegador ya la tiene quien las administra.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HEIGIT_DASHBOARD_URL)))
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.settings_usage_heigit))
                            }
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(STADIA_DASHBOARD_URL)))
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.settings_usage_stadia))
                            }
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

    customColorPicker?.let { (initial, onPicked) ->
        AlertDialog(
            onDismissRequest = { customColorPicker = null },
            confirmButton = { TextButton(onClick = { customColorPicker = null }) { Text(stringResource(R.string.onboarding_continue)) } },
            text = {
                ColorPickerHSV(
                    initialColor = Color(initial),
                    onColorChanged = { onPicked(it.toArgbCompat()) },
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

    // Salir borra de este móvil el historial y las zonas descargadas, y volver a entrar exige
    // la contraseña: no es un botón para pulsar sin querer al lado de la foto de perfil.
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(stringResource(R.string.settings_logout)) },
            text = { Text(stringResource(R.string.settings_logout_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    viewModel.logout(context) { onLoggedOut() }
                }) { Text(stringResource(R.string.settings_logout)) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text(stringResource(R.string.cancel)) }
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

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text(stringResource(R.string.settings_backup_restore)) },
            text = { Text(stringResource(R.string.settings_backup_restore_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    viewModel.restoreBackup()
                }) { Text(stringResource(R.string.settings_backup_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
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

    if (showUpdateNotice) {
        UpdateNoticeDialog(viewModel, onDismiss = { showUpdateNotice = false })
    }

    if (showMapDefaultPicker) {
        AlertDialog(
            onDismissRequest = { showMapDefaultPicker = false },
            title = { Text(stringResource(R.string.settings_map_default_subject)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.settings_map_default_subject_self),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showMapDefaultPicker = false
                                scope.launch { locator.settings.setMapDefaultUserId("") }
                            }
                            .padding(vertical = 12.dp),
                    )
                    HorizontalDivider()
                    // Uno mismo no sale en la lista: para eso está la opción de arriba.
                    groupMembers.filter { it.id != locator.session.userId }.forEach { member ->
                        Text(
                            member.display_name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showMapDefaultPicker = false
                                    scope.launch { locator.settings.setMapDefaultUserId(member.id) }
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMapDefaultPicker = false }) { Text(stringResource(R.string.cancel)) }
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

    // ponytail: el mismo cuadro que sale durante la ruta; aqui solo se abre sin ruta.
    if (showNavOptions) {
        NavOptionsDialog(locator.settings) { showNavOptions = false }
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
internal fun SectionTitle(text: String) {
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

/**
 * Fila de colores de ejemplo con el elegido marcado, y al final el disco de degradado que abre el
 * selector personalizado (sustituye al antiguo botón "+": mismo tamaño que el resto y se ve de un
 * vistazo lo que hace). La usan el acento de la app y los dos colores del mapa; el primero de cada
 * paleta es el color de siempre, así que quien no toque nada sigue viendo lo mismo.
 */
@Composable
private fun ColorSwatchRow(
    presets: List<Color>,
    selected: Int,
    onPick: (Int) -> Unit,
    onCustom: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { preset ->
            val argb = preset.toArgbCompat()
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(preset)
                    // Sin el borde no se sabe cuál de los siete está puesto; si el color es uno
                    // personalizado, no se marca ninguno y también se entiende.
                    .then(
                        if (argb == selected) {
                            Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onPick(argb) },
            )
        }
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
                .clickable { onCustom() },
        )
    }
}

private fun Color.toArgbCompat(): Int = this.toArgb()
