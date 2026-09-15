package com.heartbeatheaven.app

import android.app.Application
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

internal object GlobalChatManager {
    private const val SUPABASE_URL = "https://fafvhyeesenpimxncupp.supabase.co"
    private const val SUPABASE_KEY = "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"
    private const val CHANNEL_ID = "chat_messages"
    private const val CHANNEL_NAME = "Chat messages"
    private const val NOTIFICATION_ID = 4101

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var realtime: RealtimeMessagesClient? = null
    private var activeUserId: String? = null
    private var lastMessageIds = ArrayDeque<String>()

    fun start(context: Context) {
        val app = context.applicationContext
        createChannel(app)
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            val auth = AuthApi(app)
            var lastPresence = 0L
            while (isActive) {
                val session = runCatching { auth.currentSession() }.getOrNull()
                if (session == null) {
                    stopRealtime()
                    activeUserId = null
                } else if (activeUserId != session.profile.id) {
                    stopRealtime()
                    activeUserId = session.profile.id
                    startRealtime(app, auth, session.profile.id)
                    touchPresence(session.accessToken, session.profile.id)
                    lastPresence = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - lastPresence >= 30_000L) {
                    val refreshed = runCatching { auth.currentSession() }.getOrNull()
                    if (refreshed != null) {
                        touchPresence(refreshed.accessToken, refreshed.profile.id)
                        lastPresence = System.currentTimeMillis()
                    }
                }
                delay(5_000L)
            }
        }
    }

    private fun startRealtime(context: Context, auth: AuthApi, userId: String) {
        realtime = RealtimeMessagesClient(
            accessTokenProvider = { runCatching { auth.currentSession()?.accessToken }.getOrNull().orEmpty() },
            userId = userId,
            apiKey = SUPABASE_KEY
        ) { id, senderId, body, createdAt ->
            if (id.isNotBlank() && rememberMessage(id)) {
                scope.launch {
                    val session = runCatching { auth.currentSession() }.getOrNull() ?: return@launch
                    markDelivered(session.accessToken, id, session.profile.id)
                    showMessageNotification(context, senderId, body, createdAt)
                }
            }
        }
        realtime?.start()
    }

    private fun rememberMessage(id: String): Boolean {
        synchronized(lastMessageIds) {
            if (lastMessageIds.contains(id)) return false
            lastMessageIds.addLast(id)
            while (lastMessageIds.size > 100) lastMessageIds.removeFirst()
            return true
        }
    }

    private fun stopRealtime() {
        realtime?.stop()
        realtime = null
    }

    private fun touchPresence(accessToken: String, userId: String) {
        runCatching {
            request(
                "/rest/v1/profiles?id=eq.$userId",
                "PATCH",
                accessToken,
                JSONObject().put("last_seen_at", Instant.now().toString()).toString()
            )
        }
    }

    private fun markDelivered(accessToken: String, messageId: String, userId: String) {
        runCatching {
            request(
                "/rest/v1/messages?id=eq.$messageId&receiver_id=eq.$userId",
                "PATCH",
                accessToken,
                JSONObject().put("delivered_at", Instant.now().toString()).toString()
            )
        }
    }

    private fun request(path: String, method: String, accessToken: String, body: String): String {
        val c = URL(SUPABASE_URL + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("apikey", SUPABASE_KEY)
            c.setRequestProperty("Authorization", "Bearer $accessToken")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Content-Type", "application/json")
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            c.disconnect()
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "New HEARTBEAT HEAVEN chat messages"
                    }
                )
            }
        }
    }

    private fun showMessageNotification(context: Context, senderId: String, body: String, createdAt: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_chat_sender_id", senderId)
            putExtra("message_created_at", createdAt)
        }
        val pending = PendingIntent.getActivity(
            context,
            senderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val preview = body.trim().ifBlank { "New message" }.take(120)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.heartbeat_heaven_logo)
            .setContentTitle("New message")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setNumber(1)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID + (senderId.hashCode() and 0x3FF), notification)
    }
}

class HeartbeatHeavenApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GlobalChatManager.start(this)
    }
}
