package com.heartbeatheaven.app

import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RealtimeNotificationsClient(
    private val accessTokenProvider: () -> String,
    private val userId: String,
    private val apiKey: String,
    private val onNotificationChange: () -> Unit
) {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var ref = 0
    private var joinRef = "0"
    private var stopped = false

    fun start() { stopped = false; connect() }
    private fun nextRef() = (++ref).toString()

    private fun connect() {
        if (stopped) return
        val topic = "realtime:notifications-$userId"
        val url = "wss://fafvhyeesenpimxncupp.supabase.co/realtime/v1/websocket?apikey=$apiKey&vsn=1.0.0"
        joinRef = nextRef()
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val changes = JSONArray().put(
                    JSONObject()
                        .put("event", "INSERT")
                        .put("schema", "public")
                        .put("table", "notifications")
                        .put("filter", "recipient_id=eq.$userId")
                )
                val payload = JSONObject()
                    .put("config", JSONObject()
                        .put("broadcast", JSONObject().put("ack", false).put("self", false))
                        .put("presence", JSONObject().put("enabled", false).put("key", userId))
                        .put("postgres_changes", changes))
                    .put("access_token", accessTokenProvider())
                webSocket.send(JSONObject().put("topic", topic).put("event", "phx_join").put("payload", payload).put("ref", joinRef).put("join_ref", joinRef).toString())

                heartbeatJob?.cancel()
                heartbeatJob = scope.launch {
                    while (isActive && !stopped) {
                        delay(25000)
                        webSocket.send(JSONObject().put("topic", topic).put("event", "access_token").put("payload", JSONObject().put("access_token", accessTokenProvider())).put("ref", nextRef()).put("join_ref", joinRef).toString())
                        webSocket.send(JSONObject().put("topic", "phoenix").put("event", "heartbeat").put("payload", JSONObject()).put("ref", nextRef()).toString())
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = JSONObject(text)
                    when (root.optString("event")) {
                        "postgres_changes" -> onNotificationChange()
                        "system" -> if (root.optJSONObject("payload")?.optString("message").orEmpty().contains("expired", true)) webSocket.close(1000, "refresh token")
                        "phx_error", "phx_close" -> webSocket.close(1000, "reconnect")
                    }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { heartbeatJob?.cancel(); reconnect() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { heartbeatJob?.cancel(); reconnect() }
        })
    }

    private fun reconnect() {
        if (stopped || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch { delay(2000); if (!stopped) connect() }
    }

    fun stop() {
        stopped = true
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        socket?.close(1000, "closed")
        socket = null
    }
}
