package com.dskmusic.lokate.ui.map

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.absoluteAvatarUrl
import com.dskmusic.lokate.data.remote.absoluteMediaUrl
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.ui.common.PlaceSearchField
import com.dskmusic.lokate.ui.common.lastKnownLocation
import com.dskmusic.lokate.ui.help.HelpDialog
import com.dskmusic.lokate.util.AppUpdater
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.util.LocationSharing
import com.dskmusic.lokate.util.MapStyle
import com.dskmusic.lokate.util.UpdateCheckState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/** Pulsación larga sobre el candado para entrar en el modo prueba. Deliberadamente muy por
 * encima del medio segundo de una pulsación larga normal: es un modo de pruebas, no algo que
 * se deba poder activar sin querer. */
private const val TEST_MODE_LONG_PRESS_MS = 1_500L

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    locator: ServiceLocator,
    onOpenZones: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOfflineMaps: () -> Unit,
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
    // viewModel() y no remember{} como el resto de pantallas: este ViewModel tiene dos bucles
    // infinitos (sondeo y renovación del seguimiento) y con remember nunca se llama a
    // onCleared, así que cada visita a la pestaña dejaba uno nuevo corriendo para siempre.
    // Atado a la entrada del NavHost, además, sigue vivo mientras el mapa esté en la pila: ir
    // a Gente o a Ajustes y volver ya no corta el seguimiento en vivo.
    val viewModel: MapViewModel = viewModel {
        MapViewModel(locator.locationRepository, locator.zoneRepository, locator.groupRepository)
    }
    PollWhileVisible(viewModel)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val zones by viewModel.zones.collectAsStateWithLifecycle(initialValue = emptyList())
    val avatarBitmaps = rememberAvatarBitmaps(state.members)
    val initialZoom by locator.settings.mapInitialZoom.collectAsStateWithLifecycle(initialValue = Constants.MAP_DEFAULT_ZOOM)
    val mapStyle by locator.settings.mapStyle.collectAsStateWithLifecycle(initialValue = MapStyle.STANDARD)
    val mapShowAccuracy by locator.settings.mapShowAccuracy.collectAsStateWithLifecycle(initialValue = false)
    val mapAccuracyIntensity by locator.settings.mapAccuracyIntensity
        .collectAsStateWithLifecycle(initialValue = Constants.MAP_ACCURACY_INTENSITY_DEFAULT)
    val scope = rememberCoroutineScope()
    val offlineMapFiles = rememberOfflineMapFiles()

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    var showFollowMenu by remember { mutableStateOf(false) }
    // El seguimiento vive en el ViewModel (sobrevive a cambiar de pestaña) y le pone el móvil
    // al seguido en tiempo real mientras dure; al soltarlo vuelve a su ritmo de siempre.
    val followUserId = state.followUserId
    val followed = state.members.find { it.user_id == followUserId }

    // Modo "seguir en vivo": mientras haya alguien elegido, recentra el mapa cada vez que
    // llega una ubicación nueva suya (mismo ciclo de sondeo que ya alimenta los marcadores).
    // Puede ser cualquier miembro del grupo, incluido uno mismo.
    LaunchedEffect(followUserId, followed?.timestamp, mapViewRef) {
        val map = mapViewRef ?: return@LaunchedEffect
        val target = followed ?: return@LaunchedEffect
        map.moveTo(GeoPoint(target.lat, target.lng))
    }

    // Acuse de recibo del "seguir": el "en vivo" solo llega cuando el otro móvil está pingando
    // de verdad, así que sirve para decir si la orden prendió allí o se quedó por el camino.
    LaunchedEffect(Unit) {
        viewModel.followFeedback.collect { feedback ->
            val text = when (feedback) {
                FollowFeedback.CONFIRMED -> context.getString(R.string.follow_live_confirmed)
                FollowFeedback.UNCONFIRMED -> context.getString(R.string.follow_live_unconfirmed)
                FollowFeedback.AUTO_STOPPED ->
                    context.getString(R.string.follow_live_auto_stopped, FOLLOW_MAX_MS / 60_000L)
            }
            val length = if (feedback == FollowFeedback.CONFIRMED) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            Toast.makeText(context, text, length).show()
        }
    }

    // Modo prueba (solo admins): se guarda fuera de la composición porque cambiar de pestaña
    // destruye esta pantalla y su ViewModel - si no, el modo se apagaría en la app pero el
    // servidor seguiría con las posiciones simuladas hasta que caducaran solas.
    var testMode by remember { mutableStateOf(MapCameraMemory.testMode) }
    var showRecipientsMenu by remember { mutableStateOf(false) }
    val testRecipients by locator.settings.testModeRecipients.collectAsStateWithLifecycle(initialValue = emptySet())
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) { if (testMode) viewModel.setTestMode(true) }

    fun enterTestMode() {
        testMode = true
        MapCameraMemory.testMode = true
        viewModel.setTestMode(true)
        Toast.makeText(context, R.string.test_mode_hint, Toast.LENGTH_LONG).show()
    }

    fun exitTestMode() {
        testMode = false
        MapCameraMemory.testMode = false
        // Primero el servidor (devuelve a cada uno su estado de zonas real, en silencio) y
        // luego el sondeo, que vuelve a traer las posiciones de verdad.
        scope.launch { runCatching { locator.adminRepository.stopSimulation() } }
        viewModel.setTestMode(false)
    }

    var showUpdateDialog by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }

    // Miembro elegido en la pestaña "Gente": centra el mapa en su posición más reciente.
    val focusUserId = focusUserIdFlow?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(focusUserId, state.members, mapViewRef) {
        val id = focusUserId ?: return@LaunchedEffect
        val member = state.members.find { it.user_id == id } ?: return@LaunchedEffect
        mapViewRef?.moveTo(GeoPoint(member.lat, member.lng))
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
    BackHandler { if (testMode) exitTestMode() else showExitConfirm = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        if (testMode) {
                            Text(
                                stringResource(R.string.test_mode_badge),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenGroup) {
                        Icon(Icons.Filled.Group, contentDescription = stringResource(R.string.group_title))
                    }
                },
                actions = {
                    MapStyleMenuButton { scope.launch { locator.settings.setMapStyle(it) } }
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Filled.HelpOutline, contentDescription = stringResource(R.string.help_title))
                    }
                    if (isAdmin && testMode) {
                        Box {
                            IconButton(onClick = { showRecipientsMenu = true }) {
                                Icon(
                                    Icons.Filled.NotificationsActive,
                                    contentDescription = stringResource(R.string.test_mode_recipients),
                                )
                            }
                            // A quién le llegan los avisos de la prueba. Se elige a mano (y se
                            // recuerda) en vez de respetar quién los tenga activados en cada
                            // zona: probar es querer verlo sonar en un móvil concreto.
                            DropdownMenu(expanded = showRecipientsMenu, onDismissRequest = { showRecipientsMenu = false }) {
                                state.members.forEach { member ->
                                    val checked = member.user_id in testRecipients
                                    DropdownMenuItem(
                                        text = { Text(member.display_name) },
                                        leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
                                        onClick = {
                                            val next = if (checked) testRecipients - member.user_id
                                            else testRecipients + member.user_id
                                            scope.launch { locator.settings.setTestModeRecipients(next) }
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { exitTestMode() }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.test_mode_exit),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else if (isAdmin) {
                        // Caja con gesto propio en vez de IconButton: hace falta distinguir el
                        // toque (abrir administración) de la pulsación MUY larga (modo prueba),
                        // y el umbral de pulsación larga de Compose es de medio segundo. Se
                        // pierde el efecto de onda al tocar, de ahí la vibración al entrar.
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        val start = System.currentTimeMillis()
                                        val up = withTimeoutOrNull(TEST_MODE_LONG_PRESS_MS) { waitForUpOrCancellation() }
                                        when {
                                            up != null -> onOpenAdmin()
                                            // up == null también puede ser un gesto cancelado
                                            // antes de tiempo (otro elemento se queda el dedo):
                                            // solo cuenta si de verdad ha pasado el tiempo.
                                            System.currentTimeMillis() - start >= TEST_MODE_LONG_PRESS_MS -> {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                enterTestMode()
                                                waitForUpOrCancellation()
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
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
                showAccuracy = mapShowAccuracy,
                accuracyIntensity = mapAccuracyIntensity,
                rememberCamera = true,
                onMemberClick = { member -> onOpenMember(member.user_id) },
                // Solo los demás: arrastrarse a uno mismo no probaría nada (los avisos de zona
                // nunca se mandan a quien se ha movido).
                draggableUserIds = if (testMode) {
                    state.members.map { it.user_id }.filter { it != locator.session.userId }.toSet()
                } else {
                    emptySet()
                },
                onMemberDragEnd = { member, point ->
                    viewModel.moveMemberLocally(member.user_id, point.latitude, point.longitude)
                    scope.launch {
                        runCatching {
                            locator.adminRepository.simulatePosition(
                                member.user_id, point.latitude, point.longitude, testRecipients.toList(),
                            )
                        }.onSuccess { result ->
                            val message = when {
                                result.transitions.isEmpty() -> context.getString(R.string.test_mode_no_transition)
                                testRecipients.isEmpty() -> context.getString(R.string.test_mode_no_recipients)
                                else -> context.getString(
                                    R.string.test_mode_sent, result.notified, result.transitions.joinToString(" · "),
                                )
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }.onFailure {
                            Toast.makeText(context, R.string.test_mode_error, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onMapReady = { view ->
                    mapViewRef = view
                    // Al abrir la app el mapa debe mostrar ya la posición actual, sin tener que
                    // tocar el botón "Dónde estoy" primero — pero solo la primera vez que se
                    // abre el mapa en esta sesión: si ya había una posición guardada (venimos de
                    // cambiar de pestaña, no de abrir la app), OsmMapView ya la restauró solo y
                    // no hay que recentrar sobre la ubicación actual por encima.
                    if (MapCameraMemory.center == null) {
                        lastKnownLocation(context) { view.controller.setCenter(GeoPoint(it.latitude, it.longitude)) }
                    }
                },
            )
            // Tic de radar sobre el seguido: late en cada posición que llega, se haya movido o
            // no. Es lo único que distingue "está quieto" de "no está llegando nada".
            LiveRadarPulse(
                mapView = mapViewRef,
                location = if (state.followLive) followed else null,
                modifier = Modifier.fillMaxSize(),
            )
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            // Buscador flotante sobre el mapa. Al elegir un sitio hay que dejar de seguir a
            // nadie: si no, la siguiente ubicación del seguido recentraría el mapa y daría la
            // sensación de que la búsqueda no ha funcionado.
            PlaceSearchField(
                nominatim = locator.nominatim,
                label = stringResource(R.string.place_search_label),
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).fillMaxWidth(),
            ) { place ->
                viewModel.setFollowing(null)
                // setZoom + setCenter dentro de map.post, sin animateTo: encadenar zoom y
                // animación en osmdroid cuelga el mapa (mismo problema que ya había al
                // posicionar la zona nueva, ver ZoneEditScreen).
                mapViewRef?.let { map ->
                    map.post {
                        map.controller.setZoom(Constants.MAP_SEARCH_RESULT_ZOOM)
                        map.controller.setCenter(GeoPoint(place.lat, place.lng))
                    }
                }
            }

            // Botón "dónde estoy" + seguir en vivo (a quien se elija), a la izquierda, para
            // que no se solape con los de la derecha.
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Mientras el otro móvil no conteste, dicho con todas las letras y a la vista:
                // el toast se lo pierde quien mire la pantalla dos segundos después.
                if (state.followState == FollowState.PENDING || state.followState == FollowState.FAILED) {
                    val failed = state.followState == FollowState.FAILED
                    Surface(
                        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(
                            stringResource(if (failed) R.string.follow_live_chip_failed else R.string.follow_live_chip_pending),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                Box {
                    // Cuatro estados a propósito: apagado, "pedido pero el otro móvil aún no ha
                    // confirmado", "no contesta" y "en vivo de verdad". Antes el botón se
                    // encendía igual aunque la orden no hubiera prendido en el otro lado, y no
                    // había forma de distinguir "no funciona" de "va, pero está parado".
                    val followColor = when (state.followState) {
                        FollowState.LIVE -> MaterialTheme.colorScheme.primary
                        FollowState.PENDING -> MaterialTheme.colorScheme.tertiary
                        FollowState.FAILED -> MaterialTheme.colorScheme.error
                        FollowState.OFF -> MaterialTheme.colorScheme.surface
                    }
                    SmallFloatingActionButton(
                        onClick = { showFollowMenu = true },
                        containerColor = followColor,
                    ) {
                        Icon(
                            if (state.followState == FollowState.LIVE) Icons.Filled.GpsFixed else Icons.Filled.GpsOff,
                            contentDescription = stringResource(R.string.map_follow_me),
                            tint = when (state.followState) {
                                FollowState.OFF -> MaterialTheme.colorScheme.onSurface
                                FollowState.FAILED -> MaterialTheme.colorScheme.onError
                                else -> MaterialTheme.colorScheme.onPrimary
                            },
                        )
                    }
                    DropdownMenu(expanded = showFollowMenu, onDismissRequest = { showFollowMenu = false }) {
                        if (followUserId != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.map_follow_stop)) },
                                leadingIcon = { Icon(Icons.Filled.GpsOff, contentDescription = null) },
                                onClick = {
                                    viewModel.setFollowing(null)
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
                                    viewModel.setFollowing(member.user_id)
                                    // Solo al elegir a quién seguir: el recentrado de cada
                                    // sondeo no toca el zoom, para no pelearse con el usuario
                                    // si se aleja a mirar algo mientras sigue a alguien.
                                    mapViewRef?.controller?.setZoom(initialZoom.toDouble())
                                    showFollowMenu = false
                                },
                            )
                        }
                    }
                }
                FloatingActionButton(
                    onClick = {
                        lastKnownLocation(context) { mapViewRef?.moveTo(GeoPoint(it.latitude, it.longitude)) }
                    },
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.locate_me))
                }
            }

            // Compartir ubicación. Invitar con el código del grupo ya vive en la info del grupo.
            FloatingActionButton(
                onClick = { shareCurrentLocation(context) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share_current_location))
            }
        }
    }

    // Modo sin conexión elegido pero sin ninguna zona descargada: el mapa que se ve debajo es
    // el de internet (ver applyMapStyle), así que solo hay que decidir qué hacer.
    if (mapStyle.isOffline && offlineMapFiles.isEmpty()) {
        AlertDialog(
            onDismissRequest = { scope.launch { locator.settings.setMapStyle(mapStyle.online) } },
            title = { Text(stringResource(R.string.offline_maps_missing_title)) },
            text = { Text(stringResource(R.string.offline_maps_missing_body)) },
            confirmButton = {
                TextButton(onClick = onOpenOfflineMaps) {
                    Text(stringResource(R.string.offline_maps_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { scope.launch { locator.settings.setMapStyle(mapStyle.online) } }) {
                    Text(stringResource(R.string.offline_maps_missing_live))
                }
            },
        )
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

private fun shareCurrentLocation(context: Context) = lastKnownLocation(context) { location ->
    context.startActivity(LocationSharing.shareIntent(location.latitude, location.longitude))
}
