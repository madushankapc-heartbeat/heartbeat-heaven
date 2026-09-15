package com.heartbeatheaven.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val APP_SUPABASE_URL = "https://fafvhyeesenpimxncupp.supabase.co"
private const val APP_SUPABASE_KEY = "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"

/** Keeps message delivery acknowledgement active across every app tab, not only Friends/chat. */
class HeartbeatHeavenApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var realtime: RealtimeMessagesClient? = null
    private var activeUserId: String? = null

    override fun onCreate() {
        super.onCreate()
        val auth = AuthApi(this)
        scope.launch {
            while (isActive) {
                val session = runCatching { auth.currentSession() }.getOrNull()
                val userId = session?.profile?.id
                if (!userId.isNullOrBlank() && userId != activeUserId) {
                    realtime?.stop()
                    realtime = RealtimeMessagesClient(
                        { auth.currentSession()?.accessToken.orEmpty() },
                        userId,
                        APP_SUPABASE_KEY
                    ) { messageId, _, _, _ ->
                        acknowledgeDelivery(auth, messageId)
                    }
                    activeUserId = userId
                    realtime?.start()
                } else if (userId.isNullOrBlank() && activeUserId != null) {
                    realtime?.stop()
                    realtime = null
                    activeUserId = null
                }
                delay(30_000)
            }
        }
    }

    private fun acknowledgeDelivery(auth: AuthApi, messageId: String) {
        val session = auth.currentSession() ?: return
        val connection = URL(
            "$APP_SUPABASE_URL/rest/v1/messages?id=eq.$messageId&receiver_id=eq.${session.profile.id}"
        ).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PATCH"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("apikey", APP_SUPABASE_KEY)
            connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use {
                it.write(JSONObject().put("delivered_at", java.time.Instant.now().toString()).toString().toByteArray())
            }
            connection.inputStream?.close()
        } catch (_: Exception) {
            // Realtime will reconnect; chat polling can also acknowledge delivery later.
        } finally {
            connection.disconnect()
        }
    }
}
