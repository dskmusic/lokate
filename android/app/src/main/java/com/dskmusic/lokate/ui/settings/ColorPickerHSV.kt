package com.dskmusic.lokate.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rueda de matiz/saturación (ángulo = tono, radio = saturación) + slider de brillo, sin
 * depender de ninguna librería externa: es la forma más simple de cubrir el requisito de
 * "selector HSV/HSL con rueda de color + slider de brillo" con Canvas + gestos nativos.
 */
@Composable
fun ColorPickerHSV(
    initialColor: Color,
    onColorChanged: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hsv = remember {
        val arr = FloatArray(3)
        android.graphics.Color.colorToHSV(initialColor.toArgb(), arr)
        arr
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var brightness by remember { mutableFloatStateOf(hsv[2]) }

    fun emit() {
        onColorChanged(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))))
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(8.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset -> updateFromOffset(size, offset) { h, s -> hue = h; saturation = s; emit() } }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        updateFromOffset(size, change.position) { h, s -> hue = h; saturation = s; emit() }
                    }
                },
        ) {
            HueSaturationWheel(hue = hue, saturation = saturation)
        }

        Slider(
            value = brightness,
            onValueChange = { brightness = it; emit() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

private fun updateFromOffset(size: androidx.compose.ui.unit.IntSize, offset: Offset, onResult: (Float, Float) -> Unit) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = min(size.width, size.height) / 2f
    val dx = offset.x - center.x
    val dy = offset.y - center.y
    val distance = sqrt(dx * dx + dy * dy).coerceAtMost(radius)
    val angle = (Math.toDegrees(atan2(dy, dx).toDouble()) + 360.0) % 360.0
    onResult(angle.toFloat(), (distance / radius).coerceIn(0f, 1f))
}

@Composable
private fun HueSaturationWheel(hue: Float, saturation: Float) {
    Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
        val radius = min(size.width, size.height) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        val hueColors = (0..360 step 15).map { deg ->
            Color(android.graphics.Color.HSVToColor(floatArrayOf(deg.toFloat(), 1f, 1f)))
        }
        drawCircle(
            brush = Brush.sweepGradient(hueColors, center = center),
            radius = radius,
            center = center,
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White, Color.Transparent),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )

        // Indicador de la posición seleccionada
        val angleRad = Math.toRadians(hue.toDouble())
        val markerRadius = saturation * radius
        val markerCenter = Offset(
            center.x + (cos(angleRad) * markerRadius).toFloat(),
            center.y + (sin(angleRad) * markerRadius).toFloat(),
        )
        drawCircle(color = Color.White, radius = 10.dp.toPx(), center = markerCenter, style = Stroke(width = 3.dp.toPx()))
        drawCircle(color = Color.Black, radius = 10.dp.toPx(), center = markerCenter, style = Stroke(width = 1.dp.toPx()))
    }
}
