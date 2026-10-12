package com.airbridge.app.network

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClient {

    companion object {
        private const val TAG = "ApiClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun ping(host: String, port: Int): JSONObject? = withContext(Dispatchers.IO) {
        val url = "http://$host:$port/api/ping"
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    JSONObject(body)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ping failed for $url: ${e.message}")
            null
        }
    }

    suspend fun uploadImage(
        host: String,
        port: Int,
        uri: Uri,
        contentResolver: ContentResolver,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val url = "http://$host:$port/api/upload"

        var fileName = "image_${System.currentTimeMillis()}.jpg"
        var fileSize = 0L

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: fileName
                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
            }
        }

        try {
            val inputStream = contentResolver.openInputStream(uri)
                ?: throw IOException("Unable to open input stream for $uri")

            val tempFile = File.createTempFile("airbridge_upload_", ".tmp")
            tempFile.deleteOnExit()

            tempFile.outputStream().use { out ->
                inputStream.copyTo(out)
            }

            val mediaType = (contentResolver.getType(uri) ?: "image/jpeg").toMediaTypeOrNull()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    fileName,
                    tempFile.asRequestBody(mediaType)
                )
                .build()

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            onProgress(0.5f)

            client.newCall(request).execute().use { response ->
                tempFile.delete()
                val success = response.isSuccessful
                if (success) {
                    onProgress(1.0f)
                }
                success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload image $uri", e)
            false
        }
    }

    suspend fun sendTranscription(
        host: String,
        port: Int,
        text: String,
        typeIntoWindow: Boolean = false,
        copyClipboard: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val url = "http://$host:$port/api/transcription"
        val json = JSONObject().apply {
            put("text", text)
            put("type_into_window", typeIntoWindow)
            put("copy_clipboard", copyClipboard)
            put("timestamp", System.currentTimeMillis())
        }

        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send transcription", e)
            false
        }
    }

    suspend fun transcribeAudioFile(
        host: String,
        port: Int,
        audioFile: File,
        prompt: String = ""
    ): String? = withContext(Dispatchers.IO) {
        val url = "http://$host:$port/api/transcribe_audio"
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/wav".toMediaTypeOrNull())
            )
            .addFormDataPart("prompt", prompt)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseText = response.body?.string() ?: ""
                    val json = JSONObject(responseText)
                    json.optString("text", "")
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio transcription request failed", e)
            null
        }
    }
}
