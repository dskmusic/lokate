package com.dskmusic.lokate.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.dto.AdminUpdateNoticeStateDto
import com.dskmusic.lokate.util.formatTimestamp

private enum class NoticeTarget { ALL, GROUP, USER }

/**
 * Aviso de "actualiza la app" que un admin manda a todo el mundo, a un grupo o a una persona.
 *
 * El texto llega tal cual a la notificación del móvil de destino, que la dibuja la propia app
 * (no el sistema) para poder ponerle el botón de actualizar: ver
 * [com.dskmusic.lokate.util.NotificationHelper.showUpdateNotification].
 */
@Composable
fun UpdateNoticeDialog(viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    val groups by viewModel.adminGroups.collectAsStateWithLifecycle()
    val users by viewModel.adminUsers.collectAsStateWithLifecycle()
    val result by viewModel.updateNoticeResult.collectAsStateWithLifecycle()
    val error by viewModel.updateNoticeError.collectAsStateWithLifecycle()
    val states by viewModel.updateNoticeStates.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadUpdateNoticeStates() }

    val default = stringResource(R.string.settings_update_notice_default)
    var message by remember { mutableStateOf(default) }
    var target by remember { mutableStateOf(NoticeTarget.ALL) }
    var groupId by remember { mutableStateOf<String?>(null) }
    var userId by remember { mutableStateOf<String?>(null) }

    val ready = when (target) {
        NoticeTarget.ALL -> true
        NoticeTarget.GROUP -> groupId != null
        NoticeTarget.USER -> userId != null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_update_notice)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text(stringResource(R.string.settings_update_notice_text)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        NoticeTarget.ALL to R.string.settings_update_notice_all,
                        NoticeTarget.GROUP to R.string.settings_update_notice_group,
                        NoticeTarget.USER to R.string.settings_update_notice_user,
                    ).forEach { (option, label) ->
                        FilterChip(
                            selected = target == option,
                            onClick = { target = option },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                when (target) {
                    NoticeTarget.GROUP -> TargetDropdown(
                        placeholder = stringResource(R.string.settings_update_notice_pick_group),
                        options = groups.map { it.id to it.name },
                        selectedId = groupId,
                        onPick = { groupId = it },
                    )

                    NoticeTarget.USER -> TargetDropdown(
                        placeholder = stringResource(R.string.settings_update_notice_pick_user),
                        options = users.map { user ->
                            val group = groups.firstOrNull { it.id == user.group_id }?.name
                            user.id to (if (group != null) user.display_name + " (" + group + ")" else user.display_name)
                        },
                        selectedId = userId,
                        onPick = { userId = it },
                    )

                    NoticeTarget.ALL -> Unit
                }
                result?.let {
                    Text(
                        stringResource(R.string.settings_update_notice_sent, it.sent, it.without_token),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                }
                LastNoticeSection(states, onRefresh = { viewModel.loadUpdateNoticeStates() })
            }
        },
        confirmButton = {
            TextButton(
                // Tras enviar se bloquea: si hiciera falta repetirlo, se cierra y se vuelve a abrir.
                enabled = ready && result == null,
                onClick = {
                    viewModel.sendUpdateNotice(
                        message = message,
                        groupId = if (target == NoticeTarget.GROUP) groupId else null,
                        userIds = if (target == NoticeTarget.USER) listOfNotNull(userId) else null,
                    )
                },
            ) { Text(stringResource(R.string.settings_update_notice_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (result != null) R.string.close else R.string.cancel))
            }
        },
    )
}

/**
 * Cómo ha quedado el último aviso de cada persona, lo más reciente arriba. Solo salen quienes
 * han recibido alguno; el estado lo cambia su propio móvil al actualizar o al descartar, así que
 * se refresca a mano con el botón (no hay nada que empuje esto desde el servidor).
 */
@Composable
private fun LastNoticeSection(states: List<AdminUpdateNoticeStateDto>, onRefresh: () -> Unit) {
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(R.string.settings_update_notice_last),
            style = MaterialTheme.typography.titleSmall,
        )
        IconButton(onClick = onRefresh) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.settings_update_notice_refresh),
            )
        }
    }
    if (states.isEmpty()) {
        Text(
            stringResource(R.string.settings_update_notice_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    states.forEach { state ->
        val who = if (state.group_name != null) state.user_name + " (" + state.group_name + ")" else state.user_name
        val label = stringResource(
            when (state.status) {
                "started" -> R.string.settings_update_notice_state_started
                "installed" -> R.string.settings_update_notice_state_installed
                "dismissed" -> R.string.settings_update_notice_state_dismissed
                else -> R.string.settings_update_notice_state_sent
            }
        )
        // Fecha del envío arriba; abajo el estado con la fecha en que contestó (si contestó) y la
        // versión que dijo tener, que es la única prueba de qué se instaló de verdad.
        val detail = buildString {
            append(label)
            state.status_at?.let { append(" · ").append(formatTimestamp(it)) }
            state.app_version?.let { append(" · v").append(it) }
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(who + " · " + formatTimestamp(state.sent_at), style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.status == "dismissed") MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** ponytail: un chip con su menú, igual que en el registro de zonas; no hace falta un
 * ExposedDropdownMenuBox porque aquí no se escribe nada, solo se elige. */
@Composable
private fun TargetDropdown(
    placeholder: String,
    options: List<Pair<String, String>>,
    selectedId: String?,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.padding(top = 8.dp)) {
        FilterChip(
            selected = selectedId != null,
            onClick = { expanded = true },
            label = { Text(options.firstOrNull { it.first == selectedId }?.second ?: placeholder) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onPick(id)
                        expanded = false
                    },
                )
            }
        }
    }
}
