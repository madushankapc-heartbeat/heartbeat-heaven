package com.heartbeatheaven.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class RealtimeMessageChange(
    val eventType: String,
    val id: String,
    val senderId: String,
    val receiverId: String,
    val body: String,
    val createdAt: String,
    val deliveredAt: String,
    val readAt: String
)

class RealtimeMessagesClient(
    private val accessTokenProvider: () -> String,
    private val userId: String,
    private val apiKey: String,
    private val onMessage: (id: String, senderId: String, body: String, createdAt: String) -> Unit,
    private val onMessageChange: (RealtimeMessageChange) -> Unit = {}
) {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var ref = 0
    private var joinRef = "0"
    private var stopped = false

    fun start() {
        stopped = false
        connect()
    }

    private fun nextRef(): String = (++ref).toString()

    private fun connect() {
        if (stopped) return
        val topic = "realtime:messages-$userId"
        val url = "wss://fafvhyeesenpimxncupp.supabase.co/realtime/v1/websocket?apikey=$apiKey&vsn=1.0.0"
        joinRef = nextRef()
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val changes = JSONArray()
                    .put(JSONObject()
                        .put("event", "INSERT")
                        .put("schema", "public")
                        .put("table", "messages")
                        .put("filter", "receiver_id=eq.$userId"))
                    .put(JSONObject()
                        .put("event", "UPDATE")
                        .put("schema", "public")
                        .put("table", "messages")
                        .put("filter", "receiver_id=eq.$userId"))
                    .put(JSONObject()
                        .put("event", "UPDATE")
                        .put("schema", "public")
                        .put("table", "messages")
                        .put("filter", "sender_id=eq.$userId"))

                val payload = JSONObject()
                    .put("config", JSONObject()
                        .put("broadcast", JSONObject().put("ack", false).put("self", false))
                        .put("presence", JSONObject().put("enabled", false).put("key", userId))
                        .put("postgres_changes", changes))
                    .put("access_token", accessTokenProvider())

                webSocket.send(
                    JSONObject()
                        .put("topic", topic)
                        .put("event", "phx_join")
                        .put("payload", payload)
                        .put("ref", joinRef)
                        .put("join_ref", joinRef)
                        .toString()
                )

                heartbeatJob?.cancel()
                heartbeatJob = scope.launch {
                    while (isActive && !stopped) {
                        delay(25000)
                        val token = accessTokenProvider()
                        webSocket.send(
                            JSONObject()
                                .put("topic", topic)
                                .put("event", "access_token")
                                .put("payload", JSONObject().put("access_token", token))
                                .put("ref", nextRef())
                                .put("join_ref", joinRef)
                                .toString()
                        )
                        webSocket.send(
                            JSONObject()
                                .put("topic", "phoenix")
                                .put("event", "heartbeat")
                                .put("payload", JSONObject())
                                .put("ref", nextRef())
                                .toString()
                        )
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = JSONObject(text)
                    when (root.optString("event")) {
                        "postgres_changes" -> {
                            val payload = root.optJSONObject("payload") ?: return@runCatching
                            val data = payload.optJSONObject("data") ?: payload
                            val record = data.optJSONObject("record") ?: return@runCatching
                            val eventType = data.optString("type").ifBlank {
                                payload.optString("eventType").ifBlank { root.optString("event_type") }
                            }
                            val senderId = record.optString("sender_id")
                            val receiverId = record.optString("receiver_id")
                            if (senderId == userId || receiverId == userId) {
                                val change = RealtimeMessageChange(
                                    eventType = eventType.ifBlank { "INSERT" },
                                    id = record.optString("id"),
                                    senderId = senderId,
                                    receiverId = receiverId,
                                    body = record.optString("body"),
                                    createdAt = record.optString("created_at"),
                                    deliveredAt = record.optString("delivered_at"),
                                    readAt = record.optString("read_at")
                                )
                                onMessageChange(change)
                                if (change.eventType.equals("INSERT", true) && receiverId == userId && senderId != userId) {
                                    onMessage(change.id, senderId, change.body, change.createdAt)
                                }
                            }
                        }
                        "system" -> {
                            val msg = root.optJSONObject("payload")?.optString("message").orEmpty()
                            if (msg.contains("expired", true)) webSocket.close(1000, "refresh token")
                        }
                        "phx_error", "phx_close" -> webSocket.close(1000, "reconnect")
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                heartbeatJob?.cancel()
                reconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                heartbeatJob?.cancel()
                reconnect()
            }
        })
    }

    private fun reconnect() {
        if (stopped || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(2000)
            if (!stopped) connect()
        }
    }

    fun stop() {
        stopped = true
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        socket?.close(1000, "closed")
        socket = null
    }
}
