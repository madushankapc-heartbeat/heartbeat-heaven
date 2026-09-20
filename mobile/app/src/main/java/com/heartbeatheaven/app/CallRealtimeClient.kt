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
import kotlin.math.min

/**
 * Realtime transport for the new call system.
 *
 * This is deliberately independent from the legacy polling implementation.
 * It provides:
 * - authenticated Supabase Realtime subscription
 * - websocket heartbeats
 * - bounded exponential reconnect
 * - token refresh on reconnect
 * - participant-only filtering
 *
 * It does not start/stop a call by itself; the call state machine owns that.
 */
internal class CallRealtimeClient(
    private val apiKey: String,
    private val userId: String,
    private val accessTokenProvider: () -> String,
    private val onCallChange: (eventType: String, record: JSONObject) -> Unit,
    private val onTransportState: (connected: Boolean) -> Unit = {}
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var stopped = true
    private var ref = 0
    private var joinRef = "0"
    private var reconnectAttempt = 0

    private val topic: String
        get() = "realtime:call-sessions-$userId"

    fun start() {
        if (!stopped) return
        stopped = false
        reconnectAttempt = 0
        connect()
    }

    fun stop() {
        stopped = true
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        heartbeatJob = null
        reconnectJob = null
        socket?.close(1000, "call signaling stopped")
        socket = null
        onTransportState(false)
    }

    private fun nextRef(): String = (++ref).toString()

    private fun connect() {
        if (stopped) return

        val token = accessTokenProvider()
        if (token.isBlank()) {
            scheduleReconnect()
            return
        }

        val currentJoinRef = nextRef()
        joinRef = currentJoinRef

        val url = "wss://fafvhyeesenpimxncupp.supabase.co/realtime/v1/websocket" +
            "?apikey=$apiKey&vsn=1.0.0"

        socket = client.newWebSocket(
            Request.Builder()
                .url(url)
                .build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (stopped) {
                        webSocket.close(1000, "stopped")
                        return
                    }

                    reconnectAttempt = 0

                    val changes = JSONObject()
                        .put("event", "INSERT")
                        .put("schema", "public")
                        .put("table", "call_sessions")
                        .put("filter", "callee_id=eq.$userId")
                        .let { JSONArrayBuilder().add(it) }
                        .put(
                            JSONObject()
                                .put("event", "UPDATE")
                                .put("schema", "public")
                                .put("table", "call_sessions")
                                .put("filter", "caller_id=eq.$userId")
                        )
                        .put(
                            JSONObject()
                                .put("event", "UPDATE")
                                .put("schema", "public")
                                .put("table", "call_sessions")
                                .put("filter", "callee_id=eq.$userId")
                        )
                        .toJsonArray()

                    val payload = JSONObject()
                        .put("config", JSONObject()
                            .put("broadcast", JSONObject().put("ack", false).put("self", false))
                            .put("presence", JSONObject().put("enabled", false).put("key", userId))
                            .put("postgres_changes", changes)
                        )
                        .put("access_token", token)

                    webSocket.send(
                        JSONObject()
                            .put("topic", topic)
                            .put("event", "phx_join")
                            .put("payload", payload)
                            .put("ref", currentJoinRef)
                            .put("join_ref", currentJoinRef)
                            .toString()
                    )

                    onTransportState(true)

                    heartbeatJob?.cancel()
                    heartbeatJob = scope.launch {
                        while (isActive && !stopped) {
                            delay(15_000)
                            val currentToken = accessTokenProvider()
                            webSocket.send(
                                JSONObject()
                                    .put("topic", topic)
                                    .put("event", "access_token")
                                    .put("payload", JSONObject().put("access_token", currentToken))
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
                                val type = data.optString("type")
                                    .ifBlank { payload.optString("eventType") }
                                    .ifBlank { "UPDATE" }
                                val record = data.optJSONObject("record")
                                    ?: data.optJSONObject("old_record")
                                    ?: payload.optJSONObject("old_record")
                                    ?: return@runCatching

                                val caller = record.optString("caller_id")
                                val callee = record.optString("callee_id")
                                if (caller == userId || callee == userId) {
                                    onCallChange(type, JSONObject(record.toString()))
                                }
                            }

                            "phx_error", "phx_close" -> {
                                webSocket.close(1000, "reconnect")
                            }

                            "system" -> {
                                val message = root.optJSONObject("payload")
                                    ?.optString("message")
                                    .orEmpty()
                                if (message.contains("expired", ignoreCase = true)) {
                                    webSocket.close(4001, "token expired")
                                }
                            }
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    heartbeatJob?.cancel()
                    onTransportState(false)
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    heartbeatJob?.cancel()
                    onTransportState(false)
                    scheduleReconnect()
                }
            }
        )
    }

    private fun scheduleReconnect() {
        if (stopped || reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            val attempt = reconnectAttempt++
            val delayMs = min(30_000L, 1_000L * (1L shl min(attempt, 5)))
            delay(delayMs)
            if (!stopped) connect()
        }
    }

    /**
     * Tiny JSONArray builder avoids exposing mutable JSON arrays outside this transport.
     */
    private class JSONArrayBuilder {
        private val array = org.json.JSONArray()
        fun add(value: JSONObject): JSONArrayBuilder {
            array.put(value)
            return this
        }
        fun put(value: JSONObject): JSONArrayBuilder {
            array.put(value)
            return this
        }
        fun toJsonArray(): org.json.JSONArray = array
    }
}
