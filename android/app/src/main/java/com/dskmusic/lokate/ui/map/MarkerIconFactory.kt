package com.dskmusic.lokate.ui.map

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.absoluteValue
import kotlin.math.min

/** Iconos de marcador estilo Google Find My Device / Life360: círculo con foto, o inicial si no
 * hay foto, con un anillo alrededor que indica la batería de ese dispositivo. */
object MarkerIconFactory {

    private val palette = listOf(
        0xFF4A6FE3.toInt(), 0xFF8457E8.toInt(), 0xFFE0654F.toInt(),
        0xFF2E9B8F.toInt(), 0xFFD9A441.toInt(), 0xFFC85D8E.toInt(),
    )

    /** Banda que el anillo de batería añade FUERA del círculo, en proporción a su diámetro: el
     * bitmap crece por los cuatro lados en vez de comerse parte de la foto. */
    private const val RING_BAND = 0.18f

    /** Grosor del arco de batería, bastante menor que la banda para que quede aire visible
     * entre el arco y el borde blanco de la foto. */
    private const val RING_STROKE = 0.08f

    /** Borde blanco del círculo, por fuera de la foto (la foto se encoge para caber dentro). */
    private const val BORDER = 0.06f

    /** Color de la pista del anillo mientras el móvil está cargando: el arco de nivel mantiene
     * su color (rojo si va justo de batería), así que hace falta otro sitio donde decirlo. */
    private const val CHARGING_TRACK = 0xE04CAF50

    fun colorFor(userId: String): Int = palette[(userId.hashCode().absoluteValue) % palette.size]

    fun initialsBitmap(
        name: String,
        userId: String,
        sizePx: Int,
        batteryLevel: Int? = null,
        charging: Boolean = false,
    ): Bitmap {
        val band = bandPx(sizePx, batteryLevel)
        val total = sizePx + band * 2
        val bitmap = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = total / 2f
        val radius = sizePx / 2f
        val border = sizePx * BORDER

        // Borde blanco = círculo blanco de fondo; encima el color, ya por dentro del borde.
        canvas.drawCircle(center, center, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorFor(userId) }
        canvas.drawCircle(center, center, radius - border, backgroundPaint)
        drawBatteryRing(canvas, total, sizePx, batteryLevel, charging)

        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sizePx * 0.45f
            textAlign = Paint.Align.CENTER
        }
        val textY = center - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(initial, center, textY, textPaint)

        return bitmap
    }

    fun circularAvatarBitmap(
        source: Bitmap,
        sizePx: Int,
        batteryLevel: Int? = null,
        charging: Boolean = false,
    ): Bitmap {
        val band = bandPx(sizePx, batteryLevel)
        val total = sizePx + band * 2
        val output = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val center = total / 2f
        val radius = sizePx / 2f
        val border = sizePx * BORDER

        canvas.drawCircle(center, center, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })

        // La foto cabe entera dentro del borde blanco (nada la recorta por arriba) y se recorta
        // cuadrada por el centro antes de escalar, para que no salga estirada si no era cuadrada.
        val photoPx = (sizePx - border * 2).toInt()
        val side = min(source.width, source.height)
        val square = Bitmap.createBitmap(
            source, (source.width - side) / 2, (source.height - side) / 2, side, side,
        )
        val scaled = Bitmap.createScaledBitmap(square, photoPx, photoPx, true)
        val offset = center - photoPx / 2f
        val shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            // El shader pinta desde (0,0): hay que desplazarlo hasta donde empieza la foto.
            setLocalMatrix(Matrix().apply { setTranslate(offset, offset) })
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawCircle(center, center, photoPx / 2f, paint)
        drawBatteryRing(canvas, total, sizePx, batteryLevel, charging)

        return output
    }

    private fun bandPx(sizePx: Int, batteryLevel: Int?): Int =
        if (batteryLevel == null) 0 else (sizePx * RING_BAND).toInt()

    /**
     * Anillo de batería por fuera del círculo: pista blanca translúcida entera más un arco de
     * color proporcional al nivel (rojo ≤ 15%, ámbar ≤ 35%, verde el resto), empezando arriba y
     * avanzando en el sentido del reloj como un indicador de carga. Cargando, la pista deja de
     * ser blanca y se pone verde: el anillo entero se ve encendido desde lejos.
     */
    private fun drawBatteryRing(
        canvas: Canvas,
        totalPx: Int,
        sizePx: Int,
        batteryLevel: Int?,
        charging: Boolean = false,
    ) {
        if (batteryLevel == null) return
        val level = batteryLevel.coerceIn(0, 100)
        val stroke = sizePx * RING_STROKE
        val center = totalPx / 2f
        // Un pelín metido hacia dentro: pegado al filo, el remate redondo del arco se cortaba.
        val radius = center - stroke / 2 - sizePx * 0.01f

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (charging) CHARGING_TRACK.toInt() else Color.argb(140, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        canvas.drawCircle(center, center, radius, trackPaint)

        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when {
                level <= 15 -> 0xFFE53935.toInt()
                level <= 35 -> 0xFFFFA000.toInt()
                else -> 0xFF43A047.toInt()
            }
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        val inset = center - radius
        canvas.drawArc(
            inset, inset, totalPx - inset, totalPx - inset,
            -90f, 360f * level / 100f, false, arcPaint,
        )
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
