package com.dskmusic.lokate.ui.auth

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dskmusic.lokate.R
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.ui.common.AvatarPicker
import com.dskmusic.lokate.ui.common.PasswordField

@Composable
fun RegisterScreen(locator: ServiceLocator, onRegistered: () -> Unit, onGoToLogin: () -> Unit) {
    val context = LocalContext.current
    val viewModel = remember { AuthViewModel(locator.authRepository, locator.backupRepository) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var avatarUri by remember { mutableStateOf<Uri?>(null) }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.register_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                AvatarPicker(
                    localPreviewUri = avatarUri,
                    remoteAvatarUrl = null,
                    onCropped = { avatarUri = it },
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.profile_photo_label), style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(stringResource(R.string.display_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
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
            Spacer(Modifier.height(12.dp))
            // Escrita a ciegas y sin poder recuperarla luego: si las dos no coinciden, no se crea
            // la cuenta. El aviso solo sale cuando ya hay algo escrito en la segunda.
            PasswordField(
                value = password2,
                onValueChange = { password2 = it },
                label = stringResource(R.string.password_confirm_label),
                modifier = Modifier.fillMaxWidth(),
                isError = password2.isNotEmpty() && password2 != password,
            )
            if (password2.isNotEmpty() && password2 != password) {
                Text(
                    stringResource(R.string.password_mismatch),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

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
                        viewModel.register(username.trim(), password, displayName.trim(), avatarUri, context, onRegistered)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = username.isNotBlank() && password.length >= 8 && password == password2 &&
                        displayName.isNotBlank() && avatarUri != null,
                ) {
                    Text(stringResource(R.string.register_button))
                }
            }

            TextButton(onClick = onGoToLogin, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.switch_to_login))
            }
        }
    }
}
