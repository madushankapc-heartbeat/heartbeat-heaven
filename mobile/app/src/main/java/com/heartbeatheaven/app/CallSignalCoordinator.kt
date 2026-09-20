package com.heartbeatheaven.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * New call-session coordinator.
 *
 * Realtime is the primary delivery path. A REST snapshot is taken at start
 * and after every successful realtime reconnect so a socket gap cannot leave
 * the call in a stale state.
 */
internal class CallSignalCoordinator(
    context: Context,
    private val auth: AuthApi,
    private val callApi: CallApi,
    private val callId: String,
    private val onSession: (CallSession) -> Unit,
    private val onTransport: (Boolean) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val userId = auth.currentSession()?.profile?.id.orEmpty()
    private val key = "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"
    private var snapshotJob: Job? = null

    private val realtime = CallRealtimeClient(
        apiKey = key,
        userId = userId,
        accessTokenProvider = { auth.currentSession()?.accessToken.orEmpty() },
        onCallChange = { _, record ->
            if (record.optString("id") == callId) {
                runCatching { callApi.parse(record) }.onSuccess { onSession(it) }
            }
        },
        onTransportState = { connected ->
            onTransport(connected)
            if (connected) refreshSnapshot()
        }
    )

    fun start() {
        refreshSnapshot()
        realtime.start()
    }

    fun stop() {
        snapshotJob?.cancel()
        realtime.stop()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun refreshSnapshot() {
        if (snapshotJob?.isActive == true) return
        snapshotJob = scope.launch {
            runCatching {
                val session = callApi.get(callId) ?: return@runCatching
                withContext(Dispatchers.Main.immediate) { onSession(session) }
            }
        }
    }
}
