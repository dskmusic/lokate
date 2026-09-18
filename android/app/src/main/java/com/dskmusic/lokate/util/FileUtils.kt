package com.dskmusic.lokate.util

import android.content.Context
import android.net.Uri
import java.io.File

object FileUtils {
    /** El cropper devuelve un content:// Uri; Retrofit/OkHttp necesitan un File real para el multipart. */
    fun uriToCacheFile(context: Context, uri: Uri, fileName: String = "avatar_upload.jpg"): File {
        val outFile = File(context.cacheDir, fileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outFile
    }
}
