package com.dskmusic.lokate.ui.history

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.local.LocationHistoryEntity
import com.dskmusic.lokate.data.remote.absoluteAvatarUrl
import com.dskmusic.lokate.data.remote.dto.GroupMemberDto
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.ui.map.HistoryMapView
import com.dskmusic.lokate.ui.map.MapStyleMenuButton
import com.dskmusic.lokate.ui.map.routeDistanceMeters
import com.dskmusic.lokate.ui.map.snapshotWithCaption
import com.dskmusic.lokate.ui.map.moveTo
import com.dskmusic.lokate.util.LocationSharing
import com.dskmusic.lokate.util.MediaSaver
import com.dskmusic.lokate.util.MapStyle
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.views.MapView
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Lo que se espera tras el último empujón del mapa para dar por buena la vista. */
private const val MAP_SETTLE_MS = 300L

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    locator: ServiceLocator,
    targetUserId: String?,
    targetDisplayName: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = remember(targetUserId) {
        HistoryViewModel(
            locator.locationRepository,
            locator.groupRepository,
            locator.settings,
            locator.session,
            targetUserId,
            targetDisplayName,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val points by viewModel.points.collectAsStateWithLifecycle(initialValue = emptyList())
    var showPicker by remember { mutableStateOf(false) }
    // La lista de puntos se pone POR ENCIMA del mapa en vez de sustituirlo: así el mapa no se
    // destruye al alternar (volvería a recentrarse y a recargar teselas) y la foto se puede hacer
    // estés en la vista que estés.
    var showList by remember { mutableStateOf(false) }
    var showSnapshotOptions by remember { mutableStateOf(false) }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var focusedPoint by remember { mutableStateOf<LocationHistoryEntity?>(null) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // Mismo ajuste que el mapa principal: cambiar el estilo aquí lo cambia en los dos, que es lo
    // que espera quien lo toca (es "cómo se ve el mapa", no "cómo se ve esta pantalla").
    val mapStyle by locator.settings.mapStyle.collectAsStateWithLifecycle(initialValue = MapStyle.STANDARD)
    val sdf = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    // Ordenar miles de puntos en cada recomposición (dos veces, una por lista) era medio segundo
    // de parón cada vez que se tocaba algo; con remember solo se hace al cambiar el día o la persona.
    val oldestFirst = remember(points) { points.sortedBy { it.timestampMillis } }
    val newestFirst = remember(oldestFirst) { oldestFirst.asReversed() }
    val distanceMeters = remember(oldestFirst) { routeDistanceMeters(oldestFirst) }
    // Lo que se ve ahora en el mapa: cambia al mover o hacer zoom.
    val visibleBounds = rememberVisibleBounds(mapViewRef)
    val visibleMeters = remember(oldestFirst, visibleBounds) {
        routeDistanceMeters(oldestFirst, visibleBounds)
    }
    val distanceText = stringResource(R.string.history_distance, formatDistance(distanceMeters))
    val visibleText = stringResource(R.string.history_distance_visible, formatDistance(visibleMeters))
    val dateText = state.selectedDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    val hourFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val timeText = if (state.isFullDay) null else {
        "${state.startTime.format(hourFormatter)}\u2013${state.endTime.format(hourFormatter)}"
    }
    // Pie de la foto: de quién, de qué día (y de qué horas, si se acotaron) y lo recorrido en lo
    // que se ve. La distancia total no pinta nada en una imagen que enseña solo un trozo del día.
    val caption = listOfNotNull(
        state.selectedDisplayName,
        listOfNotNull(dateText, timeText).joinToString(" "),
        stringResource(R.string.history_snapshot_distance, formatDistance(visibleMeters)),
    ).joinToString(" · ")
    val snapshotName = "lokate-" +
        "${state.selectedDisplayName.orEmpty()}-$dateText".lowercase()
            .replace(Regex("[^a-z0-9]+"), "-").trim('-') + ".png"
    val fromTitle = stringResource(R.string.history_time_from)
    val toTitle = stringResource(R.string.history_time_to)
    val saveSnapshot: (Boolean) -> Unit = { share ->
        val bitmap = mapViewRef?.snapshotWithCaption(caption)
        if (bitmap == null) {
            Toast.makeText(context, R.string.history_snapshot_failed, Toast.LENGTH_SHORT).show()
        } else {
            scope.launch {
                val file = withContext(Dispatchers.IO) {
                    File(context.cacheDir, snapshotName).also { out ->
                        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
                if (share) {
                    MediaSaver.shareFile(context, file, "image/png")
                } else {
                    val saved = MediaSaver.saveToDevice(context, file, "image/png", snapshotName, forceDownloads = true)
                    val message = if (saved) R.string.history_snapshot_saved else R.string.history_snapshot_failed
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.selectedDisplayName ?: stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    IconButton(onClick = { showSnapshotOptions = true }, enabled = points.isNotEmpty()) {
                        Icon(
                            Icons.Filled.PhotoCamera,
                            contentDescription = stringResource(R.string.history_snapshot),
                        )
                    }
                    IconButton(onClick = { showList = !showList }, enabled = points.isNotEmpty()) {
                        Icon(
                            if (showList) Icons.Filled.Map else Icons.AutoMirrored.Filled.FormatListBulleted,
                            contentDescription = stringResource(
                                if (showList) R.string.history_view_map else R.string.history_view_list,
                            ),
                        )
                    }
                    MapStyleMenuButton { scope.launch { locator.settings.setMapStyle(it) } }
                    val selectedMember = state.members.firstOrNull { it.id == state.selectedUserId }
                    IconButton(onClick = { showPicker = true }) {
                        if (selectedMember?.avatar_url != null) {
                            AsyncImage(
                                model = absoluteAvatarUrl(selectedMember.avatar_url),
                                contentDescription = stringResource(R.string.history_choose_person),
                                modifier = Modifier.size(28.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                        } else {
                            Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.history_choose_person))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val today = remember { LocalDate.now() }
            val yesterday = remember { today.minusDays(1) }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.selectedDate == yesterday,
                    onClick = { viewModel.setYesterday(); focusedPoint = null },
                    label = { Text(stringResource(R.string.history_yesterday), maxLines = 1) },
                )
                FilterChip(
                    selected = state.selectedDate == today,
                    onClick = { viewModel.setToday(); focusedPoint = null },
                    label = { Text(stringResource(R.string.history_today), maxLines = 1) },
                )
                FilterChip(
                    selected = state.selectedDate != today && state.selectedDate != yesterday,
                    onClick = {
                        val d = state.selectedDate
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                viewModel.setDate(LocalDate.of(year, month + 1, day))
                                focusedPoint = null
                            },
                            d.year, d.monthValue - 1, d.dayOfMonth,
                        ).show()
                    },
                    label = { Text(stringResource(R.string.history_pick_date), maxLines = 1) },
                )
                FilterChip(
                    selected = !state.isFullDay,
                    onClick = {
                        // Dos diálogos encadenados (desde → hasta) con el selector del sistema: el
                        // de rango de Material3 sigue siendo experimental y esto no cuesta nada.
                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                val from = LocalTime.of(hour, minute)
                                TimePickerDialog(
                                    context,
                                    { _, endHour, endMinute ->
                                        viewModel.setTimeRange(from, LocalTime.of(endHour, endMinute))
                                        focusedPoint = null
                                    },
                                    state.endTime.hour, state.endTime.minute, true,
                                ).apply { setTitle(toTitle) }.show()
                            },
                            state.startTime.hour, state.startTime.minute, true,
                        ).apply { setTitle(fromTitle) }.show()
                    },
                    label = { Text(timeText ?: stringResource(R.string.history_all_day), maxLines = 1) },
                )
                if (!state.isFullDay) {
                    IconButton(onClick = { viewModel.clearTimeRange(); focusedPoint = null }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.history_all_day))
                    }
                }
            }
            HorizontalDivider()

            if (points.isEmpty() && !state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.history_no_points), style = MaterialTheme.typography.bodyMedium)
                }
                return@Column
            }

            if (points.isNotEmpty()) {
                Text(
                    "$distanceText · $visibleText",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Box(
                    Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp)
                        .clip(MaterialTheme.shapes.medium),
                ) {
                    HistoryMapView(
                        points = oldestFirst,
                        focusedPoint = focusedPoint,
                        mapStyle = mapStyle,
                        modifier = Modifier.fillMaxSize(),
                        onMapReady = { mapViewRef = it },
                        onPointSelected = { focusedPoint = it },
                    )
                    focusedPoint?.let { point ->
                        val coords = String.format(Locale.US, "%.5f, %.5f", point.lat, point.lng)
                        Card(
                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clickable {
                                clipboard.setText(AnnotatedString(coords))
                                // Android 13+ ya enseña su propio aviso al copiar; el Toast sobraría.
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                    Toast.makeText(context, R.string.history_coords_copied, Toast.LENGTH_SHORT).show()
                                }
                            },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp),
                            ) {
                                Column {
                                    Text(
                                        sdf.format(java.util.Date(point.timestampMillis)),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    Text(coords, style = MaterialTheme.typography.bodyMedium)
                                }
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.history_coords_copy),
                                    modifier = Modifier.padding(start = 12.dp).size(20.dp),
                                )
                                IconButton(
                                    onClick = {
                                        LocationSharing.openInGoogleMaps(
                                            context,
                                            point.lat,
                                            point.lng,
                                            state.selectedDisplayName.orEmpty(),
                                        )
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.Map,
                                        contentDescription = stringResource(R.string.history_open_maps),
                                    )
                                }
                                IconButton(onClick = { focusedPoint = null }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                                }
                            }
                        }
                    }
                    if (showList) {
                        // De lo más reciente a lo más antiguo, y al tocar un punto se vuelve al
                        // mapa ya centrado en él.
                        Surface(Modifier.fillMaxSize()) {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(newestFirst, key = { it.id }) { point ->
                                    ListItem(
                                        headlineContent = { Text(sdf.format(java.util.Date(point.timestampMillis))) },
                                        supportingContent = {
                                            Text(String.format(Locale.US, "%.5f, %.5f", point.lat, point.lng))
                                        },
                                        modifier = Modifier.clickable {
                                            focusedPoint = point
                                            mapViewRef?.moveTo(GeoPoint(point.lat, point.lng))
                                            showList = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSnapshotOptions) {
        AlertDialog(
            onDismissRequest = { showSnapshotOptions = false },
            title = { Text(stringResource(R.string.history_snapshot)) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.history_snapshot_save)) },
                        modifier = Modifier.clickable {
                            showSnapshotOptions = false
                            saveSnapshot(false)
                        },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.history_snapshot_share)) },
                        modifier = Modifier.clickable {
                            showSnapshotOptions = false
                            saveSnapshot(true)
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSnapshotOptions = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.history_choose_person)) },
            text = {
                Column {
                    state.members.forEach { member ->
                        MemberPickerRow(
                            member = member,
                            selected = member.id == state.selectedUserId,
                            onClick = {
                                viewModel.selectUser(member)
                                focusedPoint = null
                                showPicker = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

/**
 * Trozo de mundo que se está viendo, para contar solo lo que hay en pantalla.
 *
 * Se espera a que el mapa se quede quieto antes de tocar el estado: osmdroid avisa de cada píxel
 * que se arrastra, y recomponer en cada uno vuelve a pintar la ruta entera del día.
 */
@Composable
private fun rememberVisibleBounds(map: MapView?): BoundingBox? {
    var bounds by remember(map) { mutableStateOf(runCatching { map?.boundingBox }.getOrNull()) }
    DisposableEffect(map) {
        if (map == null) return@DisposableEffect onDispose { }
        val handler = Handler(Looper.getMainLooper())
        // boundingBox revienta si el mapa aún no se ha medido (primeros milisegundos).
        val settle = Runnable { bounds = runCatching { map.boundingBox }.getOrNull() }
        val listener = object : MapListener {
            fun schedule(): Boolean {
                handler.removeCallbacks(settle)
                handler.postDelayed(settle, MAP_SETTLE_MS)
                return false
            }

            override fun onScroll(event: ScrollEvent?) = schedule()
            override fun onZoom(event: ZoomEvent?) = schedule()
        }
        map.addMapListener(listener)
        handler.postDelayed(settle, MAP_SETTLE_MS)
        onDispose {
            handler.removeCallbacks(settle)
            map.removeMapListener(listener)
        }
    }
    return bounds
}

@Composable
private fun MemberPickerRow(member: GroupMemberDto, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = absoluteAvatarUrl(member.avatar_url),
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Text(
            member.display_name,
            modifier = Modifier.padding(start = 12.dp),
            style = if (selected) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Metros por debajo del kilómetro, kilómetros con un decimal por encima. */
private fun formatDistance(meters: Double): String =
    if (meters < 1000) String.format(Locale.getDefault(), "%.0f m", meters)
    else String.format(Locale.getDefault(), "%.1f km", meters / 1000)
