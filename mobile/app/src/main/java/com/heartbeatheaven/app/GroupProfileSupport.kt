package com.heartbeatheaven.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal object GroupProfileSupport {
    private const val SUPABASE_URL = "https://fafvhyeesenpimxncupp.supabase.co"
    private const val KEY = "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"
    private const val BUCKET = "group-profile-pictures"
    private const val MAX_BYTES = 5L * 1024L * 1024L

    fun upload(context: Context, uri: Uri, session: AuthSession, groupId: String): String = runCatching {
        val resolver = context.contentResolver
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)) else -1L
        } ?: -1L
        if (size > MAX_BYTES) error("Group picture must be 5 MB or smaller.")
        val mime = resolver.getType(uri).orEmpty().lowercase()
        val extension = when (mime) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> error("Please choose a JPG, PNG, WEBP, or GIF image.")
        }
        val path = "$groupId/${UUID.randomUUID()}.$extension"
        val input = resolver.openInputStream(uri) ?: error("Could not read the selected image.")
        val c = URL("$SUPABASE_URL/storage/v1/object/$BUCKET/$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20000
            c.readTimeout = 120000
            c.doOutput = true
            c.setRequestProperty("apikey", KEY)
            c.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            c.setRequestProperty("Content-Type", mime)
            c.setRequestProperty("x-upsert", "false")
            input.use { src -> c.outputStream.use { out ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val read = src.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) error("Group picture must be 5 MB or smaller.")
                    out.write(buffer, 0, read)
                }
                out.flush()
            } }
            if (c.responseCode !in 200..299) error("Group picture upload failed (${c.responseCode}).")
        } finally { c.disconnect() }
        path
    }.getOrThrow()

    fun signedUrl(session: AuthSession, path: String, expiresIn: Int = 600): String = runCatching {
        if (path.contains("..") || path.startsWith("/")) error("Invalid group picture path.")
        val c = URL("$SUPABASE_URL/storage/v1/object/sign/$BUCKET/$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("apikey", KEY)
            c.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.outputStream.use { it.write(JSONObject().put("expiresIn", expiresIn).toString().toByteArray()) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Could not load group picture.")
            val signed = JSONObject(body).optString("signedURL").ifBlank { JSONObject(body).optString("signedUrl") }
            if (signed.isBlank()) error("Could not load group picture.")
            if (signed.startsWith("http")) signed else SUPABASE_URL + "/storage/v1" + signed
        } finally { c.disconnect() }
    }.getOrThrow()

    fun signedUrls(session: AuthSession, paths: List<String>, expiresIn: Int = 600): Map<String, String> = runCatching {
        if (paths.isEmpty()) return@runCatching emptyMap()
        val payload = JSONObject().put("expiresIn", expiresIn).put("paths", JSONArray(paths))
        val c = URL("$SUPABASE_URL/storage/v1/object/sign/$BUCKET").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("apikey", KEY)
            c.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Could not load group pictures.")
            val result = mutableMapOf<String, String>()
            val rows = JSONArray(body)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val path = row.optString("path")
                val signed = row.optString("signedURL").ifBlank { row.optString("signedUrl") }
                if (path.isNotBlank() && signed.isNotBlank()) result[path] = if (signed.startsWith("http")) signed else SUPABASE_URL + "/storage/v1" + signed
            }
            result
        } finally { c.disconnect() }
    }.getOrThrow()

    fun delete(session: AuthSession, path: String) {
        if (path.contains("..") || path.startsWith("/")) error("Invalid group picture path.")
        val c = URL("$SUPABASE_URL/storage/v1/object/$BUCKET/$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "DELETE"
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("apikey", KEY)
            c.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            if (c.responseCode !in 200..299) error("Could not remove old group picture (${c.responseCode}).")
        } finally { c.disconnect() }
    }
}
