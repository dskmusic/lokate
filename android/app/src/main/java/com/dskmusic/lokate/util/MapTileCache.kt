package com.dskmusic.lokate.util

import android.content.Context
import java.io.File

/** La carpeta de teselas del mapa la fija LokateApplication vía osmdroid Configuration
 * (osmdroidTileCache) — este objeto solo sabe dónde está para poder medirla/vaciarla desde
 * Ajustes, sin depender de tener un MapView activo en pantalla. */
object MapTileCache {

    fun directory(context: Context): File =
        File(context.getExternalFilesDir(null), "tiles")

    fun sizeBytes(context: Context): Long {
        val dir = directory(context)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun clear(context: Context) {
        directory(context).deleteRecursively()
    }
}
