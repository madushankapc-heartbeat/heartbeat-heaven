package com.heartbeatheaven.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object OwnerChatNotificationManager {
    private const val URL = "https://fafvhyeesenpimxncupp.supabase.co/functions/v1/owner-chat"
    private const val KEY = "sb_" + "publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"
    private const val CHANNEL_ID = "owner_chat_messages_v1"
    private const val CHANNEL_NAME = "Contact Owner"
    private const val PREFS = "owner_chat_notifications"
    private const val ADMIN_SEEN = "admin_seen_ids"
    private const val USER_SEEN = "user_seen_ids"
    private const val BASE_ID = 7201

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    fun start(context: Context) {
        val app = context.applicationContext
        createChannel(app)
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runCatching { poll(app) }
                delay(5_000L)
            }
        }
    }

    private fun poll(context: Context) {
        val auth = AuthApi(context)
        val session = runCatching { auth.currentSession() }.getOrNull()
        if (session?.profile?.isAdmin == true) {
            pollAdmin(context, session.accessToken)
        } else {
            pollUser(context, session?.accessToken)
        }
    }

    private fun pollAdmin(context: Context, token: String) {
        val root = request(context, token, JSONObject().put("action", "list"))
        val conversations = root.optJSONArray("conversations") ?: JSONArray()
        for (i in 0 until conversations.length()) {
            val c = conversations.optJSONObject(i) ?: continue
            val id = c.optString("id")
            if (id.isBlank()) continue
            val detail = request(context, token, JSONObject().put("action", "list").put("conversation_id", id))
            val messages = detail.optJSONArray("messages") ?: continue
            val latest = messages.optJSONObject(messages.length() - 1) ?: continue
            if (latest.optString("sender_type") == "user") {
                notifyIfNew(context, ADMIN_SEEN, latest.optString("id"), "New Contact Owner message", c.optString("label").ifBlank { "User" }, latest.optString("body"), true)
            }
        }
    }

    private fun pollUser(context: Context, token: String?) {
        val root = request(context, token, JSONObject().put("action", "list"))
        val messages = root.optJSONArray("messages") ?: return
        val latest = messages.optJSONObject(messages.length() - 1) ?: return
        if (latest.optString("sender_type") == "admin") {
            notifyIfNew(context, USER_SEEN, latest.optString("id"), "Reply from HEARTBEAT HEAVEN", "Owner", latest.optString("body"), false)
        }
    }

    private fun request(context: Context, token: String?, payload: JSONObject): JSONObject {
        val c = URL(URL).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("apikey", KEY)
            if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $token")
            val guest = context.getSharedPreferences("owner_chat", Context.MODE_PRIVATE).getString("guest_token", null)
            if (!guest.isNullOrBlank()) c.setRequestProperty("x-guest-token", guest)
            c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (c.responseCode !in 200..299 || text.isBlank()) return JSONObject()
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    private fun notifyIfNew(context: Context, prefKey: String, messageId: String, title: String, sender: String, body: String, admin: Boolean) {
        if (messageId.isBlank() || !remember(context, prefKey, messageId)) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val intent = Intent(context, if (admin) OwnerMessagesActivity::class.java else OwnerChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(context, messageId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val preview = body.trim().ifBlank { "New message" }.take(160)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.heartbeat_heaven_logo)
            .setContentTitle(title)
            .setContentText("$sender: $preview")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$sender: $preview"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(BASE_ID + (messageId.hashCode() and 0x7FFF), notification)
    }

    private fun remember(context: Context, key: String, id: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val set = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
            if (set.contains(id)) return false
            set.add(id)
            while (set.size > 500) set.remove(set.first())
            prefs.edit().putStringSet(key, set).apply()
            return true
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "New Contact Owner messages"
                })
            }
        }
    }
}
