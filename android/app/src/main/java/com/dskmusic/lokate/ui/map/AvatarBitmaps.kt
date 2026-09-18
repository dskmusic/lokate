package com.dskmusic.lokate.ui.map

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.dskmusic.lokate.data.remote.absoluteAvatarUrl
import com.dskmusic.lokate.data.remote.dto.LocationDto

/** Descarga y cachea (por usuario) la foto de perfil de cada miembro, para dibujarla en el marcador del mapa. */
@Composable
fun rememberAvatarBitmaps(members: List<LocationDto>): Map<String, Bitmap> {
    val context = LocalContext.current
    val imageLoader = remember { ImageLoader.Builder(context).build() }
    var bitmaps by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }

    val pending = members
        .filter { it.avatar_url != null && !bitmaps.containsKey(it.user_id) }
        .map { it.user_id to absoluteAvatarUrl(it.avatar_url) }

    LaunchedEffect(pending) {
        if (pending.isEmpty()) return@LaunchedEffect
        val loaded = mutableMapOf<String, Bitmap>()
        for ((userId, url) in pending) {
            val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
            val result = runCatching { imageLoader.execute(request) }.getOrNull()
            val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            if (bitmap != null) loaded[userId] = bitmap
        }
        if (loaded.isNotEmpty()) bitmaps = bitmaps + loaded
    }

    return bitmaps
}
