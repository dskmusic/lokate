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
import com.dskmusic.lokate.util.ConfigCheck
import com.dskmusic.lokate.util.Constants
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.location.LocationServiceController
import com.dskmusic.lokate.location.LocationUpdateWorker
import com.dskmusic.lokate.ui.map.AppForeground
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
                // Onboarding ya hecho, pero falta algo que nunca se le llegó a pedir: pasa al
                // actualizar la app a una versión con un permiso nuevo. Sin esto, la única
                // forma de que lo pidiera era borrar los datos de la app.
                ConfigCheck.pendingOnboarding(
                    applicationContext,
                    locator.settings.onboardingAskedIssues.first(),
                ).isNotEmpty() -> Routes.ONBOARDING
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

        // Toque en una notificación de zona ("X ha llegado a Y"): se abre la ficha de esa
        // persona. Solo desde el mapa: si toca onboarding o login, primero lo suyo.
        val memberFromPush = intent.getStringExtra(Constants.EXTRA_PUSH_USER_ID)
            ?.takeIf { it.isNotBlank() && startDestination == Routes.MAP }
        // Se consume al leerlo: si la actividad se recrea por otra cosa (cambiar el idioma en
        // Ajustes, por ejemplo), el intent sigue siendo el mismo y volvería a saltar la ficha.
        intent.removeExtra(Constants.EXTRA_PUSH_USER_ID)

        // Acceso directo del icono de la app: se abre el mapa y encima la sección elegida, para
        // que "atrás" lleve al mapa en vez de cerrar la app. Se consume igual que el extra de
        // arriba, si no una rotación volvería a abrir la sección.
        val sectionFromShortcut = when (intent.action) {
            Constants.ACTION_OPEN_PEOPLE -> Routes.PEOPLE
            Constants.ACTION_OPEN_ZONES -> Routes.ZONES
            Constants.ACTION_OPEN_HISTORY -> Routes.history()
            else -> null
        }?.takeIf { startDestination == Routes.MAP }
        intent.action = Intent.ACTION_MAIN

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
                    LokateNavHost(
                        locator = locator,
                        startDestination = startDestination,
                        openMemberUserId = memberFromPush,
                        openSection = sectionFromShortcut,
                    )
                }
            }
        }
    }

    // Lo que mira el resto de la app para saber si hay alguien delante. onStart/onStop y no
    // onResume/onPause: un diálogo del sistema por encima no es irse de la app.
    override fun onStart() {
        super.onStart()
        AppForeground.visible.value = true
    }

    override fun onStop() {
        super.onStop()
        // Girar el móvil, cambiar el tema o el idioma también pasa por aquí, y eso no es irse de
        // la app: sin esta condición, cambiar de tema soltaría el seguimiento en vivo en marcha.
        if (!isChangingConfigurations) AppForeground.visible.value = false
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
