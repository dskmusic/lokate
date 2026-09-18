package com.dskmusic.lokate.ui.emergency

import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.dskmusic.lokate.R
import com.dskmusic.lokate.util.MediaSaver
import kotlinx.coroutines.launch
import java.io.File

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EmergencyMessageScreen(
    sender: String,
    text: String,
    attachmentUrl: String?,
    attachmentKind: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val mimeType = remember(attachmentUrl, attachmentKind) {
        val ext = attachmentUrl?.substringAfterLast('.', "")
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (attachmentKind) {
            "image" -> "image/jpeg"
            "video" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }
    val fileName = remember(attachmentUrl) { attachmentUrl?.substringAfterLast('/') ?: "adjunto" }

    suspend fun ensureDownloaded(): File? {
        downloadedFile?.let { return it }
        val url = attachmentUrl ?: return null
        return runCatching { MediaSaver.downloadToCache(context, url, fileName) }
            .onSuccess { downloadedFile = it }
            .getOrNull()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sender) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))

            if (attachmentUrl != null) {
                when (attachmentKind) {
                    "image" -> AsyncImage(
                        model = attachmentUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(360.dp),
                    )
                    "video" -> Box(Modifier.fillMaxWidth().height(360.dp)) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoURI(Uri.parse(attachmentUrl))
                                    setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                                    setOnPreparedListener { it.isLooping = false; start() }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> Text(stringResource(R.string.emergency_attachment_generic))
                }

                Spacer(Modifier.height(16.dp))
                Row {
                    OutlinedButton(
                        onClick = {
                            working = true
                            scope.launch {
                                val file = ensureDownloaded()
                                val saved = file != null && MediaSaver.saveToDevice(context, file, mimeType, fileName, forceDownloads = true)
                                message = context.getString(
                                    if (saved) R.string.emergency_media_saved else R.string.emergency_media_error,
                                )
                                working = false
                            }
                        },
                        enabled = !working,
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.emergency_save_button))
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = {
                            working = true
                            scope.launch {
                                val file = ensureDownloaded()
                                working = false
                                if (file != null) MediaSaver.shareFile(context, file, mimeType)
                            }
                        },
                        enabled = !working,
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.emergency_share_button))
                    }
                }

                if (working) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
                message?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
