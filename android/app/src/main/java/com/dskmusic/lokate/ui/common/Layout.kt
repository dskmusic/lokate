package com.dskmusic.lokate.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Lo que hace falta para que la app aguante el móvil tumbado, que son dos preguntas y nada más.
 *
 * Comprobación: girar el móvil en el mapa, en un cuadro con lista larga (los pasos de la ruta) y
 * en el editor de zonas; nada se sale por abajo y el mapa sigue siendo lo más grande.
 */

/** Si el móvil está tumbado. */
@Composable
fun apaisado(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * El alto que se pide, recortado a lo que de verdad cabe. Tumbado, la pantalla mide la mitad de
 * alto: una lista de 460 dp o una foto de 360 dp se salen por abajo y se llevan por delante los
 * botones del cuadro, que es justo lo que no se puede perder.
 */
@Composable
fun cabe(alto: Dp, fraccion: Float = 0.55f): Dp =
    minOf(alto, LocalConfiguration.current.screenHeightDp.dp * fraccion)

/**
 * Tumbado, fuera las barras del sistema. Entre la de estado y la de navegación se llevan unos
 * 70 dp, que de pie no se notan y en horizontal son la quinta parte de la pantalla; y lo que se
 * mira tumbado es el mapa. Vuelven solas deslizando desde el borde (y al girar a vertical).
 */
@Composable
fun PantallaCompletaTumbado() {
    val tumbado = apaisado()
    val vista = LocalView.current
    if (vista.isInEditMode) return
    val ventana = vista.context.actividad()?.window ?: return
    DisposableEffect(tumbado) {
        val barras = WindowCompat.getInsetsController(ventana, vista)
        // Transitorias: un deslizamiento desde el borde las enseña un momento y se vuelven a
        // esconder solas, sin mover nada de sitio.
        barras.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (tumbado) barras.hide(WindowInsetsCompat.Type.systemBars())
        else barras.show(WindowInsetsCompat.Type.systemBars())
        // Nadie quiere salir de la app y encontrarse el móvil sin barra de estado.
        onDispose { barras.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** La Activity de un Context, saltando los envoltorios de tema que mete Compose por el camino. */
private tailrec fun Context.actividad(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.actividad()
    else -> null
}

/**
 * Cómo se mide un cuadro de diálogo tumbado: ancho de sobra en lugar de estrecho y altísimo.
 *
 * Material le pone a todos los cuadros el mismo ancho, pensado para un móvil de pie, donde es
 * casi toda la pantalla. Tumbado ese mismo ancho es un tercio de la pantalla y el contenido no
 * tiene más salida que crecer hacia abajo, que es justo lo que no hay. Ensanchando, el texto
 * ocupa la mitad de líneas y lo de dentro se puede repartir en dos columnas.
 */
@Composable
fun Modifier.cuadroAncho(): Modifier =
    if (apaisado()) this.widthIn(max = 720.dp).fillMaxWidth(0.9f) else this

/** Hace falta junto a [cuadroAncho]: con el ancho de serie puesto, Material no deja pasar de los
 * 560 dp suyos y el modifier no sirve de nada. */
@Composable
fun propiedadesCuadro(): DialogProperties = DialogProperties(usePlatformDefaultWidth = !apaisado())
