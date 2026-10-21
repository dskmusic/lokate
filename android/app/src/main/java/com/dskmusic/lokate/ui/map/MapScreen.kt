package com.dskmusic.lokate.ui.map

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
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
import com.dskmusic.lokate.nav.NavSession
import com.dskmusic.lokate.ui.common.PlaceResult
import com.dskmusic.lokate.ui.common.PlaceSearchField
import com.dskmusic.lokate.ui.common.apaisado
import com.dskmusic.lokate.ui.common.lastKnownLocation
import com.dskmusic.lokate.ui.help.HelpDialog
import com.dskmusic.lokate.ui.nav.NavBanner
import com.dskmusic.lokate.ui.nav.NavBottomBar
import com.dskmusic.lokate.ui.nav.NavOptionsDialog
import com.dskmusic.lokate.ui.nav.RouteStepsDialog
import com.dskmusic.lokate.util.AppUpdater
import com.dskmusic.lokate.util.ConfigCheck
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.util.LocationSharing
import com.dskmusic.lokate.util.MapStyle
import com.dskmusic.lokate.util.UpdateCheckState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.osmdroid.util.GeoPoint

/** Pulsación larga sobre el candado para entrar en el modo prueba. Deliberadamente muy por
 * encima del medio segundo de una pulsación larga normal: es un modo de pruebas, no algo que
 * se deba poder activar sin querer. */
private const val TEST_MODE_LONG_PRESS_MS = 1_500L

/** Lo ancho que como mucho se ponen los carteles flotantes del mapa (buscador, maniobra, regla).
 * De pie no se nota, que un móvil no llega; tumbado evita una barra de 800 dp tapando el mapa de
 * lado a lado. */
private val OVERLAY_MAX_W = 480.dp

/** Una de las cinco paradas de la app. En una lista porque se pintan en dos sitios: la barra de
 * abajo con el móvil de pie y el raíl del lateral con el móvil tumbado. [onClick] a nulo es
 * la parada en la que ya se está. */
private class Parada(val icono: ImageVector, val etiqueta: Int, val onClick: (() -> Unit)?)

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
    startUpdate: Boolean = false,
    /** Dirección compartida desde otra app: entra escrita en el buscador y se busca sola. */
    sharedPlace: String? = null,
    focusUserIdFlow: StateFlow<String?>? = null,
    onFocusUserIdConsumed: () -> Unit = {},
    focusZoneIdFlow: StateFlow<String?>? = null,
    onFocusZoneIdConsumed: () -> Unit = {},
    /** Alguien ha pulsado "hasta dónde llega" en su ficha: se le piden los anillos y se pintan
     * en este mapa (ver [IsochroneState]). */
    isochroneUserIdFlow: StateFlow<String?>? = null,
    onIsochroneUserIdConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val tumbado = apaisado()
    // Si el racimo de la esquina (el que sustituye a la barra de arriba tumbado) está abierto.
    var accionesAbiertas by remember { mutableStateOf(false) }
    val paradas = listOf(
        Parada(Icons.Filled.Map, R.string.map_title, null),
        Parada(Icons.Filled.People, R.string.people_title, onOpenPeople),
        Parada(Icons.Filled.Place, R.string.zones_title, onOpenZones),
        Parada(Icons.Filled.History, R.string.history_title, onOpenHistory),
        Parada(Icons.Filled.Settings, R.string.settings_title, onOpenSettings),
    )
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
    val mapShowAccuracy by locator.settings.mapShowAccuracy.collectAsStateWithLifecycle(initialValue = true)
    val mapAccuracyNames by locator.settings.mapAccuracyNames.collectAsStateWithLifecycle(initialValue = true)
    val mapZoneMarginColor by locator.settings.mapZoneMarginColor
        .collectAsStateWithLifecycle(initialValue = Constants.MAP_ZONE_MARGIN_COLOR_DEFAULT)
    val mapZoneColor by locator.settings.mapZoneColor
        .collectAsStateWithLifecycle(initialValue = Constants.MAP_ZONE_COLOR_DEFAULT)
    val mapAccuracyColor by locator.settings.mapAccuracyColor
        .collectAsStateWithLifecycle(initialValue = Constants.MAP_ACCURACY_COLOR_DEFAULT)
    val mapZoneFillIntensity by locator.settings.mapZoneFillIntensity
        .collectAsStateWithLifecycle(initialValue = Constants.MAP_ZONE_FILL_INTENSITY_DEFAULT)
    val mapAccuracyIntensity by locator.settings.mapAccuracyIntensity
        .collectAsStateWithLifecycle(initialValue = Constants.MAP_ACCURACY_INTENSITY_DEFAULT)
    val mapShowZoneMargin by locator.settings.mapShowZoneMargin.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    val offlineMapFiles = rememberOfflineMapFiles()

    // El mando del mapa, lo pinte osmdroid o MapLibre (ver [MapControl]).
    var mapControl by remember { mutableStateOf<MapControl?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    var showFollowMenu by remember { mutableStateOf(false) }
    // El seguimiento vive en el ViewModel (sobrevive a cambiar de pestaña) y le pone el móvil
    // al seguido en tiempo real mientras dure; al soltarlo vuelve a su ritmo de siempre.
    val followUserId = state.followUserId
    val followed = state.members.find { it.user_id == followUserId }

    // Modo "seguir en vivo": mientras haya alguien elegido, recentra el mapa cada vez que
    // llega una ubicación nueva suya (mismo ciclo de sondeo que ya alimenta los marcadores).
    // Puede ser cualquier miembro del grupo, incluido uno mismo.
    LaunchedEffect(followUserId, followed?.timestamp, mapControl) {
        val map = mapControl ?: return@LaunchedEffect
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

    // Descargar el APK del servidor e instalarlo. Lo llaman el botón del diálogo de "hay
    // actualización" y el aviso que manda un admin: es lo mismo, solo cambia quién lo dispara.
    fun runUpdate() {
        // Sin el permiso de "instalar apps desconocidas" no hay nada que hacer salvo llevarle a
        // dárselo; al volver, el diálogo sigue ahí con su botón.
        if (!AppUpdater.canRequestInstall(context)) {
            AppUpdater.openInstallPermissionSettings(context)
            return
        }
        updating = true
        scope.launch {
            val url = absoluteMediaUrl("/lokate.apk").orEmpty()
            runCatching { AppUpdater.downloadAndInstall(context, url) }
            updating = false
            showUpdateDialog = false
        }
    }

    // Se ha entrado tocando el aviso de un admin: el diálogo se abre ya descargando, para que se
    // vea qué está pasando, y no hay que pulsar nada más.
    LaunchedEffect(startUpdate) {
        if (startUpdate) {
            showUpdateDialog = true
            runUpdate()
        }
    }

    // Miembro elegido en la pestaña "Gente" o en su ficha: centra el mapa en su posición más
    // reciente.
    val focusUserId = focusUserIdFlow?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(focusUserId, mapControl) {
        val id = focusUserId ?: return@LaunchedEffect
        val map = mapControl ?: return@LaunchedEffect
        // Primero con lo que haya en memoria, para que el mapa no se quede donde estaba mientras
        // se pide...
        state.members.find { it.user_id == id }?.let { map.moveTo(GeoPoint(it.lat, it.lng)) }
        // ...y luego con la posición recién traída, que es la que pidió la ficha con "localizar":
        // el sondeo llevaba parado desde que se salió del mapa, así que lo de memoria puede ser
        // de hace rato. Hasta aquí no se consume el foco, o el refresco llegaría sin nadie a
        // quien centrar.
        viewModel.fetchLatest(id)?.let { map.moveTo(GeoPoint(it.lat, it.lng)) }
        onFocusUserIdConsumed()
    }

    // Los anillos de "hasta dónde llega", si hay alguno pedido. Una petición por pulsación: el
    // estado vive dentro (ver Isochrone.kt).
    val isochrone = rememberIsochrone(locator.locationRepository)

    // "Hasta dónde llega" desde la ficha de alguien. Se pide con la posición que haya en memoria
    // y no se pide una fresca a su móvil: el dibujo es de manzanas enteras, así que veinte metros
    // de diferencia no cambian nada de lo que se ve, y esperar a que el otro móvil conteste sí.
    val isochroneUserId = isochroneUserIdFlow?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(isochroneUserId, state.members) {
        val id = isochroneUserId ?: return@LaunchedEffect
        // Hasta que la posición de esa persona está cargada no se consume: entrando al mapa desde
        // su ficha, la lista de miembros puede llegar un instante después.
        val who = state.members.find { it.user_id == id } ?: return@LaunchedEffect
        isochrone.show(who)
        onIsochroneUserIdConsumed()
    }

    // Los anillos recién llegados se enseñan enteros, igual que una ruta recién trazada: un
    // anillo de 15 minutos en coche no cabe en el zoom con el que se estaba mirando a alguien.
    LaunchedEffect(isochrone.rings, mapControl) {
        val puntos = isochrone.rings.flatMap { it.points }
        if (puntos.isNotEmpty()) mapControl?.framePoints(puntos, ISOCHRONE_PADDING_PX)
    }

    // Zona elegida con el ojo en la pestaña "Zonas": el mapa se abre con su círculo entero
    // encuadrado, el mismo encuadre que al crearla o editarla.
    val focusZoneId = focusZoneIdFlow?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(focusZoneId, zones, mapControl) {
        val id = focusZoneId ?: return@LaunchedEffect
        val map = mapControl ?: return@LaunchedEffect
        // Las zonas vienen de la caché local y pueden tardar un instante en estar: hasta que la
        // zona aparece no se consume el foco, o se perdería el encuadre.
        val zone = zones.find { it.id == id } ?: return@LaunchedEffect
        map.frameZone(zone.lat, zone.lng, zone.radius_m)
        onFocusZoneIdConsumed()
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

    // Sitio del mapa que se acaba de mantener pulsado (null = no hay cuadro abierto).
    // El último sitio encontrado en el buscador: lleva su chincheta en el mapa hasta que se
    // busque otro o se vacíe el buscador.
    var searchHit by remember { mutableStateOf<PlaceResult?>(null) }
    var addressPoint by remember { mutableStateOf<GeoPoint?>(null) }
    // El "cómo llegar" de ese punto: a quién se le pide la posición, a pie o en coche, y la
    // ruta que salga. Todo el estado vive dentro (ver RoutePlanner).
    val planner = rememberRoutePlanner(locator.locationRepository)

    // La navegación por voz, si hay alguna en marcha. El estado vive en un objeto de proceso y
    // no aquí a propósito: el servicio sigue guiando con esta pantalla cerrada, y al volver a
    // ella se encuentra el viaje donde estaba (ver [NavSession]).
    val navState by NavSession.state.collectAsState()
    val navPaused by NavSession.paused.collectAsState()
    val navRotateMap by locator.settings.navRotateMap.collectAsStateWithLifecycle(initialValue = true)

    // Con quién se abre el mapa. null = el ajuste aún no ha llegado (ver [OsmMapView]).
    val mapDefaultUserId by locator.settings.mapDefaultUserId.collectAsState(initial = null)
    var showSteps by remember { mutableStateOf(false) }
    /** La ruta esperando a que se encienda la ubicación para arrancar (ver GpsRequiredDialog). */
    var gpsPendiente by remember { mutableStateOf<PlannedRoute?>(null) }
    var showNavOptions by remember { mutableStateOf(false) }

    // Navegando, la pantalla no se apaga: es lo que se espera de un navegador en el soporte del
    // coche. Se suelta al salir, que si no se queda encendida para siempre.
    val rootView = LocalView.current
    DisposableEffect(navState != null) {
        rootView.keepScreenOn = navState != null
        onDispose { rootView.keepScreenOn = false }
    }

    // El mapa va detrás de quien navega. Hasta que se arrastra el mapa: entonces se queda donde
    // se le deja (como en Maps) y vuelve con el botón de recentrar.
    var siguiendo by remember { mutableStateOf(true) }
    /** Lo que se estaba mirando antes de arrancar el viaje (centro y zoom), para devolverlo al
     * salir: la navegación deja el mapa donde acabó el viaje, que puede estar a kilómetros de
     * donde se estaba. */
    var camaraPrevia by remember { mutableStateOf<Pair<GeoPoint, Double>?>(null) }
    LaunchedEffect(navState != null) {
        siguiendo = true
        val map = mapControl
        if (navState != null) {
            // Al arrancar el viaje, del plano de la ruta entera al zoom de ir por la calle: con
            // una ruta de veinte kilómetros encuadrada no se ve ni dónde está el próximo giro.
            // Solo este salto; después el zoom vuelve a ser de quien mira.
            // De golpe y sin animar, como tras elegir un resultado del buscador (ver
            // [MapControl.jumpTo]).
            if (map != null && map.widthPx > 0) camaraPrevia = map.center to map.zoom
            navState?.position?.let { map?.jumpTo(it, Constants.MAP_NAV_ZOOM) }
        } else {
            // Y al salir, lo de antes del viaje. Si no hay nada guardado (la pantalla se abrió
            // con el viaje ya en marcha), al menos la última posición conocida de quien se esté
            // siguiendo.
            val previa = camaraPrevia
            camaraPrevia = null
            when {
                previa != null -> map?.jumpTo(previa.first, previa.second)
                followed != null -> map?.moveTo(GeoPoint(followed.lat, followed.lng))
            }
        }
    }
    LaunchedEffect(navState?.position) {
        if (siguiendo) navState?.position?.let { mapControl?.moveTo(it) }
    }

    // Modo medir: la regla del mapa. Vive aquí y solo en memoria a propósito — es de usar y
    // tirar, al apagarlo no queda nada que guardar.
    var measuring by remember { mutableStateOf(false) }
    val measurePoints = remember { mutableStateListOf<GeoPoint>() }
    // Total mientras un punto va colgado del dedo: measurePoints no se toca hasta soltar (cada
    // cambio suyo rehace todas las capas del mapa), así que el número en vivo viaja aparte.
    var measureLiveMeters by remember { mutableStateOf<Double?>(null) }
    // Punto recién agarrado + cuántas veces se ha agarrado: el contador es lo que hace latir el
    // tic de radar, porque coger dos veces seguidas el mismo punto tiene que volver a sonar.
    var measureGrab by remember { mutableStateOf<Pair<GeoPoint, Int>?>(null) }
    // Toda foto del mapa pasa por aquí: pregunta el tamaño, enseña el aviso de que se está
    // generando y, mientras dura, apaga los botones — dos fotos a la vez se pelearían por mover
    // el mismo mapa.
    val imageExport = rememberImageExport()
    // Qué entra en la foto de la ruta. Se recuerda entre fotos: quien los quita una vez suele
    // querer la siguiente igual.
    // Lo que tapan de mapa el buscador (arriba) y el cartel de la ruta (abajo), medido en vez de
    // supuesto: cambia con la pantalla tumbada, con un nombre largo que parta el cartel en dos
    // líneas y con el tamaño de letra del sistema.
    var topOverlayPx by remember { mutableIntStateOf(0) }
    var bottomOverlayPx by remember { mutableIntStateOf(0) }
    // Lo que tapa abajo la barra del viaje, para el botón de los 45° del mapa vectorial.
    var navOverlayPx by remember { mutableIntStateOf(0) }
    /** Qué ruta se encuadró y con qué huecos. Vive aquí fuera y no dentro de la tarjeta de la
     * ruta a propósito: ver el efecto que encuadra. */
    var encuadrada by remember { mutableStateOf("") }
    var showMeasureSaveOptions by remember { mutableStateOf(false) }
    var measureImageMidPoints by remember { mutableStateOf(true) }
    var measureImageSegmentDistances by remember { mutableStateOf(true) }
    var measureImageFitRoute by remember { mutableStateOf(true) }
    // Si lo que se pidió fue compartir en vez de guardar: las opciones de la foto son las mismas,
    // así que el diálogo es uno solo y esto es lo único que los distingue.
    var measureImageShare by remember { mutableStateOf(false) }

    fun toggleMeasuring() {
        measuring = !measuring
        measurePoints.clear()
        measureLiveMeters = null
        measureGrab = null
    }

    // El mapa es la pantalla de inicio: aquí el botón atrás no debe cerrar la app de golpe,
    // sino preguntar primero (en el resto de pantallas, atrás simplemente deshace la
    // navegación por defecto de Jetpack Navigation, sin necesitar nada extra). Con un modo
    // encendido, atrás es lo que lo apaga: cerrar la app sería tirar lo que se estaba haciendo.
    var showExitConfirm by remember { mutableStateOf(false) }
    BackHandler {
        when {
            measuring -> toggleMeasuring()
            testMode -> exitTestMode()
            else -> showExitConfirm = true
        }
    }

    // Los botones de la barra de arriba. En una lambda porque se pintan en dos sitios: en la
    // barra con el móvil de pie y en el racimo plegable de la esquina con el móvil tumbado.
    val acciones: @Composable RowScope.() -> Unit = {
        IconButton(onClick = { toggleMeasuring() }) {
            Icon(
                Icons.Filled.Straighten,
                contentDescription = stringResource(R.string.measure_button),
                tint = if (measuring) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
        // Foto de lo que se está viendo, al lado de la regla: las dos son
        // herramientas de "llévate esto del mapa".
        IconButton(
            onClick = {
                val map = mapControl
                if (map != null) {
                    imageExport.ask { size, onProgress ->
                        val saved = saveMapImage(context, map, size, SNAPSHOT_TILE_WAIT_MS, onProgress)
                        val message = if (saved) R.string.measure_saved else R.string.measure_save_failed
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            enabled = !imageExport.busy,
        ) {
            Icon(
                Icons.Filled.PhotoCamera,
                contentDescription = stringResource(R.string.map_image_button),
            )
        }
        MapStyleMenuButton(mapStyle) { scope.launch { locator.settings.setMapStyle(it) } }
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
    }

    // Tumbado, las paradas se van a un raíl en el lateral: en horizontal sobra ancho y falta
    // alto, y los 80 dp de la barra de abajo son 80 dp menos de mapa. El raíl va por fuera del
    // Scaffold —y no dentro del cuerpo— para que también esté en las pantallas de "sin grupo" y
    // "sin conexión", donde si no, tumbado, no habría forma de ir a ningún sitio.
    Row(Modifier.fillMaxSize()) {
        if (tumbado) {
            // Con scroll porque cinco paradas con su letrero son unos 360 dp, y un móvil
            // pequeño tumbado no llega a tanto: sin esto, Ajustes se quedaría recortado.
            NavigationRail(
                modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
            ) {
                paradas.forEach { parada ->
                    NavigationRailItem(
                        selected = parada.onClick == null,
                        onClick = parada.onClick ?: {},
                        icon = { Icon(parada.icono, contentDescription = null) },
                        label = { Text(stringResource(parada.etiqueta)) },
                    )
                }
            }
        }
    Scaffold(
        modifier = Modifier.weight(1f).fillMaxHeight(),
        topBar = {
            // Tumbado no hay barra: son 64 dp de los ~400 que tiene la pantalla de alto, y sus
            // botones están igual de a mano en el racimo de la esquina (más abajo).
            if (!tumbado) TopAppBar(
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
                actions = acciones,
            )
        },
        bottomBar = {
            if (!tumbado) {
                NavigationBar {
                    paradas.forEach { parada ->
                        NavigationBarItem(
                            selected = parada.onClick == null,
                            onClick = parada.onClick ?: {},
                            icon = { Icon(parada.icono, contentDescription = null) },
                            label = { Text(stringResource(parada.etiqueta)) },
                        )
                    }
                }
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
            LokateMapView(
                members = state.members,
                zones = zones,
                modifier = Modifier.fillMaxSize(),
                avatarBitmaps = avatarBitmaps,
                initialZoom = initialZoom.toDouble(),
                mapStyle = mapStyle,
                showAccuracy = mapShowAccuracy,
                accuracyIntensity = mapAccuracyIntensity,
                showAccuracyLabels = mapAccuracyNames,
                zoneFillIntensity = mapZoneFillIntensity,
                zoneColor = mapZoneColor,
                zoneMarginColor = mapZoneMarginColor,
                accuracyColor = mapAccuracyColor,
                showZoneMargin = mapShowZoneMargin,
                rememberCamera = true,
                // El botón del norte del mapa vectorial, por debajo del buscador.
                compassTopInsetPx = topOverlayPx,
                // Y el de los 45°, navegando, por encima de la barra del viaje.
                tiltBottomInsetPx = navOverlayPx,
                // Con quién se abre el mapa (ver Ajustes). null hasta que el ajuste llega del
                // disco: así no se centra en uno mismo por ir más rápido que DataStore.
                autoCenterUserId = mapDefaultUserId,
                onMemberClick = { member -> onOpenMember(member.user_id) },
                // Solo midiendo: con el modo apagado el mapa no escucha toques (tocar una zona
                // sigue enseñando su nombre, como siempre).
                onMapTap = if (measuring) ({ point -> measurePoints += point }) else null,
                // Dedo quieto sobre un sitio: su dirección. Midiendo no, que ahí el dedo
                // quieto ya significa agarrar un punto de la regla.
                onMapLongPress = if (measuring) null else ({ point -> addressPoint = point }),
                // Arrastrar el mapa suelta el autocentrado de la navegación.
                onUserPan = { siguiendo = false },
                // Navegando se pinta la ruta de la navegación, que es la que manda: tras un
                // recálculo ya no es la que trazó el planificador.
                routePoints = (NavSession.route ?: planner.route)?.points.orEmpty(),
                isochroneRings = isochrone.rings,
                navPoint = navState?.position,
                navRotate = navRotateMap,
                searchPoint = searchHit?.let { GeoPoint(it.lat, it.lng) },
                searchLabel = searchHit?.label.orEmpty(),
                onSearchPointClick = { searchHit?.let { addressPoint = GeoPoint(it.lat, it.lng) } },
                measurePoints = measurePoints,
                // El índice lo lleva dentro el marcador que se tocó; comprobarlo evita reventar
                // si llega un toque de una capa que ya no corresponde a la lista de ahora.
                onMeasurePointDelete = { index -> if (index in measurePoints.indices) measurePoints.removeAt(index) },
                onMeasurePointMoved = { index, point ->
                    if (index in measurePoints.indices) measurePoints[index] = point
                },
                onMeasurePointInsert = { index, point ->
                    if (index in 1..measurePoints.size) measurePoints.add(index, point)
                },
                onMeasurePointGrab = { point -> measureGrab = point to ((measureGrab?.second ?: 0) + 1) },
                onMeasureLiveTotal = { meters -> measureLiveMeters = meters },
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
                onMapControl = { control ->
                    mapControl = control
                    // Al abrir la app el mapa debe mostrar ya la posición actual, sin tener que
                    // tocar el botón "Dónde estoy" primero — pero solo la primera vez que se
                    // abre el mapa en esta sesión: si ya había una posición guardada (venimos de
                    // cambiar de pestaña, no de abrir la app), el mapa ya la restauró solo y
                    // no hay que recentrar sobre la ubicación actual por encima.
                    if (MapCameraMemory.center == null) {
                        lastKnownLocation(context) { control.moveTo(GeoPoint(it.latitude, it.longitude)) }
                    }
                },
            )
            // Tic de radar sobre el seguido: late en cada posición que llega, se haya movido o
            // no. Es lo único que distingue "está quieto" de "no está llegando nada".
            val pulsing = if (state.followLive) followed else null
            LiveRadarPulse(
                map = mapControl,
                point = pulsing?.let { GeoPoint(it.lat, it.lng) },
                // El sello de tiempo y no la posición: es lo único que cambia en CADA ping, que
                // es justo lo que este tic tiene que demostrar que sigue pasando.
                trigger = pulsing?.timestamp,
                modifier = Modifier.fillMaxSize(),
            )
            // El mismo tic, en naranja, cuando se agarra un punto de la regla: junto con la
            // vibración es lo que dice "ya lo tienes cogido, arrastra".
            LiveRadarPulse(
                map = mapControl,
                point = measureGrab?.first,
                trigger = measureGrab?.second,
                modifier = Modifier.fillMaxSize(),
                color = Color(MEASURE_COLOR),
            )
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            addressPoint?.let { point ->
                MapPointAddressDialog(
                    point = point,
                    nominatim = locator.nominatim,
                    servidor = locator.locationRepository,
                    onRouteTo = {
                        addressPoint = null
                        planner.start(point, state.members)
                    },
                    onDismiss = { addressPoint = null },
                )
            }
            RoutePlannerDialogs(planner)
            ImageExportDialogs(imageExport)
            // Una ruta recién trazada se enseña entera; a partir de ahí, el zoom es de quien mire.
            // Navegando no: el cartel de la maniobra ocupa su sitio y dice más.
            planner.route?.takeIf { navState == null }?.let { trazada ->
                // También cuando cambia lo que tapan: la primera vuelta el cartel aún no está
                // medido (vale 0) y al girar el móvil cambia entero.
                LaunchedEffect(trazada, topOverlayPx, bottomOverlayPx) {
                    // Pero una sola vez por ruta y por hueco: al salir de la navegación esta
                    // tarjeta se vuelve a montar con la misma ruta y el mismo hueco, el efecto
                    // arrancaba de nuevo y el mapa se iba al plano de la ruta entera justo
                    // después de haber vuelto a donde se estaba antes del viaje.
                    val huella = "${System.identityHashCode(trazada)}#$topOverlayPx#$bottomOverlayPx"
                    if (encuadrada == huella) return@LaunchedEffect
                    encuadrada = huella
                    mapControl?.frameRoute(trazada.points, topOverlayPx, bottomOverlayPx)
                }
                val resumen = trazada.summary()
                RouteInfoCard(
                    route = trazada,
                    sharing = imageExport.busy,
                    onPhoto = { compartir ->
                        val map = mapControl
                        if (map != null) {
                            imageExport.ask { size, onProgress ->
                                val ok = exportRouteImage(
                                    context, map, trazada, resumen, size, compartir, onProgress,
                                )
                                // Compartiendo y saliendo bien no hay nada que decir: lo que se
                                // ve es el diálogo del sistema. Guardando sí, que si no la foto
                                // parece no haber ido a ningún sitio.
                                if (!ok || !compartir) {
                                    val aviso = if (ok) R.string.measure_saved else R.string.measure_save_failed
                                    Toast.makeText(context, aviso, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    onClose = planner::clear,
                    // Los pasos, de donde sale navegar y simular. Solo si la ruta trae
                    // maniobras: una ruta vieja o sin indicaciones no se puede navegar.
                    onSteps = if (trazada.steps.isNotEmpty()) ({ showSteps = true }) else null,
                    // Ancho entero: con una ruta en pantalla los botones de la esquina se
                    // quitan de en medio (justo debajo), así que no hay nada que esquivar.
                    modifier = Modifier.align(Alignment.BottomStart)
                        .onSizeChanged { bottomOverlayPx = it.height }
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }

            // La tarjeta de los anillos ocupa el mismo hueco que la de la ruta: lo de quién
            // son, qué significa cada color y el cambio a pie/en coche.
            if (planner.route == null && navState == null) {
                IsochroneCard(
                    state = isochrone,
                    modifier = Modifier.align(Alignment.BottomStart)
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }

            // Navegando, arriba manda la maniobra: ni buscador ni regla. Abajo, lo que queda de
            // viaje y los botones del viaje.
            navState?.let { nav ->
                NavBanner(
                    state = nav,
                    compacto = tumbado,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                        .widthIn(max = OVERLAY_MAX_W).fillMaxWidth(),
                )
                // Centrar va justo encima de la barra del viaje y pegado a su borde derecho, así
                // que los dos van en la misma columna: con eso comparten margen y ancho máximo
                // sin tener que adivinar lo que mide la barra (que cambia tumbado).
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        // Antes del padding a propósito: lo que tapa es la caja con su hueco.
                        .onSizeChanged { navOverlayPx = it.height }
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        .widthIn(max = OVERLAY_MAX_W).fillMaxWidth(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Solo con el autocentrado soltado: es la vuelta atrás de haber arrastrado
                    // el mapa, y el resto del viaje no tiene nada que hacer ahí. Con la palabra
                    // y no solo el icono: aparece de repente a mitad de viaje y una diana
                    // suelta no dice qué va a pasar al tocarla.
                    if (!siguiendo) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                siguiendo = true
                                nav.position?.let { mapControl?.moveTo(it) }
                            },
                        ) {
                            Text(stringResource(R.string.nav_recenter))
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Filled.MyLocation, contentDescription = null)
                        }
                    }
                    NavBottomBar(
                        state = nav,
                        paused = navPaused,
                        onMute = { scope.launch { locator.settings.setNavVoiceEnabled(nav.muted) } },
                        onPause = { NavSession.togglePause(context) },
                        onSteps = { showSteps = true },
                        onExit = { NavSession.stop(context) },
                        compacto = tumbado,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Los pasos de la ruta: la lista que se puede leer, y desde donde se arranca a
            // navegar o a simular. Navegando se lee de la ruta de la navegación (la recalculada,
            // si hubo recálculo); si no, de la que acaba de trazar el planificador.
            if (showSteps) {
                (NavSession.route ?: planner.route)?.let { ruta ->
                    RouteStepsDialog(
                        route = ruta,
                        // Guiar por voz al móvil de otro no significa nada: el que oye las
                        // indicaciones es este teléfono.
                        canNavigate = navState == null && ruta.memberId == locator.session.userId,
                        activeStep = navState?.stepIndex,
                        onNavigate = {
                            showSteps = false
                            // Navegar de verdad con la ubicación del sistema apagada no falla:
                            // se queda a medias. El servicio arranca y pide posiciones que no
                            // llegan nunca, así que el viaje no se publica y la pantalla se
                            // queda sin cartel y sin botón de salir. Se pregunta antes.
                            if (ConfigCheck.isLocationEnabled(context)) {
                                NavSession.start(context, ruta, simulate = false)
                            } else {
                                gpsPendiente = ruta
                            }
                        },
                        onSimulate = {
                            showSteps = false
                            NavSession.start(context, ruta, simulate = true)
                        },
                        onOptions = {
                            showSteps = false
                            showNavOptions = true
                        },
                        onDismiss = { showSteps = false },
                    )
                }
            }
            if (showNavOptions) {
                NavOptionsDialog(locator.settings) { showNavOptions = false }
            }
            // Simular no entra aquí a propósito: el viaje de mentira no pide GPS.
            gpsPendiente?.let { ruta ->
                GpsRequiredDialog(
                    onDismiss = { gpsPendiente = null },
                    onEnabled = {
                        gpsPendiente = null
                        NavSession.start(context, ruta, simulate = false)
                    },
                )
            }

            // Midiendo, la tarjeta de la regla ocupa el hueco del buscador: los dos flotan en
            // lo alto del mapa y buscar sitios mientras se mide no viene a cuento. Navegando,
            // ese sitio es del cartel de la maniobra y no hay ni regla ni buscador.
            if (measuring && navState == null) {
                MeasureInfoCard(
                    points = measurePoints,
                    liveMeters = measureLiveMeters,
                    onSave = {
                        if (!imageExport.busy) {
                            measureImageShare = false
                            showMeasureSaveOptions = true
                        }
                    },
                    onShare = {
                        if (!imageExport.busy) {
                            measureImageShare = true
                            showMeasureSaveOptions = true
                        }
                    },
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                        .widthIn(max = OVERLAY_MAX_W).fillMaxWidth(),
                )
            } else if (navState == null) {
                // Buscador flotante sobre el mapa. Al elegir un sitio hay que dejar de seguir a
                // nadie: si no, la siguiente ubicación del seguido recentraría el mapa y daría la
                // sensación de que la búsqueda no ha funcionado.
                PlaceSearchField(
                    nominatim = locator.nominatim,
                    label = stringResource(R.string.place_search_label),
                    servidor = locator.locationRepository,
                    settings = locator.settings,
                    modifier = Modifier.align(Alignment.TopCenter)
                        // Antes del padding a propósito: lo que tapa es la caja con su hueco.
                        .onSizeChanged { topOverlayPx = it.height }
                        .padding(8.dp)
                        .widthIn(max = OVERLAY_MAX_W).fillMaxWidth(),
                    // Lo que se está viendo manda: buscando "McDonald's" mientras se mira dónde
                    // está alguien, el de al lado suyo antes que el de la otra punta del mundo.
                    nearBox = {
                        mapControl?.nearBox()
                    },
                    onCleared = { searchHit = null },
                    textoInicial = sharedPlace.orEmpty(),
                ) { place ->
                    searchHit = place
                    viewModel.setFollowing(null)
                    // De golpe y sin animar, ver [MapControl.jumpTo].
                    mapControl?.jumpTo(GeoPoint(place.lat, place.lng), Constants.MAP_SEARCH_RESULT_ZOOM)
                }
            }

            // Botón "dónde estoy" + seguir en vivo (a quien se elija), a la izquierda, para
            // que no se solape con los de la derecha. Con una ruta trazada o en marcha no se
            // enseñan: abajo manda la ruta —su tarjeta o la barra de navegación— y encima de
            // estos botones no cabe. Vuelven al cerrar la ruta.
            if (planner.route == null && navState == null && isochrone.member == null) {
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
                                        mapControl?.setZoom(initialZoom.toDouble())
                                        showFollowMenu = false
                                    },
                                )
                            }
                        }
                    }
                    FloatingActionButton(
                        onClick = {
                            lastKnownLocation(context) { mapControl?.moveTo(GeoPoint(it.latitude, it.longitude)) }
                        },
                    ) {
                        Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.locate_me))
                    }
                }
            }

            // Compartir ubicación. Invitar con el código del grupo ya vive en la info del grupo.
            // Con una ruta en pantalla se esconde: al lado del compartir de la tarjeta parecían
            // el mismo botón, y este manda tu posición, no la ruta.
            if (planner.route == null && navState == null && isochrone.member == null) {
                FloatingActionButton(
                    onClick = { shareCurrentLocation(context) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share_current_location))
                }
            }

            // Tumbado, la barra de arriba vive aquí: un botón en la esquina que se abre hacia
            // la izquierda con los mismos botones. Plegado ocupa 40 dp de esquina en vez de una
            // franja de 64 dp de lado a lado, y el mapa llega hasta el borde de arriba.
            if (tumbado) {
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedVisibility(
                        visible = accionesAbiertas,
                        enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
                        exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(),
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            tonalElevation = 3.dp,
                            shadowElevation = 3.dp,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // El grupo: de pie es el icono de la izquierda de la barra.
                                IconButton(onClick = onOpenGroup) {
                                    Icon(
                                        Icons.Filled.Group,
                                        contentDescription = stringResource(R.string.group_title),
                                    )
                                }
                                acciones()
                            }
                        }
                    }
                    SmallFloatingActionButton(
                        onClick = { accionesAbiertas = !accionesAbiertas },
                        // En modo prueba el botón se pone en rojo: tumbado no hay barra donde
                        // poner el cartel de "modo prueba", y eso no se puede quedar sin avisar.
                        containerColor = if (testMode) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surface,
                    ) {
                        Icon(
                            if (accionesAbiertas) Icons.Filled.ChevronRight else Icons.Filled.MoreVert,
                            contentDescription = stringResource(
                                if (accionesAbiertas) R.string.close else R.string.map_tools,
                            ),
                        )
                    }
                }
            }
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

    if (showMeasureSaveOptions) {
        MeasureSaveOptionsDialog(
            share = measureImageShare,
            midPoints = measureImageMidPoints,
            segmentDistances = measureImageSegmentDistances,
            fitRoute = measureImageFitRoute,
            onMidPointsChange = { measureImageMidPoints = it },
            onSegmentDistancesChange = { measureImageSegmentDistances = it },
            onFitRouteChange = { measureImageFitRoute = it },
            onDismiss = { showMeasureSaveOptions = false },
            onConfirm = {
                showMeasureSaveOptions = false
                val map = mapControl
                if (map != null) {
                    val route = measurePoints.toList()
                    val withMidPoints = measureImageMidPoints
                    val withSegmentDistances = measureImageSegmentDistances
                    val withFitRoute = measureImageFitRoute
                    val sharing = measureImageShare
                    imageExport.ask { size, onProgress ->
                        val saved = saveMeasureImage(
                            context, map, route, withMidPoints, withSegmentDistances, withFitRoute,
                            share = sharing, size = size, onProgress = onProgress,
                        )
                        // Compartiendo no hay nada que anunciar cuando sale bien: lo que se ve es
                        // el diálogo del sistema abriéndose encima.
                        if (!saved || !sharing) {
                            val message = if (saved) R.string.measure_saved else R.string.measure_save_failed
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
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
                TextButton(enabled = !updating, onClick = { runUpdate() }) {
                    Text(stringResource(R.string.update_available_now))
                }
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
