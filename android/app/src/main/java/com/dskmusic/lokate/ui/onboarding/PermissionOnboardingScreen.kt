package com.dskmusic.lokate.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dskmusic.lokate.R
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.location.LocationServiceController
import com.dskmusic.lokate.location.LocationUpdateWorker
import com.dskmusic.lokate.util.ConfigCheck
import com.dskmusic.lokate.util.ManufacturerBatteryUtils
import com.dskmusic.lokate.util.PermissionUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private enum class OnboardingStep { FOREGROUND_LOCATION, BACKGROUND_LOCATION, ACTIVITY, NOTIFICATIONS, DND, BATTERY, MANUFACTURER, DONE }

/** Qué pantalla del onboarding arregla cada código de [ConfigCheck]. Null = ninguna (no es un
 * permiso que se pueda pedir desde aquí). */
private fun onboardingStepFor(issue: String): OnboardingStep? = when (issue) {
    ConfigCheck.LOCATION -> OnboardingStep.FOREGROUND_LOCATION
    ConfigCheck.BACKGROUND_LOCATION -> OnboardingStep.BACKGROUND_LOCATION
    ConfigCheck.ACTIVITY -> OnboardingStep.ACTIVITY
    ConfigCheck.NOTIFICATIONS -> OnboardingStep.NOTIFICATIONS
    ConfigCheck.DND -> OnboardingStep.DND
    ConfigCheck.BATTERY -> OnboardingStep.BATTERY
    else -> null
}

@Composable
fun PermissionOnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val locator = remember { ServiceLocator.getInstance(context) }
    val scope = rememberCoroutineScope()

    // Lo que ya se le preguntó alguna vez. Se lee de una y no como estado: si cambiase a mitad
    // del onboarding, reiniciaría el paso en el que está el usuario.
    val askedIssues = remember { runBlocking { locator.settings.onboardingAskedIssues.first() } }
    // Arranca en el primer paso que de verdad falte, no siempre en el primero de todos: tras
    // actualizar la app, quien ya lo tenía todo concedido ve solo la pantalla del permiso nuevo
    // en vez de tener que pasar por las cinco.
    var step by remember {
        mutableStateOf(
            ConfigCheck.pendingOnboarding(context, askedIssues)
                .mapNotNull { onboardingStepFor(it) }
                .minByOrNull { it.ordinal }
                ?: OnboardingStep.FOREGROUND_LOCATION,
        )
    }
    // Si el usuario deniega un permiso, no le forzamos a repetir: mostramos el aviso y le dejamos
    // reintentar o continuar sin él (el prompt pide avisar, no bloquear el uso básico).
    var lastPermissionDenied by remember { mutableStateOf(false) }

    fun goToBatteryOrDone() {
        step = if (PermissionUtils.isIgnoringBatteryOptimizations(context) &&
            !ManufacturerBatteryUtils.hasKnownAggressiveBatteryManagement()
        ) {
            OnboardingStep.DONE
        } else if (PermissionUtils.isIgnoringBatteryOptimizations(context)) {
            OnboardingStep.MANUFACTURER
        } else {
            OnboardingStep.BATTERY
        }
    }

    val foregroundLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        lastPermissionDenied = !granted
        if (granted) step = OnboardingStep.BACKGROUND_LOCATION
    }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        lastPermissionDenied = !granted
        if (granted) step = OnboardingStep.ACTIVITY
    }
    val activityLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        lastPermissionDenied = !granted
        if (granted) step = OnboardingStep.NOTIFICATIONS
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        lastPermissionDenied = !granted
        if (granted) step = OnboardingStep.DND
    }
    // El panel de "Acceso a No molestar" no devuelve resultado: al volver se comprueba solo.
    val dndLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        goToBatteryOrDone()
    }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        step = if (ManufacturerBatteryUtils.hasKnownAggressiveBatteryManagement()) OnboardingStep.MANUFACTURER else OnboardingStep.DONE
    }

    fun finish() {
        scope.launch {
            locator.settings.setOnboardingCompleted(true)
            // Lo que SIGA faltando al salir queda marcado como preguntado: quien lo saltó a
            // propósito no se lo vuelve a encontrar en cada apertura. Lo concedido no se apunta,
            // así que si algún día lo revoca se le vuelve a ofrecer.
            locator.settings.setOnboardingAskedIssues(
                (ConfigCheck.parse(askedIssues).orEmpty() + ConfigCheck.onboardableIssues(context))
                    .distinct()
                    .joinToString(","),
            )
            if (locator.authRepository.isLoggedIn()) {
                LocationServiceController.ensureStarted(context)
                LocationUpdateWorker.schedule(context)
            }
            onFinished()
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            when (step) {
                OnboardingStep.FOREGROUND_LOCATION -> OnboardingStepContent(
                    title = stringResource(R.string.onboarding_location_title),
                    description = stringResource(R.string.onboarding_location_desc),
                    denied = lastPermissionDenied,
                    onContinue = {
                        lastPermissionDenied = false
                        if (PermissionUtils.hasForegroundLocationPermission(context)) {
                            step = OnboardingStep.BACKGROUND_LOCATION
                        } else {
                            foregroundLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    },
                    onSkip = { lastPermissionDenied = false; step = OnboardingStep.BACKGROUND_LOCATION },
                )
                OnboardingStep.BACKGROUND_LOCATION -> OnboardingStepContent(
                    title = stringResource(R.string.onboarding_background_location_title),
                    description = stringResource(R.string.onboarding_background_location_desc),
                    denied = lastPermissionDenied,
                    onContinue = {
                        lastPermissionDenied = false
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || PermissionUtils.hasBackgroundLocationPermission(context)) {
                            step = OnboardingStep.ACTIVITY
                        } else {
                            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    },
                    onSkip = { lastPermissionDenied = false; step = OnboardingStep.ACTIVITY },
                )
                OnboardingStep.ACTIVITY -> OnboardingStepContent(
                    title = stringResource(R.string.onboarding_activity_title),
                    description = stringResource(R.string.onboarding_activity_desc),
                    denied = lastPermissionDenied,
                    onContinue = {
                        lastPermissionDenied = false
                        if (PermissionUtils.hasActivityRecognitionPermission(context)) {
                            step = OnboardingStep.NOTIFICATIONS
                        } else {
                            activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        }
                    },
                    onSkip = { lastPermissionDenied = false; step = OnboardingStep.NOTIFICATIONS },
                )
                OnboardingStep.NOTIFICATIONS -> OnboardingStepContent(
                    title = stringResource(R.string.onboarding_notifications_title),
                    description = stringResource(R.string.onboarding_notifications_desc),
                    denied = lastPermissionDenied,
                    onContinue = {
                        lastPermissionDenied = false
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || PermissionUtils.hasNotificationPermission(context)) {
                            step = OnboardingStep.DND
                        } else {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onSkip = { lastPermissionDenied = false; step = OnboardingStep.DND },
                )
                OnboardingStep.DND -> OnboardingStepContent(
                    title = stringResource(R.string.onboarding_dnd_title),
                    description = stringResource(R.string.onboarding_dnd_desc),
                    onContinue = {
                        if (PermissionUtils.hasDndAccess(context)) {
                            goToBatteryOrDone()
                        } else {
                            dndLauncher.launch(PermissionUtils.dndAccessSettingsIntent())
                        }
                    },
                    extraAction = {
                        TextButton(onClick = { goToBatteryOrDone() }) {
                            Text(stringResource(R.string.onboarding_skip_step))
                        }
                    },
                )
                OnboardingStep.BATTERY -> OnboardingStepContent(
                    title = stringResource(R.string.onboarding_battery_title),
                    description = stringResource(R.string.onboarding_battery_desc),
                    onContinue = {
                        if (PermissionUtils.isIgnoringBatteryOptimizations(context)) {
                            step = if (ManufacturerBatteryUtils.hasKnownAggressiveBatteryManagement()) OnboardingStep.MANUFACTURER else OnboardingStep.DONE
                        } else {
                            batteryLauncher.launch(PermissionUtils.batteryOptimizationIntent(context))
                        }
                    },
                )
                OnboardingStep.MANUFACTURER -> OnboardingStepContent(
                    title = stringResource(R.string.onboarding_manufacturer_title),
                    description = stringResource(
                        R.string.onboarding_manufacturer_desc,
                        ManufacturerBatteryUtils.manufacturerDisplayName(),
                    ),
                    onContinue = {
                        // Estos paneles de fabricante no devuelven un resultado fiable: los abrimos
                        // y dejamos que el usuario vuelva sola/o cuando termine, avanzando al pulsar de nuevo.
                        ManufacturerBatteryUtils.tryOpenAutoStartSettings(context)
                        step = OnboardingStep.DONE
                    },
                    extraAction = {
                        TextButton(onClick = { step = OnboardingStep.DONE }) {
                            Text(stringResource(R.string.onboarding_skip_step))
                        }
                    },
                )
                OnboardingStep.DONE -> {
                    Text(stringResource(R.string.onboarding_finish), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { finish() }) {
                        Text(stringResource(R.string.onboarding_finish))
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingStepContent(
    title: String,
    description: String,
    onContinue: () -> Unit,
    denied: Boolean = false,
    onSkip: (() -> Unit)? = null,
    extraAction: (@Composable () -> Unit)? = null,
) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))
    Text(description, style = MaterialTheme.typography.bodyLarge)

    if (denied) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.onboarding_skip_warning), color = MaterialTheme.colorScheme.error)
    }

    Spacer(Modifier.height(24.dp))
    Button(onClick = onContinue) {
        Text(stringResource(R.string.onboarding_continue))
    }
    if (denied) {
        val context = LocalContext.current
        TextButton(onClick = { context.startActivity(PermissionUtils.appSettingsIntent(context)) }) {
            Text(stringResource(R.string.onboarding_open_settings))
        }
        if (onSkip != null) {
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.onboarding_skip_step))
            }
        }
    }
    extraAction?.invoke()
}
