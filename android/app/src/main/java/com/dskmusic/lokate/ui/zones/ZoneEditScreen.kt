package com.dskmusic.lokate.ui.zones

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.dto.ZoneDto
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.ui.map.OsmMapView
import com.google.android.gms.location.LocationServices
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

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
        ZoneEditViewModel(locator.zoneRepository, locator.nominatim, existingZone)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

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
    // actual arriba — ajustando el zoom para que se vea el círculo completo con margen.
    // setCenter/zoomToBoundingBox (no animateTo): la ubicación inicial y la del resultado de
    // búsqueda pueden resolver casi a la vez que el mapa termina de montarse — encadenar
    // animaciones ahí es lo que daba la sensación de que el mapa "se volvía loco" moviéndose solo.
    LaunchedEffect(state.lat, state.lng, state.radiusM, mapViewRef) {
        val lat = state.lat
        val lng = state.lng
        val map = mapViewRef
        if (lat != null && lng != null && map != null) {
            // Margen del 40% alrededor del radio para que el círculo no toque los bordes del mapa.
            val deltaLat = (state.radiusM * 1.4) / 111_320.0
            val deltaLng = deltaLat / kotlin.math.max(kotlin.math.cos(Math.toRadians(lat)), 0.2)
            val box = org.osmdroid.util.BoundingBox(lat + deltaLat, lng + deltaLng, lat - deltaLat, lng - deltaLng)
            map.post {
                runCatching { map.zoomToBoundingBox(box, false) }
                    .onFailure { map.controller.setCenter(GeoPoint(lat, lng)) }
            }
        }
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

            Box {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it; viewModel.searchAddress(it) },
                    label = { Text(stringResource(R.string.zone_search_address)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                DropdownMenu(
                    expanded = state.searchResults.isNotEmpty(),
                    onDismissRequest = viewModel::clearSearchResults,
                    modifier = Modifier.fillMaxWidth(0.92f),
                ) {
                    state.searchResults.forEach { result ->
                        DropdownMenuItem(
                            text = { Text(result.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                viewModel.selectSearchResult(result)
                                searchQuery = result.label
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.zone_radius_label) + ": ${state.radiusM.toInt()} m")
            Slider(
                value = state.radiusM.toFloat().coerceIn(20f, 3000f),
                onValueChange = { viewModel.updateRadius(it.toDouble()) },
                valueRange = 20f..3000f,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = state.radiusM.toInt().toString(),
                onValueChange = { text -> text.toDoubleOrNull()?.let { viewModel.updateRadius(it) } },
                label = { Text(stringResource(R.string.zone_radius_manual_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
            )

            Spacer(Modifier.height(12.dp))
            Text("Toca el mapa para elegir el centro de la zona, o busca una dirección arriba")
            Spacer(Modifier.height(4.dp))
            Box(modifier = Modifier.fillMaxWidth().height(280.dp).clip(MaterialTheme.shapes.medium)) {
                // remember() es clave aquí: sin esto se crea una lista nueva en cada recomposición
                // (aunque el contenido no cambie), lo que hace que OsmMapView reconstruya todos los
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
                OsmMapView(
                    members = emptyList(),
                    zones = previewZones,
                    modifier = Modifier.fillMaxSize(),
                    onMapTap = { point -> viewModel.pickLocation(point.latitude, point.longitude) },
                    onMapReady = { mapViewRef = it },
                )
            }

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = viewModel::save,
                enabled = state.name.isNotBlank() && state.lat != null && state.lng != null && !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.zone_save))
            }
        }
    }
}
