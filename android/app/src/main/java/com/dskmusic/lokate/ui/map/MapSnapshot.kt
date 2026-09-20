package com.dskmusic.lokate.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.osmdroid.views.MapView

/** Fondo del pie de foto: gris muy oscuro, que el blanco puro deslumbra en una foto de mapa. */
private const val CAPTION_BACKGROUND = 0xFF202124.toInt()

/**
 * Foto del mapa tal y como se ve ahora mismo (ruta, marcadores y filtro de color incluidos), con
 * un pie de texto debajo.
 *
 * Se captura al tamaño real de la vista en píxeles — en un móvil de hoy son unos 1080 × 2000, que
 * es justo el detalle que hay dibujado; ampliarlo solo dejaría un PNG más grande e igual de
 * borroso, porque las teselas ya están rasterizadas.
 *
 * Devuelve null si el mapa todavía no se ha dibujado (no tiene tamaño).
 */
fun MapView.snapshotWithCaption(caption: String): Bitmap? {
    if (width <= 0 || height <= 0) return null
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = width / 26f
    }
    // Nombre largo + fecha + distancia no siempre caben de una tirada: se encoge la letra lo justo.
    while (textPaint.textSize > 8f && textPaint.measureText(caption) > width - 24f) {
        textPaint.textSize -= 1f
    }
    val footerHeight = (textPaint.textSize * 2.2f).toInt()

    val bitmap = Bitmap.createBitmap(width, height + footerHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    draw(canvas)
    canvas.drawRect(
        0f,
        height.toFloat(),
        width.toFloat(),
        (height + footerHeight).toFloat(),
        Paint().apply { color = CAPTION_BACKGROUND },
    )
    val baseline = height + footerHeight / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(caption, width / 2f, baseline, textPaint)
    return bitmap
}
