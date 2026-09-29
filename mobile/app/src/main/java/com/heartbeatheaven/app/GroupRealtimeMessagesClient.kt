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

/**
 * Realtime INSERT listener for one group_messages stream.
 * The access token is sent to Supabase Realtime so existing Postgres RLS
 * controls which group rows this authenticated member can receive.
 */
class GroupRealtimeMessagesClient(
    private val accessTokenProvider: () -> String,
    private val apiKey: String,
    private val groupId: String,
    private val onChange: (eventType: String, record: JSONObject) -> Unit
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
        val topic = "realtime:group-messages-$groupId"
        val url = "wss://fafvhyeesenpimxncupp.supabase.co/realtime/v1/websocket?apikey=$apiKey&vsn=1.0.0"
        joinRef = nextRef()

        socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val changes = JSONArray()
                        .put(
                            JSONObject()
                                .put("event", "*")
                                .put("schema", "public")
                                .put("table", "group_messages")
                                .put("filter", "group_id=eq.$groupId")
                        )
                        .put(
                            JSONObject()
                                .put("event", "*")
                                .put("schema", "public")
                                .put("table", "group_message_reactions")
                        )

                    val payload = JSONObject()
                        .put(
                            "config",
                            JSONObject()
                                .put("broadcast", JSONObject().put("ack", false).put("self", false))
                                .put("presence", JSONObject().put("enabled", false))
                                .put("postgres_changes", changes)
                        )
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
                                val eventType = data.optString("type")
                                    .ifBlank { payload.optString("eventType") }
                                    .ifBlank { "INSERT" }
                                val record = data.optJSONObject("record")
                                    ?: return@runCatching
                                val recordGroupId = record.optString("group_id")
                                val isReaction = data.optString("table") == "group_message_reactions" || record.has("group_message_id")
                                if (recordGroupId == groupId || isReaction) {
                                    onChange(eventType, record)
                                }
                            }
                            "system" -> {
                                val msg = root.optJSONObject("payload")?.optString("message").orEmpty()
                                if (msg.contains("expired", true)) {
                                    webSocket.close(1000, "refresh token")
                                }
                            }
                            "phx_error", "phx_close" -> {
                                webSocket.close(1000, "reconnect")
                            }
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
            }
        )
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
