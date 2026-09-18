package com.dskmusic.lokate.ui.common

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView

/**
 * Avatar circular tocable: abre el selector de fotos del sistema y a continuación
 * la pantalla de recorte (CanHub Android-Image-Cropper) en proporción 1:1.
 */
@Composable
fun AvatarPicker(
    localPreviewUri: Uri?,
    remoteAvatarUrl: String?,
    size: androidx.compose.ui.unit.Dp = 96.dp,
    onCropped: (Uri) -> Unit,
) {
    val context = LocalContext.current

    val cropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            result.uriContent?.let(onCropped)
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val cropOptions = CropImageOptions().apply {
                fixAspectRatio = true
                aspectRatioX = 1
                aspectRatioY = 1
                cropShape = CropImageView.CropShape.OVAL
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
            cropLauncher.launch(CropImageContractOptions(uri = uri, cropImageOptions = cropOptions))
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { pickLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
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
