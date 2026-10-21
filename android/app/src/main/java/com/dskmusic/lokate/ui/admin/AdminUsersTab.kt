package com.dskmusic.lokate.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.absoluteAvatarUrl
import com.dskmusic.lokate.data.remote.dto.AdminGroupDto
import com.dskmusic.lokate.data.remote.dto.AdminUserDto
import com.dskmusic.lokate.ui.common.rememberAvatarPicker

@Composable
internal fun AdminUsersTab(
    state: AdminUiState,
    viewModel: AdminViewModel,
    query: String = "",
    onOpenMember: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<AdminUserDto?>(null) }
    var deleting by remember { mutableStateOf<AdminUserDto?>(null) }
    var ringConfirm by remember { mutableStateOf<AdminUserDto?>(null) }
    var notifyConfirm by remember { mutableStateOf<AdminUserDto?>(null) }
    var creating by remember { mutableStateOf(false) }
    // rememberSaveable y no remember: entre tocar la foto y volver del recorte hay dos pantallas
    // de por medio, y si el sistema se lleva por delante esta de aquí en el camino, al volver ya
    // no sabríamos a quién se le estaba cambiando la foto.
    var avatarTargetId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (state.groups.isEmpty()) viewModel.loadGroups()
    }

    // Mismo recorte circular que cuando uno se pone su propia foto: ampliando con dos dedos y
    // moviendo la imagen bajo el círculo.
    val pickAvatar = rememberAvatarPicker { uri ->
        avatarTargetId?.let { viewModel.uploadAvatar(it, uri, context) }
        avatarTargetId = null
    }

    // Por nombre y por usuario: en la lista se ven los dos, y buscar "pepe" por el usuario es
    // tan natural como por el nombre.
    val users = remember(state.users, query) {
        val text = query.trim()
        if (text.isEmpty()) {
            state.users
        } else {
            state.users.filter {
                it.display_name.contains(text, ignoreCase = true) || it.username.contains(text, ignoreCase = true)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (state.loadingUsers && state.users.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(users, key = { it.id }) { user ->
                    ListItem(
                        // Tocar el nombre abre su ficha completa (la misma del mapa): permisos
                        // sin dar, batería, wifi, con qué frecuencia manda, versión instalada.
                        // Vale con cualquiera sin cambiarse a su grupo — ver el backend,
                        // reachable_member. El avatar y los cuatro botones se quedan con su
                        // propio toque, así que lo pulsable es justo el nombre y el @usuario.
                        modifier = Modifier.clickable { onOpenMember(user.id) },
                        leadingContent = {
                            AsyncImage(
                                model = absoluteAvatarUrl(user.avatar_url),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { avatarTargetId = user.id; pickAvatar() },
                            )
                        },
                        headlineContent = { Text(user.display_name) },
                        supportingContent = { Text("@${user.username}" + if (user.is_admin) " · admin" else "") },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { notifyConfirm = user }) {
                                    Icon(Icons.Filled.Notifications, contentDescription = stringResource(R.string.settings_send_test_notification))
                                }
                                // Este botón hace sonar el dispositivo (el push que manda es
                                // "ring"), así que funciona como interruptor: se queda rojo
                                // mientras suena y al volver a pulsarlo lo calla.
                                val ringing = user.id in state.ringingUserIds
                                IconButton(onClick = {
                                    // Al callar no se pregunta: darle sin querer no molesta a nadie.
                                    if (ringing) viewModel.toggleRing(user.id) else ringConfirm = user
                                }) {
                                    Icon(
                                        Icons.Filled.LocationOn,
                                        contentDescription = stringResource(
                                            if (ringing) R.string.ring_stop_action else R.string.ring_device_button,
                                        ),
                                        tint = if (ringing) MaterialTheme.colorScheme.error else LocalContentColor.current,
                                    )
                                }
                                IconButton(onClick = { editing = user }) {
                                    Icon(Icons.Filled.Edit, contentDescription = null)
                                }
                                IconButton(onClick = { deleting = user }) {
                                    Icon(Icons.Filled.Delete, contentDescription = null)
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }

        FloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.admin_add)) }
    }

    editing?.let { user ->
        var name by remember(user.id) { mutableStateOf(user.display_name) }
        var isAdmin by remember(user.id) { mutableStateOf(user.is_admin) }
        var group by remember(user.id) { mutableStateOf(state.groups.firstOrNull { it.id == user.group_id }) }
        // El grupo solo se manda si se ha tocado el desplegable: si la lista de grupos no llegó a
        // cargarse, cambiarle el nombre no debe dejarle sin grupo de rebote.
        var groupTouched by remember(user.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(user.display_name) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
                    GroupDropdown(
                        groups = state.groups,
                        selected = group,
                        onSelect = { group = it; groupTouched = true },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isAdmin, onCheckedChange = { isAdmin = it })
                        Text(stringResource(R.string.settings_admin_section))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateUser(
                        user.id,
                        displayName = name,
                        isAdmin = isAdmin,
                        // Cadena vacía = "sin grupo" (ver AdminUserUpdateRequestDto).
                        groupId = if (groupTouched) group?.id.orEmpty() else null,
                    ) { editing = null }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    deleting?.let { user ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(user.display_name) },
            text = { Text(stringResource(R.string.admin_confirm_delete)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteUser(user.id) { deleting = null } }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    ringConfirm?.let { user ->
        AlertDialog(
            onDismissRequest = { ringConfirm = null },
            title = { Text(stringResource(R.string.ring_confirm_title)) },
            text = { Text(stringResource(R.string.ring_confirm_body, user.display_name)) },
            confirmButton = {
                TextButton(onClick = { ringConfirm = null; viewModel.toggleRing(user.id) }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { ringConfirm = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    notifyConfirm?.let { user ->
        AlertDialog(
            onDismissRequest = { notifyConfirm = null },
            title = { Text(stringResource(R.string.notify_test_confirm_title)) },
            text = { Text(stringResource(R.string.notify_test_confirm_body, user.display_name)) },
            confirmButton = {
                TextButton(onClick = { notifyConfirm = null; viewModel.notifyTest(user.id) }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { notifyConfirm = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (creating) {
        CreateUserDialog(
            groups = state.groups,
            onDismiss = { creating = false },
            onCreate = { username, password, displayName, groupId, isAdmin ->
                viewModel.createUser(username, password, displayName, groupId, isAdmin) { creating = false }
            },
        )
    }
}

@Composable
private fun CreateUserDialog(
    groups: List<AdminGroupDto>,
    onDismiss: () -> Unit,
    onCreate: (username: String, password: String, displayName: String, groupId: String?, isAdmin: Boolean) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var isAdmin by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf<AdminGroupDto?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.admin_create_user_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.display_name_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.username_label)) },
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.padding(top = 8.dp),
                )
                GroupDropdown(
                    groups = groups,
                    selected = selectedGroup,
                    onSelect = { selectedGroup = it },
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isAdmin, onCheckedChange = { isAdmin = it })
                    Text(stringResource(R.string.settings_admin_section))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank() && password.length >= 8 && displayName.isNotBlank(),
                onClick = { onCreate(username, password, displayName, selectedGroup?.id, isAdmin) },
            ) { Text(stringResource(R.string.admin_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Elegir grupo (o ninguno), igual al crear un usuario que al editarlo. */
@Composable
private fun GroupDropdown(
    groups: List<AdminGroupDto>,
    selected: AdminGroupDto?,
    onSelect: (AdminGroupDto?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = selected?.name ?: stringResource(R.string.admin_no_group),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.group_title)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // readOnly no impide que el campo se quede el toque, así que el menú lo abre este de
        // encima.
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.admin_no_group)) },
                onClick = { onSelect(null); expanded = false },
            )
            groups.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.name) },
                    onClick = { onSelect(group); expanded = false },
                )
            }
        }
    }
}
