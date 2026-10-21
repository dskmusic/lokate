package com.dskmusic.lokate.ui.common

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView

/**
 * Abre el selector de fotos del sistema y, con la elegida, la pantalla de recorte: ventana
 * circular fija en 1:1, dos dedos para ampliar y arrastrar para encuadrar. Devuelve la función
 * que arranca el proceso.
 *
 * Vive aquí suelto y no dentro de [AvatarPicker] porque la parte de administración pone el avatar
 * de otros usuarios desde una lista (sin este círculo tocable) y tiene que recortar igual: un solo
 * sitio con las opciones, o las dos pantallas acaban recortando distinto.
 */
@Composable
fun rememberAvatarPicker(onCropped: (Uri) -> Unit): () -> Unit {
    val cropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            result.uriContent?.let(onCropped)
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            cropLauncher.launch(CropImageContractOptions(uri = uri, cropImageOptions = avatarCropOptions()))
        }
    }

    return { pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

private fun avatarCropOptions() = CropImageOptions().apply {
    fixAspectRatio = true
    aspectRatioX = 1
    aspectRatioY = 1
    // Óvalo + proporción fija = el círculo de siempre, el mismo que se verá luego en el mapa.
    cropShape = CropImageView.CropShape.OVAL
    // Lo que pedía esto: pellizcar con dos dedos para encuadrar sin tener que pescar las esquinas
    // del recorte. Viene apagado de serie. Con autoZoomEnabled (encendido de serie) el efecto es
    // el de ampliar la foto: al cerrar el círculo, la vista hace zoom a lo que queda dentro.
    multiTouchEnabled = true
    // El círculo arranca tocando los bordes de la foto, como en cualquier selector de avatar, en
    // vez de pequeñito en medio.
    initialCropWindowPaddingRatio = 0f
    outputCompressFormat = android.graphics.Bitmap.CompressFormat.JPEG
    outputCompressQuality = 90
    // Sin esto, los iconos de la barra (aceptar/cancelar/rotar) salen en blanco
    // sobre el fondo blanco del theme de la app y quedan invisibles.
    toolbarColor = android.graphics.Color.BLACK
    toolbarTitleColor = android.graphics.Color.WHITE
    toolbarBackButtonColor = android.graphics.Color.WHITE
    toolbarTintColor = android.graphics.Color.WHITE
    activityMenuIconColor = android.graphics.Color.WHITE
    activityMenuTextColor = android.graphics.Color.WHITE
}

/** Avatar circular tocable: al tocarlo arranca el elegir + recortar de [rememberAvatarPicker]. */
@Composable
fun AvatarPicker(
    localPreviewUri: Uri?,
    remoteAvatarUrl: String?,
    size: androidx.compose.ui.unit.Dp = 96.dp,
    onCropped: (Uri) -> Unit,
) {
    val pickAndCrop = rememberAvatarPicker(onCropped)

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { pickAndCrop() },
        contentAlignment = Alignment.Center,
    ) {
        val model = localPreviewUri ?: remoteAvatarUrl
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, modifier = Modifier.size(size).clip(CircleShape))
        } else {
            Icon(Icons.Filled.AddAPhoto, contentDescription = null, tint = Color.Gray)
        }
    }
}
