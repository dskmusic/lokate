package com.dskmusic.lokate.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.absoluteAvatarUrl
import com.dskmusic.lokate.data.remote.dto.AdminActivityDayDto
import com.dskmusic.lokate.data.remote.dto.AdminDiskUsageDto
import com.dskmusic.lokate.data.remote.dto.AdminRecentActivityItemDto

@Composable
internal fun AdminDashboardTab(state: AdminUiState, onRefresh: () -> Unit) {
    val dashboard = state.dashboard
    if (state.loadingDashboard && dashboard == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (dashboard == null) return

    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.admin_tab_dashboard), style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.retry)) }
            }
        }
        item {
            val cards = listOf(
                stringResource(R.string.admin_stat_groups) to dashboard.stats.groups.toString(),
                stringResource(R.string.admin_stat_users) to dashboard.stats.users.toString(),
                stringResource(R.string.admin_stat_online) to dashboard.stats.online_now.toString(),
                stringResource(R.string.admin_stat_pings_24h) to dashboard.stats.pings_24h.toString(),
                stringResource(R.string.admin_stat_active_24h) to dashboard.stats.active_users_24h.toString(),
                stringResource(R.string.admin_stat_push) to dashboard.stats.devices_with_push.toString(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cards.chunked(2).forEach { rowCards ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowCards.forEach { (label, value) ->
                            Box(Modifier.weight(1f)) { StatCard(label, value) }
                        }
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.admin_dashboard_activity_7d),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
        }
        item { ActivityChart(dashboard.activity_series) }
        state.diskUsage?.let { usage ->
            item {
                Text(
                    stringResource(R.string.admin_storage_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                )
            }
            item { StorageBreakdown(usage) }
        }
        item {
            Text(
                stringResource(R.string.admin_dashboard_recent_activity),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
        }
        if (dashboard.recent_activity.isEmpty()) {
            item { Text(stringResource(R.string.admin_dashboard_no_activity), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(dashboard.recent_activity) { activity: AdminRecentActivityItemDto ->
                ListItem(
                    leadingContent = {
                        AsyncImage(
                            model = absoluteAvatarUrl(activity.avatar_url),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    },
                    headlineContent = { Text(activity.display_name) },
                    supportingContent = { Text(activity.timestamp.replace("T", " ").take(16)) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

internal fun formatBytes(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb < 1000) "%.1f MB".format(mb) else "%.2f GB".format(mb / 1024)
}

@Composable
private fun StorageBreakdown(usage: AdminDiskUsageDto) {
    val rows = listOf(
        stringResource(R.string.admin_storage_database) to usage.database_bytes,
        stringResource(R.string.admin_storage_avatars) to usage.avatars_bytes,
        stringResource(R.string.admin_storage_attachments) to usage.attachments_bytes,
        stringResource(R.string.admin_storage_apk) to usage.apk_bytes,
        stringResource(R.string.admin_storage_web_static) to usage.web_static_bytes,
        stringResource(R.string.admin_storage_backups) to usage.backups_bytes,
    )
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(formatBytes(value), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.admin_storage_total), style = MaterialTheme.typography.titleSmall)
                Text(formatBytes(usage.total_bytes), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun ActivityChart(series: List<AdminActivityDayDto>) {
    Row(
        Modifier.fillMaxWidth().height(140.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        series.forEach { day ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(day.pct.coerceIn(2, 100).dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                Text(day.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
        }
    }
}
