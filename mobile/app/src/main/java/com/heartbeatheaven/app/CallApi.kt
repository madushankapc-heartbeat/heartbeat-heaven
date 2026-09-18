package com.heartbeatheaven.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class CallSession(
    val id: String, val callerId: String, val calleeId: String, val callType: String, val status: String,
    val offerSdp: String?, val answerSdp: String?, val callerIce: JSONArray, val calleeIce: JSONArray,
    val startedAt: String?, val endedAt: String?
)

internal class CallApi(private val context: Context, private val auth: AuthApi) {
    private val base = "https://fafvhyeesenpimxncupp.supabase.co"
    private val key = "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"

    private fun request(path: String, method: String, body: String? = null): String {
        fun once(token: String): Pair<Int,String> {
            val c = URL(base + path).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 12000
                c.readTimeout = 18000
                c.setRequestProperty("apikey", key)
                c.setRequestProperty("Authorization", "Bearer " + token)
                c.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                return c.responseCode to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally { c.disconnect() }
        }
        val session = auth.currentSession() ?: error("Login required")
        var pair = once(session.accessToken)
        if (pair.first == 401) pair = once((auth.currentSession() ?: error("Session expired")).accessToken)
        if (pair.first !in 200..299) error("Call request failed (" + pair.first + ")")
        return pair.second
    }

    private fun parse(o: JSONObject): CallSession = CallSession(
        o.optString("id"), o.optString("caller_id"), o.optString("callee_id"), o.optString("call_type"),
        o.optString("status"), o.optString("offer_sdp").takeUnless { it == "null" || it.isBlank() },
        o.optString("answer_sdp").takeUnless { it == "null" || it.isBlank() },
        o.optJSONArray("caller_ice") ?: JSONArray(), o.optJSONArray("callee_ice") ?: JSONArray(),
        o.optString("started_at").takeUnless { it == "null" || it.isBlank() },
        o.optString("ended_at").takeUnless { it == "null" || it.isBlank() }
    )

    suspend fun create(calleeId: String, type: String): CallSession = withContext(Dispatchers.IO) {
        parse(JSONObject(request("/rest/v1/rpc/create_call", "POST", JSONObject().put("p_callee_id", calleeId).put("p_call_type", type).toString())))
    }
    suspend fun get(id: String): CallSession? = withContext(Dispatchers.IO) {
        val a = JSONArray(request("/rest/v1/call_sessions?id=eq." + id + "&select=*&limit=1", "GET"))
        if (a.length() == 0) null else parse(a.getJSONObject(0))
    }
    suspend fun updateStatus(id: String, status: String): CallSession = withContext(Dispatchers.IO) {
        parse(JSONObject(request("/rest/v1/rpc/update_call_status", "POST", JSONObject().put("p_call_id", id).put("p_status", status).toString())))
    }
    fun updateStatusBlocking(id: String, status: String): CallSession =
        parse(JSONObject(request("/rest/v1/rpc/update_call_status", "POST", JSONObject().put("p_call_id", id).put("p_status", status).toString())))
    suspend fun publishOffer(id: String, sdp: String) = patch(id, JSONObject().put("offer_sdp", sdp))
    suspend fun publishAnswer(id: String, sdp: String) = patch(id, JSONObject().put("answer_sdp", sdp))
    suspend fun addCallerIce(id: String, candidate: JSONObject) = appendIce(id, true, candidate)
    suspend fun addCalleeIce(id: String, candidate: JSONObject) = appendIce(id, false, candidate)
    private suspend fun patch(id: String, body: JSONObject) = withContext(Dispatchers.IO) { request("/rest/v1/call_sessions?id=eq." + id, "PATCH", body.toString()) }
    private suspend fun appendIce(id: String, caller: Boolean, candidate: JSONObject) = withContext(Dispatchers.IO) {
        val current = get(id) ?: return@withContext
        val next = JSONArray((if (caller) current.callerIce else current.calleeIce).toString())
        next.put(candidate)
        patch(id, JSONObject().put(if (caller) "caller_ice" else "callee_ice", next))
    }
}
