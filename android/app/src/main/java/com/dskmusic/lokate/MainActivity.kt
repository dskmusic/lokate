package com.dskmusic.lokate

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.app.NotificationManagerCompat
import com.dskmusic.lokate.push.NotificationHelper
import com.dskmusic.lokate.push.RING_NOTIFICATION_ID
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.location.LocationServiceController
import com.dskmusic.lokate.location.LocationUpdateWorker
import com.dskmusic.lokate.ui.navigation.LokateNavHost
import com.dskmusic.lokate.ui.navigation.Routes
import com.dskmusic.lokate.ui.theme.LokateTheme
import com.dskmusic.lokate.util.LocaleHelper
import com.dskmusic.lokate.util.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val locator = ServiceLocator.getInstance(newBase.applicationContext)
        val language = runBlocking { locator.settings.appLanguage.first() }
        super.attachBaseContext(LocaleHelper.wrap(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val locator = ServiceLocator.getInstance(applicationContext)

        var startDestination = runBlocking {
            when {
                !locator.settings.onboardingCompleted.first() -> Routes.ONBOARDING
                !locator.authRepository.isLoggedIn() -> Routes.LOGIN
                else -> Routes.MAP
            }
        }

        // Se llega aquí al tocar la notificación de un mensaje de emergencia: para el sonido/
        // vibración y abre directamente el visor en vez del mapa.
        val emergencySender = intent.getStringExtra(Constants.EXTRA_EMERGENCY_SENDER)
        if (emergencySender != null && locator.authRepository.isLoggedIn()) {
            NotificationHelper.stopRingAlarm(applicationContext)
            NotificationManagerCompat.from(this)
                .cancel(intent.getIntExtra(Constants.EXTRA_NOTIFICATION_ID, RING_NOTIFICATION_ID))
            startDestination = Routes.emergencyMessage(
                sender = emergencySender,
                text = intent.getStringExtra(Constants.EXTRA_EMERGENCY_TEXT).orEmpty(),
                attachmentUrl = intent.getStringExtra(Constants.EXTRA_EMERGENCY_ATTACHMENT_URL),
                attachmentKind = intent.getStringExtra(Constants.EXTRA_EMERGENCY_ATTACHMENT_KIND),
            )
        }

        // Cubre a quien ya tenía sesión iniciada antes de este arreglo: sin esto, ni su token FCM
        // se había mandado al backend, ni el servicio de ubicación llegó a arrancar nunca
        // (antes solo arrancaba desde el onboarding, que ocurre ANTES de tener cuenta).
        if (locator.authRepository.isLoggedIn()) {
            LocationServiceController.ensureStarted(applicationContext)
            LocationUpdateWorker.schedule(applicationContext)
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { locator.authRepository.registerCurrentDeviceToken() }
            }
        }

        setContent {
            val themeMode by locator.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val accentArgb by locator.settings.accentColor.collectAsState(
                initial = com.dskmusic.lokate.data.prefs.SettingsDataStore.DEFAULT_ACCENT,
            )

            LokateTheme(themeMode = themeMode, accentColor = Color(accentArgb)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LokateNavHost(locator = locator, startDestination = startDestination)
                }
            }
        }
    }

    // launchMode="singleTop": tocar otra notificación con la app ya abierta entrega el intent
    // aquí en vez de por onCreate. Recrear la actividad es la forma simple de que onCreate
    // vuelva a mirar los extras nuevos (parar el sonido, abrir el visor de emergencia, etc.).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
}
