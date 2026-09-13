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
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RealtimeMessagesClient(
    private val accessToken: String,
    private val userId: String,
    private val onMessage: (ChatMessage) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var ref = 0
    private var stopped = false

    fun start() {
        stopped = false
        connect()
    }

    private fun nextRef(): String = (++ref).toString()

    private fun connect() {
        if (stopped) return
        val url = "wss://fafvhyeesenpimxncupp.supabase.co/realtime/v1/websocket?apikey=sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs&vsn=1.0.0"
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val joinRef = nextRef()
                val payload = JSONObject()
                    .put("config", JSONObject()
                        .put("broadcast", JSONObject().put("ack", false).put("self", false))
                        .put("presence", JSONObject().put("key", ""))
                        .put("postgres_changes", org.json.JSONArray()
                            .put(JSONObject()
                                .put("event", "INSERT")
                                .put("schema", "public")
                                .put("table", "messages")
                                .put("filter", "receiver_id=eq.$userId"))))
                    .put("access_token", accessToken)
                webSocket.send(JSONObject()
                    .put("topic", "realtime:public:messages")
                    .put("event", "phx_join")
                    .put("payload", payload)
                    .put("ref", joinRef).toString())
                heartbeatJob?.cancel()
                heartbeatJob = scope.launch {
                    while (isActive && !stopped) {
                        delay(25000)
                        socket?.send(JSONObject()
                            .put("topic", "phoenix")
                            .put("event", "heartbeat")
                            .put("payload", JSONObject())
                            .put("ref", nextRef()).toString())
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = JSONObject(text)
                    if (root.optString("event") != "postgres_changes") return@runCatching
                    val payload = root.optJSONObject("payload") ?: return@runCatching
                    val record = payload.optJSONObject("record") ?: return@runCatching
                    onMessage(ChatMessage(
                        record.optString("id"),
                        record.optString("sender_id"),
                        record.optString("body"),
                        record.optString("created_at")
                    ))
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
        if (stopped) return
        scope.launch {
            delay(2000)
            if (!stopped) connect()
        }
    }

    fun stop() {
        stopped = true
        heartbeatJob?.cancel()
        socket?.close(1000, "closed")
        socket = null
        client.dispatcher.executorService.shutdown()
    }
}
