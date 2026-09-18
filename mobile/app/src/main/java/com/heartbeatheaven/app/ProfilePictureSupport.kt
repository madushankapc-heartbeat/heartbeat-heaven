package com.heartbeatheaven.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal object ProfilePictureSupport {
    private const val SUPABASE_URL = "https://fafvhyeesenpimxncupp.supabase.co"
    private const val KEY = "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"
    private const val BUCKET = "profile-pictures"

    fun upload(context: Context, uri: Uri, session: AuthSession): Result<String> = runCatching {
        val source = context.contentResolver.openInputStream(uri) ?: error("Could not read the selected image.")
        val original = source.use { BitmapFactory.decodeStream(it) } ?: error("Selected file is not a valid image.")
        val scaled = if (original.width > 1200 || original.height > 1200) {
            val ratio = minOf(1200f / original.width, 1200f / original.height)
            Bitmap.createScaledBitmap(original, (original.width * ratio).toInt(), (original.height * ratio).toInt(), true)
        } else original
        val bytes = ByteArrayOutputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 88, out); out.toByteArray() }
        if (scaled !== original) scaled.recycle()
        if (!original.isRecycled) original.recycle()
        val path = session.profile.id + "/avatar.jpg"
        val c = URL(SUPABASE_URL + "/storage/v1/object/" + BUCKET + "/" + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 20000; c.readTimeout = 30000; c.doOutput = true
            c.setRequestProperty("apikey", KEY); c.setRequestProperty("Authorization", "Bearer " + session.accessToken)
            c.setRequestProperty("Content-Type", "image/jpeg"); c.setRequestProperty("x-upsert", "true")
            c.outputStream.use { it.write(bytes) }
            if (c.responseCode !in 200..299) error("Profile photo upload failed (${c.responseCode}).")
        } finally { c.disconnect() }
        val publicUrl = SUPABASE_URL + "/storage/v1/object/public/" + BUCKET + "/" + path + "?v=" + System.currentTimeMillis()
        val p = URL(SUPABASE_URL + "/rest/v1/profiles?id=eq." + session.profile.id).openConnection() as HttpURLConnection
        try {
            p.requestMethod = "PATCH"; p.connectTimeout = 15000; p.readTimeout = 20000; p.doOutput = true
            p.setRequestProperty("apikey", KEY); p.setRequestProperty("Authorization", "Bearer " + session.accessToken); p.setRequestProperty("Content-Type", "application/json")
            p.outputStream.use { it.write(JSONObject().put("avatar_url", publicUrl).toString().toByteArray()) }
            if (p.responseCode !in 200..299) error("Profile photo was uploaded but the profile could not be updated.")
        } finally { p.disconnect() }
        publicUrl
    }

    fun remove(session: AuthSession): Result<Unit> = runCatching {
        val c = URL(SUPABASE_URL + "/storage/v1/object/" + BUCKET + "/" + session.profile.id + "/avatar.jpg").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "DELETE"; c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("apikey", KEY); c.setRequestProperty("Authorization", "Bearer " + session.accessToken)
            if (c.responseCode !in 200..299 && c.responseCode != 404) error("Could not remove profile photo (${c.responseCode}).")
        } finally { c.disconnect() }
        val p = URL(SUPABASE_URL + "/rest/v1/profiles?id=eq." + session.profile.id).openConnection() as HttpURLConnection
        try {
            p.requestMethod = "PATCH"; p.connectTimeout = 15000; p.readTimeout = 20000; p.doOutput = true
            p.setRequestProperty("apikey", KEY); p.setRequestProperty("Authorization", "Bearer " + session.accessToken); p.setRequestProperty("Content-Type", "application/json")
            p.outputStream.use { it.write(JSONObject().put("avatar_url", JSONObject.NULL).toString().toByteArray()) }
            if (p.responseCode !in 200..299) error("Profile update failed (${p.responseCode}).")
        } finally { p.disconnect() }
    }
}