package com.dskmusic.lokate.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.ServerConfig
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.ui.common.PasswordField
import com.dskmusic.lokate.util.formatTimestamp
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(locator: ServiceLocator, onLoggedIn: () -> Unit, onGoToRegister: () -> Unit) {
    val viewModel = remember { AuthViewModel(locator.authRepository, locator.backupRepository) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // La app puede venir compilada sin servidor (el código no lleva ninguna URL dentro): entonces
    // lo primero es preguntarlo, porque sin eso no hay a dónde mandar el usuario y la contraseña.
    // Se guarda igual que el de Ajustes, así que solo se pregunta una vez.
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf("") }
    var needsServer by remember { mutableStateOf(!ServerConfig.isSet) }
    fun applyServer() {
        if (!needsServer) return
        ServerConfig.update(serverUrl)
        scope.launch { locator.settings.setServerBaseUrlOverride(serverUrl) }
        needsServer = !ServerConfig.isSet
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.login_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(24.dp))

            if (needsServer) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text(stringResource(R.string.settings_server_url)) },
                    placeholder = { Text(stringResource(R.string.settings_server_url_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.username_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            PasswordField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.password_label),
                modifier = Modifier.fillMaxWidth(),
            )

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                Button(
                    onClick = {
                        applyServer()
                        viewModel.login(username.trim(), password, onLoggedIn)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = username.isNotBlank() && password.isNotBlank() &&
                        (!needsServer || serverUrl.isNotBlank()),
                ) {
                    Text(stringResource(R.string.login_button))
                }
            }

            // Crear cuenta también necesita servidor: se guarda antes de salir de esta pantalla.
            TextButton(
                onClick = {
                    applyServer()
                    onGoToRegister()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !needsServer || serverUrl.isNotBlank(),
            ) {
                Text(stringResource(R.string.switch_to_register))
            }
        }
    }

    // Hay copia de sus ajustes arriba: se ofrece antes de entrar, que es cuando importa (móvil
    // nuevo o app recién reinstalada). Decir que no entra con los ajustes de fábrica y no borra
    // la copia: la siguiente copia automática la pisará cuando toque.
    state.backupDate?.let { date ->
        AlertDialog(
            onDismissRequest = { viewModel.enterWithoutRestoring() },
            title = { Text(stringResource(R.string.login_backup_title)) },
            text = { Text(stringResource(R.string.login_backup_body, formatTimestamp(date))) },
            confirmButton = {
                TextButton(onClick = { viewModel.restoreBackupAndEnter() }) {
                    Text(stringResource(R.string.login_backup_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.enterWithoutRestoring() }) {
                    Text(stringResource(R.string.login_backup_skip))
                }
            },
        )
    }
}
