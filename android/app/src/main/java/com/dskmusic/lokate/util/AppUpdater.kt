package com.dskmusic.lokate.util

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.dskmusic.lokate.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/** Descarga el APK publicado en el servidor y abre el instalador del sistema con él. */
object AppUpdater {

    /** Se ha lanzado el instalador y todavía no se ha vuelto a la app. Sobrevive a que Android
     * mate el proceso al instalar, que es justo lo que pasa cuando la cosa sale bien. */
    private const val K_PENDING_CONFIRM = "update_pending_confirm"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    fun canRequestInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    suspend fun downloadAndInstall(context: Context, url: String) {
        val out = withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "lokate_update.apk")
            URL(url).openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            file
        }
        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Se avisa al admin ANTES de abrir el instalador: a partir de aquí el sistema puede
        // matar el proceso en cualquier momento (instalar la app encima hace justo eso).
        val locator = ServiceLocator.getInstance(context.applicationContext)
        if (locator.authRepository.isLoggedIn()) {
            prefs(context).edit().putBoolean(K_PENDING_CONFIRM, true).apply()
            locator.authRepository.reportUpdateNoticeStatus("started")
        }
        context.startActivity(intent)
    }

    /**
     * Segundo aviso al admin: la app ha vuelto a arrancar después de haber lanzado el
     * instalador. Lo llama [com.dskmusic.lokate.LokateApplication] en cada arranque y no hace
     * nada si no había nada pendiente.
     *
     * ponytail: esto NO pregunta al sistema si la instalación salió bien (no hay API para eso
     * con el instalador clásico), así que también llega si el usuario canceló el instalador. Por
     * eso el aviso lleva la versión: es la prueba de en cuál se ha quedado.
     */
    fun confirmPendingInstall(context: Context) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(K_PENDING_CONFIRM, false)) return
        prefs.edit().remove(K_PENDING_CONFIRM).apply()
        val locator = ServiceLocator.getInstance(context.applicationContext)
        if (!locator.authRepository.isLoggedIn()) return
        CoroutineScope(Dispatchers.IO).launch {
            locator.authRepository.reportUpdateNoticeStatus("installed")
        }
    }
}
