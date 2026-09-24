package com.dskmusic.lokate.data.remote

/** El backend devuelve rutas relativas (`/avatars/xxx.jpg`, `/attachments/xxx.jpg`); se resuelven contra la API base. */
fun absoluteMediaUrl(path: String?): String? {
    if (path.isNullOrBlank()) return null
    if (path.startsWith("http")) return path
    // ServerConfig y no BuildConfig: si el usuario cambió de servidor en Ajustes, las fotos tienen
    // que salir del mismo sitio que los datos.
    return ServerConfig.baseUrl.trimEnd('/') + path
}

fun absoluteAvatarUrl(avatarUrl: String?): String? = absoluteMediaUrl(avatarUrl)
