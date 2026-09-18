package com.heartbeatheaven.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal data class UploadedChatMedia(val type: String, val url: String, val name: String, val size: Long)

internal object ChatMediaSupport {
    private const val SUPABASE_URL = "https://fafvhyeesenpimxncupp.supabase.co"
    private const val KEY = "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"
    private const val BUCKET = "chat-media"
    private const val MAX_BYTES = 6L * 1024L * 1024L

    fun upload(context: Context, uri: Uri, session: AuthSession, otherUserId: String): Result<UploadedChatMedia> = runCatching {
        val resolver = context.contentResolver
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)) else -1L
        } ?: -1L
        if (size > MAX_BYTES) error("Please choose a file smaller than 6 MB.")
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null } ?: "attachment"
        val mime = resolver.getType(uri).orEmpty().lowercase()
        val type = when { mime.startsWith("image/") -> "image"; mime.startsWith("video/") -> "video"; mime.startsWith("audio/") -> "audio"; else -> "file" }
        val extension = name.substringAfterLast(".", "").lowercase().takeIf { it.isNotBlank() } ?: when (type) { "image" -> "jpg"; "video" -> "mp4"; "audio" -> "mp3"; else -> "bin" }
        val path = session.profile.id + "/" + otherUserId + "/" + UUID.randomUUID().toString() + "." + extension
        val input = resolver.openInputStream(uri) ?: error("Could not read the selected file.")
        val c = URL(SUPABASE_URL + "/storage/v1/object/" + BUCKET + "/" + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 20000; c.readTimeout = 60000; c.doOutput = true
            c.setRequestProperty("apikey", KEY); c.setRequestProperty("Authorization", "Bearer " + session.accessToken)
            c.setRequestProperty("Content-Type", mime.ifBlank { "application/octet-stream" }); c.setRequestProperty("x-upsert", "false")
            input.use { src -> src.copyTo(c.outputStream) }
            if (c.responseCode !in 200..299) error("Attachment upload failed (${c.responseCode}).")
        } finally { c.disconnect() }
        val publicUrl = SUPABASE_URL + "/storage/v1/object/public/" + BUCKET + "/" + path
        UploadedChatMedia(type, publicUrl, name, if (size >= 0) size else 0L)
    }
}