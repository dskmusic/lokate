package com.dskmusic.lokate.ui.history

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import com.dskmusic.lokate.data.local.LocationHistoryEntity
import com.dskmusic.lokate.data.remote.absoluteAvatarUrl
import com.dskmusic.lokate.data.remote.dto.GroupMemberDto
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.ui.map.HistoryMapView
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var focusedPoint by remember { mutableStateOf<LocationHistoryEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.selectedDisplayName ?: stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
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
                Text(
                    state.selectedDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider()

            if (points.isEmpty() && !state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.history_no_points), style = MaterialTheme.typography.bodyMedium)
                }
                return@Column
            }

            if (points.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(220.dp).clip(MaterialTheme.shapes.medium)) {
                    HistoryMapView(
                        points = points.sortedBy { it.timestampMillis },
                        focusedPoint = focusedPoint,
                        modifier = Modifier.fillMaxSize(),
                        onMapReady = { mapViewRef = it },
                    )
                }
            }

            val sdf = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(points.sortedByDescending { it.timestampMillis }, key = { it.id }) { point ->
                    ListItem(
                        headlineContent = { Text(sdf.format(java.util.Date(point.timestampMillis))) },
                        supportingContent = { Text("%.5f, %.5f".format(point.lat, point.lng)) },
                        modifier = Modifier.clickable {
                            focusedPoint = point
                            mapViewRef?.controller?.animateTo(GeoPoint(point.lat, point.lng))
                        },
                    )
                }
            }
        }
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
