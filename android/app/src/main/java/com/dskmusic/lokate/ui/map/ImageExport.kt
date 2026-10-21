package com.dskmusic.lokate.ui.map

import android.os.PowerManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dskmusic.lokate.R
import kotlinx.coroutines.launch

/**
 * El "¿de qué tamaño?" y el "generando…" de cualquier foto del mapa, en un solo sitio: lo usan la
 * foto de la pantalla, la de la regla, la del "cómo llegar" y la del historial, que por dentro son
 * la misma operación con distinto pie de foto.
 *
 * Quien lo usa llama a [ask] con lo que hay que hacer una vez elegido el tamaño, y pone
 * [ImageExportDialogs] en su pantalla. Mientras hay algo en marcha [busy] está a true, que es con
 * lo que se apagan los botones para que no se encadenen dos fotos a la vez.
 */
class ImageExport {
    internal var pending by mutableStateOf<(suspend (SnapshotSize, (Int, Int) -> Unit) -> Unit)?>(null)
    internal var progress by mutableStateOf<Pair<Int, Int>?>(null)

    val busy: Boolean get() = pending != null || progress != null

    /** Pregunta el tamaño y, con el elegido, ejecuta [export] — que recibe también por dónde va
     * (capturas hechas y totales) para la barra de progreso. */
    fun ask(export: suspend (SnapshotSize, (Int, Int) -> Unit) -> Unit) {
        if (!busy) pending = export
    }
}

@Composable
fun rememberImageExport(): ImageExport = remember { ImageExport() }

/** Tope del permiso para seguir trabajando con la pantalla apagada. La foto más grande tarda
 * unos veinte segundos; tres minutos es de sobra y, si algo se queda colgado, se suelta solo en
 * vez de dejar el móvil sin dormir. */
private const val EXPORT_WAKELOCK_MS = 3 * 60 * 1000L

@Composable
fun ImageExportDialogs(export: ImageExport) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    export.pending?.let { action ->
        SnapshotSizeDialog(
            onDismiss = { export.pending = null },
            onPick = { size ->
                export.pending = null
                export.progress = 0 to size.grid * size.grid
                scope.launch {
                    // Con la pantalla apagada el móvil suspende el proceso y la foto se queda a
                    // medias hasta que alguien desbloquea. Esto mantiene la CPU despierta (no la
                    // pantalla) hasta que esté acabada: se puede minimizar y volver luego.
                    val cpu = context.getSystemService(PowerManager::class.java)
                        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lokate:export")
                    runCatching { cpu?.acquire(EXPORT_WAKELOCK_MS) }
                    // finally: si la foto revienta a mitad, el diálogo de progreso tiene que
                    // irse igual — si no, la pantalla se queda bloqueada para siempre.
                    try {
                        action(size) { hechas, total -> export.progress = hechas to total }
                    } finally {
                        export.progress = null
                        runCatching { if (cpu?.isHeld == true) cpu.release() }
                    }
                }
            },
        )
    }
    export.progress?.let { (hechas, total) -> SnapshotProgressDialog(hechas, total) }
}

/** Lo grande que se quiere la foto, antes de ponerse a hacerla: la mayor tarda veinte segundos y
 * no es lo que se quiere cada vez que se comparte una ruta de dos calles. */
@Composable
private fun SnapshotSizeDialog(onPick: (SnapshotSize) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_size_title)) },
        text = {
            Column {
                SnapshotSize.entries.forEach { size ->
                    ListItem(
                        headlineContent = { Text(stringResource(size.label)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onPick(size) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** El "generando…" mientras el mapa se pasea solo haciendo capturas. Sin botones: cancelarlo a
 * medias dejaría el mapa en cualquier sitio, y lo que queda siempre es cuestión de segundos. */
@Composable
private fun SnapshotProgressDialog(done: Int, total: Int) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.export_generating)) },
        text = {
            Column {
                Text(stringResource(R.string.export_generating_count, done, total))
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {},
    )
}
