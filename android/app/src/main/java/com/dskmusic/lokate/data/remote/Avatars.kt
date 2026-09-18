package com.dskmusic.lokate.data.remote

import com.dskmusic.lokate.BuildConfig

/** El backend devuelve rutas relativas (`/avatars/xxx.jpg`, `/attachments/xxx.jpg`); se resuelven contra la API base. */
fun absoluteMediaUrl(path: String?): String? {
    if (path.isNullOrBlank()) return null
    if (path.startsWith("http")) return path
    return BuildConfig.API_BASE_URL.trimEnd('/') + path
}

fun absoluteAvatarUrl(avatarUrl: String?): String? = absoluteMediaUrl(avatarUrl)
