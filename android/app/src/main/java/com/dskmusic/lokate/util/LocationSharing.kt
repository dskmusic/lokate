package com.dskmusic.lokate.util

import android.content.Context
import android.content.Intent
import android.net.Uri

object LocationSharing {

    fun shareIntent(lat: Double, lng: Double, label: String? = null): Intent {
        val mapsUrl = "https://maps.google.com/?q=$lat,$lng"
        val text = if (label != null) "$label: $mapsUrl" else mapsUrl
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    }

    fun inviteIntent(groupName: String, inviteCode: String): Intent {
        val text = "Únete a mi grupo \"$groupName\" en Lokate by DSK con este código: $inviteCode"
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    }

    fun openInGoogleMapsIntent(lat: Double, lng: Double, label: String = ""): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)"))

    fun openInGoogleMaps(context: Context, lat: Double, lng: Double, label: String = "") {
        val intent = openInGoogleMapsIntent(lat, lng, label)
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lng")))
        }
    }
}
