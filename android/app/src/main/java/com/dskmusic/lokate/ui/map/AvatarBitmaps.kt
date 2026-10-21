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

/**
 * Descarga y cachea la foto de perfil de cada miembro, para dibujarla en el marcador del mapa.
 * Devuelve usuario -> foto, que es lo que necesita el mapa.
 *
 * La caché va por URL y no por usuario: al cambiar su foto, el servidor devuelve una URL distinta
 * (lleva versión, ver save_avatar en backend/app/routers/auth.py), y con la clave puesta en el
 * usuario el marcador se queda con la foto vieja hasta reiniciar la app.
 */
@Composable
fun rememberAvatarBitmaps(members: List<LocationDto>): Map<String, Bitmap> {
    val context = LocalContext.current
    val imageLoader = remember { ImageLoader.Builder(context).build() }
    // ponytail: las fotos que se reemplazan se quedan en el mapa hasta cerrar la pantalla. Son
    // cuatro avatares, no merece la pena limpiarlo.
    var byUrl by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }

    val urls = members.mapNotNull { member ->
        absoluteAvatarUrl(member.avatar_url)?.let { member.user_id to it }
    }
    val pending = urls.filter { (_, url) -> !byUrl.containsKey(url) }

    LaunchedEffect(pending) {
        if (pending.isEmpty()) return@LaunchedEffect
        val loaded = mutableMapOf<String, Bitmap>()
        for ((_, url) in pending) {
            val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
            val result = runCatching { imageLoader.execute(request) }.getOrNull()
            val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            if (bitmap != null) loaded[url] = bitmap
        }
        if (loaded.isNotEmpty()) byUrl = byUrl + loaded
    }

    return urls.mapNotNull { (userId, url) -> byUrl[url]?.let { userId to it } }.toMap()
}
