package com.dskmusic.lokate.data.repository

import com.dskmusic.lokate.data.remote.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class MessageRepository(private val api: ApiService) {

    suspend fun sendEmergencyMessage(userId: String, text: String, attachment: File?, attachmentMimeType: String?) =
        withContext(Dispatchers.IO) {
            val textBody = text.toRequestBody("text/plain".toMediaType())
            val filePart = attachment?.let {
                val mediaType = (attachmentMimeType ?: "application/octet-stream").toMediaType()
                MultipartBody.Part.createFormData("file", it.name, it.asRequestBody(mediaType))
            }
            api.sendEmergencyMessage(userId, textBody, filePart)
        }
}
