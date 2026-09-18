package com.dskmusic.lokate.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/** Descarga y guarda/comparte los adjuntos de un mensaje de emergencia (foto, vídeo u otro archivo). */
object MediaSaver {

    suspend fun downloadToCache(context: Context, url: String, fileName: String): File = withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, fileName)
        URL(url).openStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        out
    }

    /** Guarda en la galería/descargas del dispositivo. true si se guardó correctamente.
     * [forceDownloads]: ignora el tipo y guarda siempre en Descargas (mensajes de emergencia). */
    suspend fun saveToDevice(
        context: Context,
        file: File,
        mimeType: String,
        displayName: String,
        forceDownloads: Boolean = false,
    ): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val useLegacyDownloadsDir = forceDownloads ||
                    (!mimeType.startsWith("image/") && !mimeType.startsWith("video/"))
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && useLegacyDownloadsDir) {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    file.copyTo(File(downloadsDir, displayName), overwrite = true)
                    return@runCatching true
                }
                val collection = when {
                    forceDownloads -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(collection, values) ?: return@runCatching false
                context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
                true
            }.getOrDefault(false)
        }

    fun shareFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(sendIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
