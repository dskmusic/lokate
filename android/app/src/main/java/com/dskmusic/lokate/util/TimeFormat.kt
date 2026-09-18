package com.dskmusic.lokate.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dskmusic.lokate.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun parseIsoDate(iso: String): Date? {
    val formats = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", "yyyy-MM-dd'T'HH:mm:ss")
    for (pattern in formats) {
        val date = try {
            SimpleDateFormat(pattern, Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(iso)
        } catch (e: java.text.ParseException) {
            null
        }
        if (date != null) return date
    }
    return null
}

fun formatTimestamp(iso: String): String {
    val date = parseIsoDate(iso) ?: return iso
    return SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(date)
}

@Composable
fun formatRelativeTime(iso: String): String {
    val date = parseIsoDate(iso) ?: return iso
    val minutes = (System.currentTimeMillis() - date.time) / 60_000
    return when {
        minutes < 1 -> stringResource(R.string.time_just_now)
        minutes < 60 -> stringResource(R.string.time_minutes, minutes.toInt())
        minutes < 60 * 24 -> stringResource(R.string.time_hours, (minutes / 60).toInt())
        else -> stringResource(R.string.time_days, (minutes / (60 * 24)).toInt())
    }
}
