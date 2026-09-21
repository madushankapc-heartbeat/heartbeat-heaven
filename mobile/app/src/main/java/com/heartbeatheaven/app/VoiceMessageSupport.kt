package com.heartbeatheaven.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal object VoiceMessageSupport {
    private const val SUPABASE_URL = "https://fafvhyeesenpimxncupp.supabase.co"
    private const val KEY = "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"
    private const val BUCKET = "chat-media"
    private const val MAX_BYTES = 10L * 1024L * 1024L

    fun newRecordingFile(context: Context, extension: String = "m4a"): File {
        val dir = File(context.cacheDir, "voice_messages").apply { mkdirs() }
        return File(dir, "voice-${UUID.randomUUID()}.$extension")
    }

    fun mimeTypeFor(file: File): String =
        if (file.extension.equals("3gp", ignoreCase = true)) "audio/3gpp" else "audio/mp4"

    fun upload(
        context: Context,
        file: File,
        session: AuthSession,
        otherUserId: String,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<UploadedChatMedia> = runCatching {
        val size = file.length()
        if (size <= 0L) error("Voice recording is empty.")
        if (size > MAX_BYTES) error("Voice message is too large.")
        val path = session.profile.id + "/" + otherUserId + "/" + UUID.randomUUID() + ".m4a"
        val connection = URL(
            SUPABASE_URL + "/storage/v1/object/" + BUCKET + "/" + path
        ).openConnection() as HttpURLConnection

        var uploadedBytes = 0L
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 300000
            connection.doOutput = true
            connection.setRequestProperty("apikey", KEY)
            connection.setRequestProperty("Authorization", "Bearer " + session.accessToken)
            connection.setRequestProperty("Content-Type", mimeTypeFor(file))
            connection.setRequestProperty("x-upsert", "false")

            file.inputStream().use { input ->
                connection.outputStream.use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        uploadedBytes += read
                        onProgress(uploadedBytes, size)
                    }
                    output.flush()
                }
            }

            if (connection.responseCode !in 200..299) {
                error("Voice message upload failed (${connection.responseCode}).")
            }
        } finally {
            connection.disconnect()
        }

        UploadedChatMedia(
            type = "audio",
            url = SUPABASE_URL + "/storage/v1/object/public/" + BUCKET + "/" + path,
            name = "Voice message.${file.extension.ifBlank { "m4a" }}",
            size = if (size > 0L) size else uploadedBytes
        )
    }
}
