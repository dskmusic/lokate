package com.dskmusic.lokate.ui.admin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

@Composable
internal fun AdminUsersTab(state: AdminUiState, viewModel: AdminViewModel) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<AdminUserDto?>(null) }
    var deleting by remember { mutableStateOf<AdminUserDto?>(null) }
    var creating by remember { mutableStateOf(false) }
    var avatarTargetId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (state.groups.isEmpty()) viewModel.loadGroups()
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = avatarTargetId
        if (uri != null && id != null) viewModel.uploadAvatar(id, uri, context)
        avatarTargetId = null
    }

    Box(Modifier.fillMaxSize()) {
        if (state.loadingUsers && state.users.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.users, key = { it.id }) { user ->
                    ListItem(
                        leadingContent = {
                            AsyncImage(
                                model = absoluteAvatarUrl(user.avatar_url),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { avatarTargetId = user.id; pickImage.launch("image/*") },
                            )
                        },
                        headlineContent = { Text(user.display_name) },
                        supportingContent = { Text("@${user.username}" + if (user.is_admin) " · admin" else "") },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { viewModel.notifyTest(user.id) }) {
                                    Icon(Icons.Filled.Notifications, contentDescription = stringResource(R.string.settings_send_test_notification))
                                }
                                IconButton(onClick = { viewModel.locateUser(user.id) }) {
                                    Icon(Icons.Filled.LocationOn, contentDescription = stringResource(R.string.refresh_location))
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
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(user.display_name) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isAdmin, onCheckedChange = { isAdmin = it })
                        Text(stringResource(R.string.settings_admin_section))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateUser(user.id, displayName = name, isAdmin = isAdmin) { editing = null }
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
    var expanded by remember { mutableStateOf(false) }
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
                Box(Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = selectedGroup?.name ?: stringResource(R.string.admin_no_group),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.group_title)) },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(Modifier.matchParentSize().clickable { expanded = true })
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.admin_no_group)) },
                            onClick = { selectedGroup = null; expanded = false },
                        )
                        groups.forEach { group ->
                            DropdownMenuItem(
                                text = { Text(group.name) },
                                onClick = { selectedGroup = group; expanded = false },
                            )
                        }
                    }
                }
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
