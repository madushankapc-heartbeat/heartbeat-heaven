package com.heartbeatheaven.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.util.UUID

internal object GroupMediaSupport {
    private const val SUPABASE_URL = "https://fafvhyeesenpimxncupp.supabase.co"
    private const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"
    private const val BUCKET = "group-media"
    private const val MAX_BYTES = 50L * 1024L * 1024L
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    data class Uploaded(val type: String, val path: String, val size: Long)

    suspend fun upload(context: Context, uri: Uri, accessToken: String, groupId: String, mime: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<Uploaded> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedMime = mime.trim().lowercase()
            val type = when {
                normalizedMime.startsWith("image/") -> "image"
                normalizedMime.startsWith("video/") -> "video"
                else -> error("Only image and video files can be shared in Group Chats.")
            }
            val extension = when (normalizedMime) {
                "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"
                "video/mp4" -> "mp4"; "video/webm" -> "webm"; "video/quicktime" -> "mov"
                else -> error("This image/video format is not supported.")
            }
            val size = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(c.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE)) else -1L } ?: -1L
            if (size <= 0L) error("Could not determine the selected file size.")
            if (size > MAX_BYTES) error("Group media must be 50 MB or smaller.")
            val path = groupId + "/" + UUID.randomUUID().toString() + "." + extension
            val requestBody = object : RequestBody() {
                override fun contentType() = normalizedMime.toMediaTypeOrNull()
                override fun contentLength() = size
                override fun writeTo(sink: BufferedSink) {
                    val input = context.contentResolver.openInputStream(uri) ?: error("Could not open the selected media.")
                    input.use { stream ->
                        val buffer = ByteArray(64 * 1024); var sent = 0L
                        while (true) { val count = stream.read(buffer); if (count <= 0) break; sink.write(buffer, 0, count); sent += count; onProgress(sent, size) }
                    }
                }
            }
            val request = Request.Builder().url(SUPABASE_URL + "/storage/v1/object/" + BUCKET + "/" + path)
                .header("apikey", SUPABASE_PUBLISHABLE_KEY).header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", normalizedMime).header("x-upsert", "false").post(requestBody).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching { JSONObject(body).optString("message").ifBlank { JSONObject(body).optString("error") }.ifBlank { JSONObject(body).optString("statusCode") } }.getOrDefault("")
                    error(detail.ifBlank { "Media upload failed (HTTP " + response.code + ")." })
                }
            }
            onProgress(size, size); Uploaded(type, path, size)
        }
    }
}