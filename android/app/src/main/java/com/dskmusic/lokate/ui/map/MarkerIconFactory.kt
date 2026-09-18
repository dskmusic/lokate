package com.dskmusic.lokate.ui.map

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.absoluteValue

/** Iconos de marcador estilo Google Find My Device / Life360: círculo con foto, o inicial si no hay foto. */
object MarkerIconFactory {

    private val palette = listOf(
        0xFF4A6FE3.toInt(), 0xFF8457E8.toInt(), 0xFFE0654F.toInt(),
        0xFF2E9B8F.toInt(), 0xFFD9A441.toInt(), 0xFFC85D8E.toInt(),
    )

    fun colorFor(userId: String): Int = palette[(userId.hashCode().absoluteValue) % palette.size]

    fun initialsBitmap(name: String, userId: String, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = sizePx / 2f

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorFor(userId) }
        canvas.drawCircle(radius, radius, radius, backgroundPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.06f
        }
        canvas.drawCircle(radius, radius, radius - borderPaint.strokeWidth / 2, borderPaint)

        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sizePx * 0.45f
            textAlign = Paint.Align.CENTER
        }
        val textY = radius - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(initial, radius, textY, textPaint)

        return bitmap
    }

    fun circularAvatarBitmap(source: Bitmap, sizePx: Int): Bitmap {
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val radius = sizePx / 2f

        val scaled = Bitmap.createScaledBitmap(source, sizePx, sizePx, true)
        val shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawCircle(radius, radius, radius, paint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.06f
        }
        canvas.drawCircle(radius, radius, radius - borderPaint.strokeWidth / 2, borderPaint)

        return output
    }

    /** Punto pequeño para marcar cada posición del historial sobre la ruta dibujada. */
    fun dotBitmap(colorInt: Int, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = sizePx / 2f

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(radius, radius, radius, borderPaint)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt }
        canvas.drawCircle(radius, radius, radius * 0.65f, fillPaint)

        return bitmap
    }
}
