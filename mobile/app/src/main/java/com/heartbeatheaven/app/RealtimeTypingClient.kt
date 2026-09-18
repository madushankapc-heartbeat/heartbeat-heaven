package com.heartbeatheaven.app

import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Ephemeral Supabase Realtime Broadcast client for chat typing state. */
class RealtimeTypingClient(
    private val accessTokenProvider: () -> String,
    private val userId: String,
    private val apiKey: String,
    private val otherUserId: String,
    private val onTypingChanged: (Boolean) -> Unit
) {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var ref = 0
    private var joinRef = "0"
    private var stopped = true

    private val topic: String
        get() {
            val ids = listOf(userId, otherUserId).sorted()
            return "realtime:typing-" + ids[0] + "-" + ids[1]
        }

    private fun nextRef() = (++ref).toString()

    fun start() {
        stopped = false
        connect()
    }

    private fun connect() {
        if (stopped) return
        val currentTopic = topic
        joinRef = nextRef()
        val url = "wss://fafvhyeesenpimxncupp.supabase.co/realtime/v1/websocket?apikey=$apiKey&vsn=1.0.0"
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val payload = JSONObject()
                    .put("config", JSONObject()
                        .put("broadcast", JSONObject().put("ack", false).put("self", false))
                        .put("presence", JSONObject().put("enabled", false))
                        .put("postgres_changes", org.json.JSONArray()))
                    .put("access_token", accessTokenProvider())
                webSocket.send(JSONObject()
                    .put("topic", currentTopic).put("event", "phx_join")
                    .put("payload", payload).put("ref", joinRef).put("join_ref", joinRef).toString())

                heartbeatJob?.cancel()
                heartbeatJob = scope.launch {
                    while (isActive && !stopped) {
                        delay(25000)
                        webSocket.send(JSONObject().put("topic", currentTopic).put("event", "access_token")
                            .put("payload", JSONObject().put("access_token", accessTokenProvider()))
                            .put("ref", nextRef()).put("join_ref", joinRef).toString())
                        webSocket.send(JSONObject().put("topic", "phoenix").put("event", "heartbeat")
                            .put("payload", JSONObject()).put("ref", nextRef()).toString())
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = JSONObject(text)
                    if (root.optString("event") != "broadcast") return@runCatching
                    val payload = root.optJSONObject("payload")?.optJSONObject("payload") ?: return@runCatching
                    if (payload.optString("user_id") == otherUserId) {
                        onTypingChanged(payload.optBoolean("typing", false))
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                heartbeatJob?.cancel(); onTypingChanged(false); reconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                heartbeatJob?.cancel(); onTypingChanged(false); reconnect()
            }
        })
    }

    private fun reconnect() {
        if (stopped || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch { delay(2000); if (!stopped) connect() }
    }

    fun setTyping(typing: Boolean) {
        if (stopped) return
        socket?.send(JSONObject()
            .put("topic", topic).put("event", "broadcast")
            .put("payload", JSONObject().put("type", "broadcast").put("event", "typing")
                .put("payload", JSONObject().put("user_id", userId).put("typing", typing)))
            .put("ref", nextRef()).put("join_ref", joinRef).toString())
    }

    fun stop() {
        stopped = true
        heartbeatJob?.cancel(); reconnectJob?.cancel()
        socket?.close(1000, "closed"); socket = null
        onTypingChanged(false)
    }
}
