package com.dskmusic.lokate.ui.zones

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.dto.ZoneDto
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.ui.common.PlaceSearchField
import com.dskmusic.lokate.ui.common.RadiusField
import com.dskmusic.lokate.ui.common.cabe
import com.dskmusic.lokate.ui.map.LokateMapView
import com.dskmusic.lokate.ui.map.MapControl
import com.dskmusic.lokate.util.MapStyle
import com.google.android.gms.location.LocationServices

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ZoneEditScreen(locator: ServiceLocator, zoneId: String?, onDone: () -> Unit) {
    val zones by locator.zoneRepository.observeZones().collectAsStateWithLifecycle(initialValue = emptyList())

    // Si es edición, esperamos a que la zona exista en la caché local (ya sincronizada al listar).
    if (zoneId != null && zones.none { it.id == zoneId }) {
        Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        return
    }
    val existingZone = zones.find { it.id == zoneId }

    ZoneEditContent(locator = locator, existingZone = existingZone, onDone = onDone)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ZoneEditContent(locator: ServiceLocator, existingZone: ZoneDto?, onDone: () -> Unit) {
    val context = LocalContext.current
    val viewModel = remember(existingZone?.id) {
        ZoneEditViewModel(
            locator.zoneRepository, locator.groupRepository, locator.locationRepository, existingZone,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val showZoneMargin by locator.settings.mapShowZoneMargin.collectAsStateWithLifecycle(initialValue = true)
    val zoneFillIntensity by locator.settings.mapZoneFillIntensity
        .collectAsStateWithLifecycle(initialValue = com.dskmusic.lokate.util.Constants.MAP_ZONE_FILL_INTENSITY_DEFAULT)
    val zoneColor by locator.settings.mapZoneColor
        .collectAsStateWithLifecycle(initialValue = com.dskmusic.lokate.util.Constants.MAP_ZONE_COLOR_DEFAULT)
    val zoneMarginColor by locator.settings.mapZoneMarginColor
        .collectAsStateWithLifecycle(initialValue = com.dskmusic.lokate.util.Constants.MAP_ZONE_MARGIN_COLOR_DEFAULT)
    // El estilo elegido en el mapa manda también aquí: la zona se coloca sobre el mismo
    // mapa que se va a mirar luego.
    val mapStyle by locator.settings.mapStyle.collectAsStateWithLifecycle(initialValue = MapStyle.STANDARD)
    var mapControl by remember { mutableStateOf<MapControl?>(null) }
    var nuevaWifi by remember { mutableStateOf("") }

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    // Zona nueva sin punto todavía: arrancamos en la ubicación actual del dispositivo, como
    // punto de partida para ajustar desde ahí (tocando el mapa o buscando una dirección).
    LaunchedEffect(Unit) {
        if (existingZone == null && state.lat == null) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                LocationServices.getFusedLocationProviderClient(context).lastLocation
                    .addOnSuccessListener { location ->
                        if (location != null) viewModel.pickLocation(location.latitude, location.longitude)
                    }
            }
        }
    }

    // El mapa sigue siempre al punto y radio elegidos: al tocarlo, al elegir un resultado de
    // búsqueda, al mover el slider del radio, o al posicionarse por defecto en la ubicación
    // actual arriba (ver [frameZone], el mismo encuadre que al editar una zona desde el panel).
    LaunchedEffect(state.lat, state.lng, state.radiusM, mapControl) {
        val lat = state.lat
        val lng = state.lng
        if (lat != null && lng != null) mapControl?.frameZone(lat, lng, state.radiusM)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.zone_add)) },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::updateName,
                label = { Text(stringResource(R.string.zone_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))

            // Pública por defecto: es lo que espera quien no se fija en este interruptor, y lo
            // que hacían todas las zonas antes de que existiera.
            Row(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.updateVisibility(!state.isPublic) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.zone_public_label))
                    Text(
                        stringResource(if (state.isPublic) R.string.zone_public_hint else R.string.zone_private_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.isPublic, onCheckedChange = viewModel::updateVisibility)
            }
            Spacer(Modifier.height(8.dp))

            PlaceSearchField(
                nominatim = locator.nominatim,
                label = stringResource(R.string.place_search_label),
                servidor = locator.locationRepository,
                settings = locator.settings,
                modifier = Modifier.fillMaxWidth(),
                onPicked = { viewModel.pickLocation(it.lat, it.lng) },
            )

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.zone_radius_label) + ": ${state.radiusM.toInt()} m")
            Slider(
                value = state.radiusM.toFloat().coerceIn(20f, 3000f),
                onValueChange = { viewModel.updateRadius(it.toDouble()) },
                valueRange = 20f..3000f,
            )
            Spacer(Modifier.height(4.dp))
            RadiusField(
                radiusM = state.radiusM,
                onRadiusChange = viewModel::updateRadius,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.zone_map_hint))
            Spacer(Modifier.height(4.dp))
            Box(modifier = Modifier.fillMaxWidth().height(cabe(280.dp)).clip(MaterialTheme.shapes.medium)) {
                // remember() es clave aquí: sin esto se crea una lista nueva en cada recomposición
                // (aunque el contenido no cambie), lo que hace que el mapa reconstruya todos los
                // overlays constantemente — incluso a mitad de un gesto de arrastre, dando la
                // sensación de que el mapa "se vuelve loco" al moverlo.
                val previewZones = remember(state.lat, state.lng, state.radiusM, state.name, existingZone?.id) {
                    val lat = state.lat
                    val lng = state.lng
                    if (lat != null && lng != null) {
                        listOf(ZoneDto(existingZone?.id ?: "preview", state.name.ifBlank { "..." }, lat, lng, state.radiusM))
                    } else {
                        emptyList()
                    }
                }
                LokateMapView(
                    members = emptyList(),
                    zones = previewZones,
                    modifier = Modifier.fillMaxSize(),
                    // Aquí es donde más se agradece verlo: se está eligiendo el radio.
                    showZoneMargin = showZoneMargin,
                    zoneFillIntensity = zoneFillIntensity,
                    zoneColor = zoneColor,
                    zoneMarginColor = zoneMarginColor,
                    onMapTap = { point -> viewModel.pickLocation(point.latitude, point.longitude) },
                    mapStyle = mapStyle,
                    onMapControl = { mapControl = it },
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.zone_wifi_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.zone_wifi_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.wifiOptions.forEach { (ssid, quien) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.toggleSsid(ssid) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = ssid in state.wifiSsids,
                        onCheckedChange = { viewModel.toggleSsid(ssid) },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(ssid)
                        if (quien != null) {
                            Text(
                                stringResource(R.string.zone_wifi_seen_by, quien),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            // Para la wifi de un sitio donde ahora mismo no hay nadie: se escribe tal cual
            // aparece en los ajustes de wifi del móvil (distingue mayúsculas).
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = nuevaWifi,
                    onValueChange = { nuevaWifi = it },
                    label = { Text(stringResource(R.string.zone_wifi_manual_label)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                TextButton(
                    onClick = {
                        viewModel.addSsid(nuevaWifi)
                        nuevaWifi = ""
                    },
                    enabled = nuevaWifi.isNotBlank(),
                ) {
                    Text(stringResource(R.string.zone_wifi_add))
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.zone_watch_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.zone_watch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.members.forEach { member ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.toggleWatched(member.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = member.id in state.watchedIds,
                        onCheckedChange = { viewModel.toggleWatched(member.id) },
                    )
                    Text(member.display_name)
                }
            }
            if (state.members.isNotEmpty() && state.watchedIds.isEmpty()) {
                Text(
                    stringResource(R.string.zone_watch_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // No se han podido leer mis avisos de esta zona: se dice, y al guardar se dejan tal
            // cual estén en el servidor en vez de pisarlos con lo que esta pantalla no sabe.
            if (state.prefsUnavailable) {
                Text(
                    stringResource(R.string.zone_watch_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = viewModel::save,
                enabled = state.name.isNotBlank() && state.lat != null && state.lng != null &&
                    // Sin lista de miembros (por ejemplo sin conexion) no se bloquea el guardado
                    // de la zona; lo que se bloquea entonces es tocar la preferencia de avisos,
                    // que es lo unico que esta pantalla no puede saber (ver prefsUnavailable).
                    (state.watchedIds.isNotEmpty() || state.members.isEmpty()) && !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.zone_save))
            }
        }
    }
}
