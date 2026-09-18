package com.dskmusic.lokate.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.absoluteAvatarUrl
import com.dskmusic.lokate.data.remote.absoluteMediaUrl
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.ui.help.HelpDialog
import com.dskmusic.lokate.util.AppUpdater
import com.dskmusic.lokate.util.LocationSharing
import com.dskmusic.lokate.util.MapStyle
import com.dskmusic.lokate.util.UpdateCheckState
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    locator: ServiceLocator,
    onOpenZones: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGroup: () -> Unit,
    onOpenMember: (String) -> Unit,
    onOpenAdmin: () -> Unit = {},
    focusUserIdFlow: StateFlow<String?>? = null,
    onFocusUserIdConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    var isAdmin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isAdmin = runCatching { locator.authRepository.me() }.getOrNull()?.is_admin == true
    }
    val viewModel = remember { MapViewModel(locator.locationRepository, locator.zoneRepository, locator.groupRepository) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val zones by viewModel.zones.collectAsStateWithLifecycle(initialValue = emptyList())
    val avatarBitmaps = rememberAvatarBitmaps(state.members)
    val initialZoom by locator.settings.mapInitialZoom.collectAsStateWithLifecycle(initialValue = 20)
    val mapStyle by locator.settings.mapStyle.collectAsStateWithLifecycle(initialValue = MapStyle.STANDARD)
    val scope = rememberCoroutineScope()

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    var showMapStyleMenu by remember { mutableStateOf(false) }
    var showFollowMenu by remember { mutableStateOf(false) }
    // Leído de MapCameraMemory (no arranca siempre en null): si ya estabas siguiendo a alguien
    // y cambias de pestaña, al volver el seguimiento sigue activo tal cual lo dejaste.
    var followUserId by remember { mutableStateOf(MapCameraMemory.followUserId) }

    // Modo "seguir en vivo": mientras haya alguien elegido, recentra el mapa cada vez que
    // llega una ubicación nueva suya (mismo ciclo de sondeo que ya alimenta los marcadores).
    // Puede ser cualquier miembro del grupo, incluido uno mismo.
    LaunchedEffect(followUserId, state.members, mapViewRef) {
        val uid = followUserId ?: return@LaunchedEffect
        val map = mapViewRef ?: return@LaunchedEffect
        val followed = state.members.find { it.user_id == uid } ?: return@LaunchedEffect
        map.controller.animateTo(GeoPoint(followed.lat, followed.lng))
    }

    var showUpdateDialog by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }

    // Miembro elegido en la pestaña "Gente": centra el mapa en su posición más reciente.
    val focusUserId = focusUserIdFlow?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(focusUserId, state.members, mapViewRef) {
        val id = focusUserId ?: return@LaunchedEffect
        val member = state.members.find { it.user_id == id } ?: return@LaunchedEffect
        mapViewRef?.controller?.animateTo(GeoPoint(member.lat, member.lng))
        onFocusUserIdConsumed()
    }

    // Refresca el estado del grupo/miembros cada vez que se vuelve a esta pantalla
    // (por ejemplo, al volver de crear/unirse a un grupo). Mismo momento para comprobar
    // actualizaciones: Android no mata el proceso al "cerrar" la app con atrás/inicio, así que
    // un arranque en frío real es poco frecuente — pasar a primer plano (ON_RESUME) es la
    // señal fiable de "se acaba de abrir la app". Se vuelve a preguntar en cada apertura
    // mientras el servidor siga marcando que hay actualización, no solo la primera vez.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
                scope.launch {
                    UpdateCheckState.check(locator.authRepository)
                    if (UpdateCheckState.updateAvailable.value) showUpdateDialog = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // El mapa es la pantalla de inicio: aquí el botón atrás no debe cerrar la app de golpe,
    // sino preguntar primero (en el resto de pantallas, atrás simplemente deshace la
    // navegación por defecto de Jetpack Navigation, sin necesitar nada extra).
    var showExitConfirm by remember { mutableStateOf(false) }
    BackHandler { showExitConfirm = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = {
                    IconButton(onClick = onOpenGroup) {
                        Icon(Icons.Filled.Group, contentDescription = stringResource(R.string.group_title))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMapStyleMenu = true }) {
                            Icon(Icons.Filled.Layers, contentDescription = stringResource(R.string.map_style_button))
                        }
                        DropdownMenu(expanded = showMapStyleMenu, onDismissRequest = { showMapStyleMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.map_style_standard)) },
                                leadingIcon = { Icon(Icons.Filled.Map, contentDescription = null) },
                                onClick = {
                                    scope.launch { locator.settings.setMapStyle(MapStyle.STANDARD) }
                                    showMapStyleMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.map_style_satellite)) },
                                leadingIcon = { Icon(Icons.Filled.Satellite, contentDescription = null) },
                                onClick = {
                                    scope.launch { locator.settings.setMapStyle(MapStyle.SATELLITE) }
                                    showMapStyleMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.map_style_dark)) },
                                leadingIcon = { Icon(Icons.Filled.DarkMode, contentDescription = null) },
                                onClick = {
                                    scope.launch { locator.settings.setMapStyle(MapStyle.DARK) }
                                    showMapStyleMenu = false
                                },
                            )
                        }
                    }
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Filled.HelpOutline, contentDescription = stringResource(R.string.help_title))
                    }
                    if (isAdmin) {
                        IconButton(onClick = onOpenAdmin) {
                            Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.admin_title))
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Filled.Map, contentDescription = null) },
                    label = { Text(stringResource(R.string.map_title)) },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onOpenPeople,
                    icon = { Icon(Icons.Filled.People, contentDescription = null) },
                    label = { Text(stringResource(R.string.people_title)) },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onOpenZones,
                    icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                    label = { Text(stringResource(R.string.zones_title)) },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onOpenHistory,
                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                    label = { Text(stringResource(R.string.history_title)) },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onOpenSettings,
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.settings_title)) },
                )
            }
        },
    ) { padding ->
        if (!state.checkedGroup) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }

        if (state.connectionError) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.CloudOff, contentDescription = null, modifier = Modifier.size(48.dp))
                Spacer(Modifier.padding(8.dp))
                Text(
                    stringResource(R.string.connection_error_body),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.padding(8.dp))
                Button(onClick = { viewModel.refresh() }) { Text(stringResource(R.string.retry)) }
            }
            return@Scaffold
        }

        if (state.group == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.map_no_group_prompt), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.padding(8.dp))
                Button(onClick = onOpenGroup) { Text(stringResource(R.string.map_go_to_group)) }
            }
            return@Scaffold
        }

        Box(Modifier.fillMaxSize().padding(padding)) {
            OsmMapView(
                members = state.members,
                zones = zones,
                modifier = Modifier.fillMaxSize(),
                avatarBitmaps = avatarBitmaps,
                initialZoom = initialZoom.toDouble(),
                mapStyle = mapStyle,
                rememberCamera = true,
                onMemberClick = { member -> onOpenMember(member.user_id) },
                onMapReady = { view ->
                    mapViewRef = view
                    // Al abrir la app el mapa debe mostrar ya la posición actual, sin tener que
                    // tocar el botón "Dónde estoy" primero — pero solo la primera vez que se
                    // abre el mapa en esta sesión: si ya había una posición guardada (venimos de
                    // cambiar de pestaña, no de abrir la app), OsmMapView ya la restauró solo y
                    // no hay que recentrar sobre la ubicación actual por encima.
                    if (MapCameraMemory.center == null) {
                        locateMe(context) { point -> view.controller.setCenter(point) }
                    }
                },
            )
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            // Botón "dónde estoy" + seguir en vivo (a quien se elija), a la izquierda, para
            // que no se solape con los de la derecha.
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box {
                    SmallFloatingActionButton(
                        onClick = { showFollowMenu = true },
                        containerColor = if (followUserId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    ) {
                        Icon(
                            if (followUserId != null) Icons.Filled.GpsFixed else Icons.Filled.GpsOff,
                            contentDescription = stringResource(R.string.map_follow_me),
                            tint = if (followUserId != null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    DropdownMenu(expanded = showFollowMenu, onDismissRequest = { showFollowMenu = false }) {
                        if (followUserId != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.map_follow_stop)) },
                                leadingIcon = { Icon(Icons.Filled.GpsOff, contentDescription = null) },
                                onClick = {
                                    followUserId = null
                                    MapCameraMemory.followUserId = null
                                    showFollowMenu = false
                                },
                            )
                        }
                        state.members.forEach { member ->
                            val isSelf = member.user_id == locator.session.userId
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isSelf) stringResource(R.string.map_follow_you, member.display_name)
                                        else member.display_name,
                                    )
                                },
                                leadingIcon = {
                                    AsyncImage(
                                        model = absoluteAvatarUrl(member.avatar_url),
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp).clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                    )
                                },
                                trailingIcon = {
                                    if (member.user_id == followUserId) {
                                        Icon(Icons.Filled.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    followUserId = member.user_id
                                    MapCameraMemory.followUserId = member.user_id
                                    showFollowMenu = false
                                },
                            )
                        }
                    }
                }
                FloatingActionButton(
                    onClick = { locateMe(context) { point -> mapViewRef?.controller?.animateTo(point) } },
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.locate_me))
                }
            }

            // Invitar (arriba) y compartir ubicación (abajo), apiladas en la derecha.
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SmallFloatingActionButton(onClick = {
                    state.group?.let { context.startActivity(LocationSharing.inviteIntent(it.name, it.invite_code)) }
                }) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = stringResource(R.string.group_invite_button))
                }
                FloatingActionButton(onClick = { shareCurrentLocation(context) }) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share_current_location))
                }
            }
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text(stringResource(R.string.exit_app_confirm_title)) },
            text = { Text(stringResource(R.string.exit_app_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { (context as? Activity)?.finish() }) {
                    Text(stringResource(R.string.exit_app_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showHelp) {
        HelpDialog(onDismiss = { showHelp = false })
    }

    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { if (!updating) showUpdateDialog = false },
            title = { Text(stringResource(R.string.update_available_title)) },
            text = {
                if (updating) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
                        Text(stringResource(R.string.update_available_downloading))
                    }
                } else {
                    Text(stringResource(R.string.update_available_body))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !updating,
                    onClick = {
                        if (!AppUpdater.canRequestInstall(context)) {
                            AppUpdater.openInstallPermissionSettings(context)
                            return@TextButton
                        }
                        updating = true
                        scope.launch {
                            val url = absoluteMediaUrl("/lokate.apk").orEmpty()
                            runCatching { AppUpdater.downloadAndInstall(context, url) }
                            updating = false
                            showUpdateDialog = false
                        }
                    },
                ) { Text(stringResource(R.string.update_available_now)) }
            },
            dismissButton = {
                TextButton(enabled = !updating, onClick = { showUpdateDialog = false }) {
                    Text(stringResource(R.string.update_available_later))
                }
            },
        )
    }
}

@SuppressLint("MissingPermission")
private fun locateMe(context: Context, onFound: (GeoPoint) -> Unit) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        return
    }
    LocationServices.getFusedLocationProviderClient(context).lastLocation
        .addOnSuccessListener { location ->
            if (location != null) onFound(GeoPoint(location.latitude, location.longitude))
        }
}

@SuppressLint("MissingPermission")
private fun shareCurrentLocation(context: Context) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        return
    }
    LocationServices.getFusedLocationProviderClient(context).lastLocation.addOnSuccessListener { location ->
        if (location != null) {
            context.startActivity(LocationSharing.shareIntent(location.latitude, location.longitude))
        }
    }
}
