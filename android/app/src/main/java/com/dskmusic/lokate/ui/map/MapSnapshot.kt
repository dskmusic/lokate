package com.dskmusic.lokate.ui.map

import android.graphics.Bitmap
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.dskmusic.lokate.R
import com.dskmusic.lokate.util.MediaSaver
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
suspend fun MapControl.snapshotWithCaption(caption: String): Bitmap? {
    val shot = frame(0) ?: return null
    val width = shot.width
    val height = shot.height
    val textPaint = captionPaint(width, caption)
    val footerHeight = (textPaint.textSize * 2.2f).toInt()

    val bitmap = runCatching {
        Bitmap.createBitmap(width, height + footerHeight, Bitmap.Config.ARGB_8888)
    }.getOrNull() ?: return null
    val canvas = Canvas(bitmap)
    canvas.drawBitmap(shot, 0f, 0f, null)
    shot.recycle()
    canvas.drawCaption(width, height, footerHeight, caption, textPaint)
    return bitmap
}

/** La letra del pie: proporcional al ancho de la foto, y encogida lo justo si el texto no cabe
 * de una tirada (un nombre largo + la distancia + el tiempo). */
private fun captionPaint(width: Int, caption: String): Paint {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = width / 26f
    }
    while (paint.textSize > 8f && paint.measureText(caption) > width - 24f) {
        paint.textSize -= 1f
    }
    return paint
}

private fun Canvas.drawCaption(width: Int, top: Int, footerHeight: Int, caption: String, paint: Paint) {
    drawRect(
        0f,
        top.toFloat(),
        width.toFloat(),
        (top + footerHeight).toFloat(),
        Paint().apply { color = CAPTION_BACKGROUND },
    )
    drawText(caption, width / 2f, top + footerHeight / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
}

/**
 * Lo grande que sale la foto. [grid] son las pantallas de ancho y de alto, y cada vez que se
 * dobla se sube un nivel de zoom entero — que es lo único que da detalle de verdad: con medio
 * nivel osmdroid se limita a estirar las teselas del nivel de abajo.
 */
enum class SnapshotSize(val grid: Int, val label: Int) {
    /** La pantalla tal cual, al instante. */
    SCREEN(1, R.string.export_size_screen),
    /** Cuatro capturas, ~5 s. El término medio, y lo que se ofrece por defecto. */
    DOUBLE(2, R.string.export_size_double),
    /** Dieciséis capturas, ~20 s y un PNG de varios megas. Para una ruta larga que se quiera
     * mirar de cerca después. */
    MAX(4, R.string.export_size_max),
}

/** A partir de aquí la imagen se guarda en RGB_565 (2 bytes por píxel en vez de 4). Un mapa no
 * tiene degradados finos, así que no se nota, y en [SnapshotSize.MAX] es la diferencia entre
 * caber en memoria o no. */
private const val BIG_IMAGE_PIXELS = 20_000_000L

/**
 * La misma foto pero con más zoom del que cabe en pantalla: el mapa se pasea por los cuadrantes
 * de lo que se está viendo, se captura cada uno y se pegan en una sola imagen.
 *
 * Es la única manera de ganar detalle. Las teselas ya están rasterizadas, así que agrandar el PNG
 * solo lo deja borroso; hay que pedirle al mapa las teselas del nivel siguiente, y esas no caben
 * en la pantalla. Encuadrar una ruta de 20 km deja el mapa tan lejos que no se leen ni los nombres
 * de las calles — con esto se leen.
 *
 * Tarda una espera de teselas ([tileWaitMs]) por captura y deja el mapa movido: quien llama lo
 * reencuadra. Devuelve null si no hay tantos niveles que subir (se está cerca del máximo de las
 * teselas), si [size] es la pantalla pelada o si no hay memoria para la imagen — quien llama
 * prueba entonces con un tamaño menor.
 */
suspend fun MapControl.zoomedSnapshot(
    caption: String,
    tileWaitMs: Long,
    size: SnapshotSize = SnapshotSize.DOUBLE,
    /** Capturas hechas y capturas totales, para poder enseñar un "generando...". */
    onProgress: (Int, Int) -> Unit = { _, _ -> },
): Bitmap? {
    val grid = size.grid
    val width = widthPx
    val height = heightPx
    if (grid < 2 || width <= 0 || height <= 0) return null
    // grid 2 -> un nivel, grid 4 -> dos. Son potencias de dos justamente para que cada cuadrante
    // llene una pantalla exacta y las capturas encajen sin huecos ni solapes.
    val levels = if (grid == 4) 2.0 else 1.0
    val base = zoom
    if (base + levels > maxZoom) return null

    // El paseo por cuadrantes da por hecho un mapa plano y mirando al norte: girado o
    // inclinado, cada captura cubriría un trapecio y las piezas no encajarían. Esto lo endereza
    // sin moverlo de sitio (en osmdroid no hay nada que enderezar).
    jumpTo(center, base)

    // Los centros de cada cuadrante, calculados ANTES de tocar el zoom: con el aumento puesto,
    // cada uno de ellos llena una pantalla entera, que es justo un trozo de la foto.
    val total = grid * grid
    val centers = (0 until total).map { index ->
        geoAt(
            (width * (index % grid + 0.5) / grid).toInt(),
            (height * (index / grid + 0.5) / grid).toInt(),
        ) ?: return null
    }

    val fullWidth = width * grid
    val fullHeight = height * grid
    val textPaint = captionPaint(fullWidth, caption)
    val footerHeight = (textPaint.textSize * 2.2f).toInt()
    // Dieciséis pantallas de 1080x2000 son 69 MB hasta en RGB_565: en un móvil justo de memoria
    // esto revienta, y una foto menos detallada es mejor que un cierre inesperado.
    val bitmap = runCatching {
        Bitmap.createBitmap(
            fullWidth,
            fullHeight + footerHeight,
            if (fullWidth.toLong() * fullHeight > BIG_IMAGE_PIXELS) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888,
        )
    }.getOrNull() ?: return null

    val canvas = Canvas(bitmap)
    // Cada cuadrante se captura en su propia imagen y se pega entero: así ninguna captura se
    // derrama sobre las vecinas. (Pintando los cuatro sobre el mismo lienzo, osmdroid llegaba
    // hasta donde le dejara el recorte y los rellenos transparentes —una zona, un círculo de
    // precisión— se sumaban unos encima de otros: de ahí las bandas de azules distintos.)
    centers.forEachIndexed { index, center ->
        onProgress(index, total)
        jumpTo(center, base + levels)
        val shot = frame(tileWaitMs) ?: return null
        // Cada captura va al hueco exacto de su cuadrante: MapLibre devuelve lo que pinta la GPU,
        // que no tiene por qué medir lo mismo que la vista al píxel, y un par de píxeles de más
        // correrían todas las piezas siguientes.
        val left = (index % grid) * width
        val top = (index / grid) * height
        canvas.drawBitmap(shot, null, Rect(left, top, left + width, top + height), null)
        shot.recycle()
    }
    onProgress(total, total)
    canvas.drawCaption(fullWidth, fullHeight, footerHeight, caption, textPaint)
    return bitmap
}


/** Deja el PNG en Descargas con un nombre con fecha y hora ("lokate-mapa-20261001-184500.png"),
 * o lo manda al diálogo de compartir del sistema si [share] (sin guardarlo: quien comparta lo lee
 * de la caché). En los dos casos pasa por un fichero, que es con lo que trabaja [MediaSaver]. */
suspend fun saveSnapshotPng(
    context: Context,
    bitmap: Bitmap,
    prefix: String,
    share: Boolean = false,
): Boolean {
    val name = prefix + "-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".png"
    val file = withContext(Dispatchers.IO) {
        File(context.cacheDir, name).also { out ->
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    // runCatching: si el móvil no tiene ni una app que acepte imágenes, es un fallo como otro
    // cualquiera y se avisa igual, no una excepción en medio de la pantalla.
    if (share) {
        return runCatching { MediaSaver.shareFileWhenVisible(context, file, "image/png") }.isSuccess
    }
    return MediaSaver.saveToDevice(context, file, "image/png", name, forceDownloads = true)
}

/**
 * La foto al tamaño pedido, bajando un escalón cada vez que no se pueda (cerca del zoom máximo de
 * las teselas, o sin memoria para la imagen) y con la pantalla pelada como último recurso.
 *
 * El encuadre que había se deja como estaba: por dentro el mapa se pasea para capturar cada
 * cuadrante, pero al acabar vuelve a su sitio.
 */
suspend fun MapControl.snapshotAtSize(
    caption: String,
    tileWaitMs: Long,
    size: SnapshotSize,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
): Bitmap? {
    val startCenter = center
    val startZoom = zoom
    val stitched = SnapshotSize.entries
        .filter { it.grid in 2..size.grid }
        .sortedByDescending { it.grid }
        .firstNotNullOfOrNull { zoomedSnapshot(caption, tileWaitMs, it, onProgress) }
    if (stitched != null) {
        // zoomedSnapshot solo se va del sitio cuando de verdad va a hacer la foto: si devuelve
        // null, el mapa no se ha movido y no hay nada que devolver.
        jumpTo(startCenter, startZoom)
        return stitched
    }
    return snapshotWithCaption(caption)
}

/**
 * Foto de la pantalla del mapa: el estilo elegido, los avatares, las zonas y lo que haya
 * encendido. Con [size] mayor que la pantalla se arma a base de capturas (ver [zoomedSnapshot]);
 * el encuadre no se toca en ningún caso.
 */
suspend fun saveMapImage(
    context: Context,
    map: MapControl,
    size: SnapshotSize = SnapshotSize.SCREEN,
    tileWaitMs: Long = SNAPSHOT_TILE_WAIT_MS,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
): Boolean {
    val caption = context.getString(R.string.app_name) + " · " +
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
    val bitmap = map.snapshotAtSize(caption, tileWaitMs, size, onProgress) ?: return false
    return saveSnapshotPng(context, bitmap, "lokate-mapa")
}
