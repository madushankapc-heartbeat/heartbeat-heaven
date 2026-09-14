package com.heartbeatheaven.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit

private const val FRIENDS_SUPABASE_URL = "https://fafvhyeesenpimxncupp.supabase.co"
private const val FRIENDS_KEY = "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"

private data class FriendUser(val id: String, val username: String, val gender: String)
private data class FriendRequest(val id: String, val user: FriendUser, val incoming: Boolean)
private data class ChatMessage(val id: String, val senderId: String, val body: String, val createdAt: String)

private class FriendsApi(private val auth: AuthApi, initialSession: AuthSession) {
    private var session = initialSession
    fun token(): String = session.accessToken
    fun userId(): String = session.profile.id

    private fun request(path: String, method: String, body: String? = null): String {
        fun doRequest(): Pair<Int, String> {
            val c = URL(FRIENDS_SUPABASE_URL + path).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 20000
                c.setRequestProperty("apikey", FRIENDS_KEY); c.setRequestProperty("Authorization", "Bearer ${session.accessToken}"); c.setRequestProperty("Accept", "application/json")
                if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
                val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                return c.responseCode to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally { c.disconnect() }
        }

        auth.currentSession()?.let { session = it }
        var (code, text) = doRequest()
        if (code == 401) {
            session = auth.currentSession() ?: throw IllegalStateException("Your session has expired. Please log in again.")
            val retry = doRequest(); code = retry.first; text = retry.second
        }
        if (code !in 200..299) {
            val detail = runCatching { JSONObject(text).optString("message").ifBlank { JSONObject(text).optString("msg") }.ifBlank { JSONObject(text).optString("error") } }.getOrDefault("")
            throw IllegalStateException(if (detail.isBlank()) "Request failed ($code)" else detail)
        }
        return text
    }

    suspend fun touchPresence() = withContext(Dispatchers.IO) { request("/rest/v1/profiles?id=eq.${userId()}&select=id", "PATCH", JSONObject().put("last_seen_at", Instant.now().toString()).toString()) }

    suspend fun onlineUsers(): List<FriendUser> = withContext(Dispatchers.IO) {
        val since = Instant.now().minus(2, ChronoUnit.MINUTES).toString()
        val a = JSONArray(request("/rest/v1/profiles?last_seen_at=gte.${URLEncoder.encode(since, "UTF-8")}&select=id,username,gender&limit=50", "GET"))
        buildList { for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("id") != userId()) add(FriendUser(o.optString("id"), o.optString("username"), o.optString("gender"))) } }
    }

    suspend fun search(username: String): List<FriendUser> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(username.trim(), "UTF-8")
        val a = JSONArray(request("/rest/v1/profiles?username=ilike.*$q*&select=id,username,gender&limit=20", "GET"))
        buildList { for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("id") != userId()) add(FriendUser(o.optString("id"), o.optString("username"), o.optString("gender"))) } }
    }

    suspend fun requests(): List<FriendRequest> = withContext(Dispatchers.IO) {
        val mine = userId(); val a = JSONArray(request("/rest/v1/friendships?or=(requester_id.eq.$mine,addressee_id.eq.$mine)&status=eq.pending&select=id,requester_id,addressee_id", "GET"))
        buildList { for (i in 0 until a.length()) { val o = a.getJSONObject(i); val incoming = o.optString("addressee_id") == mine; val uid = if (incoming) o.optString("requester_id") else o.optString("addressee_id"); val p = JSONArray(request("/rest/v1/profiles?id=eq.$uid&select=id,username,gender", "GET")); if (p.length() > 0) { val u = p.getJSONObject(0); add(FriendRequest(o.optString("id"), FriendUser(uid, u.optString("username"), u.optString("gender")), incoming)) } } }
    }

    suspend fun send(userId: String): String = withContext(Dispatchers.IO) {
        val mine = userId()
        val existing = JSONArray(request("/rest/v1/friendships?or=(and(requester_id.eq.$mine,addressee_id.eq.$userId),and(requester_id.eq.$userId,addressee_id.eq.$mine))&select=id,status,requester_id,addressee_id", "GET"))
        if (existing.length() > 0) {
            val row = existing.getJSONObject(0); val status = row.optString("status"); val id = row.optString("id")
            when (status) {
                "accepted" -> return@withContext "Already friends."
                "pending" -> return@withContext "Friend request already pending."
                "rejected" -> { request("/rest/v1/friendships?id=eq.$id", "PATCH", JSONObject().put("requester_id", mine).put("addressee_id", userId).put("status", "pending").toString()); return@withContext "Friend request sent." }
                "blocked" -> return@withContext "This friendship is blocked."
            }
        }
        request("/rest/v1/friendships", "POST", JSONObject().put("requester_id", mine).put("addressee_id", userId).toString()); "Friend request sent."
    }

    suspend fun accept(id: String) = withContext(Dispatchers.IO) { request("/rest/v1/friendships?id=eq.$id", "PATCH", JSONObject().put("status", "accepted").toString()) }

    suspend fun friends(): List<FriendUser> = withContext(Dispatchers.IO) {
        val mine = userId(); val a = JSONArray(request("/rest/v1/friendships?or=(requester_id.eq.$mine,addressee_id.eq.$mine)&status=eq.accepted&select=requester_id,addressee_id", "GET"))
        buildList { for (i in 0 until a.length()) { val o = a.getJSONObject(i); val uid = if (o.optString("requester_id") == mine) o.optString("addressee_id") else o.optString("requester_id"); val p = JSONArray(request("/rest/v1/profiles?id=eq.$uid&select=id,username,gender", "GET")); if (p.length() > 0) { val u = p.getJSONObject(0); add(FriendUser(uid, u.optString("username"), u.optString("gender"))) } } }
    }

    suspend fun messages(other: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val mine = userId(); val a = JSONArray(request("/rest/v1/messages?or=(and(sender_id.eq.$mine,receiver_id.eq.$other),and(sender_id.eq.$other,receiver_id.eq.$mine))&order=created_at.asc&limit=100", "GET"))
        buildList { for (i in 0 until a.length()) { val o = a.getJSONObject(i); add(ChatMessage(o.optString("id"), o.optString("sender_id"), o.optString("body"), o.optString("created_at"))) } }
    }

    suspend fun sendMessage(other: String, body: String) = withContext(Dispatchers.IO) { request("/rest/v1/messages", "POST", JSONObject().put("sender_id", userId()).put("receiver_id", other).put("body", body.trim()).toString()) }
}

@Composable
internal fun FriendsScreen() {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val auth = remember { AuthApi(context) }
    var session by remember { mutableStateOf<AuthSession?>(null) }; var checkingSession by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }; var results by remember { mutableStateOf<List<FriendUser>>(emptyList()) }; var online by remember { mutableStateOf<List<FriendUser>>(emptyList()) }
    var friends by remember { mutableStateOf<List<FriendUser>>(emptyList()) }; var requests by remember { mutableStateOf<List<FriendRequest>>(emptyList()) }; var selected by remember { mutableStateOf<FriendUser?>(null) }; var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }; var text by remember { mutableStateOf("") }; var message by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { session = withContext(Dispatchers.IO) { auth.currentSession() }; checkingSession = false }
    val api = session?.let { remember(it.accessToken) { FriendsApi(auth, it) } }

    fun reload() {
        val a = api ?: return; busy = true
        scope.launch { runCatching { withContext(Dispatchers.IO) { a.touchPresence(); Triple(a.friends(), a.requests(), a.onlineUsers()) } }.onSuccess { (f, r, o) -> friends = f; requests = r; online = o; session = auth.currentSession() }.onFailure { message = it.message ?: "Could not load Friends." }; busy = false }
    }

    LaunchedEffect(session?.accessToken) { if (session != null) reload() }
    LaunchedEffect(api) { if (api != null) while (true) { runCatching { api.touchPresence(); online = api.onlineUsers() }; delay(30000) } }
    LaunchedEffect(selected?.id, api) { selected?.let { u -> api?.let { messages = runCatching { it.messages(u.id) }.getOrElse { emptyList() } } } }

    DisposableEffect(api, selected?.id) {
        val selectedId = selected?.id
        val realtime = api?.let { currentApi -> RealtimeMessagesClient({ currentApi.token() }, currentApi.userId(), FRIENDS_KEY) { id, senderId, body, createdAt -> scope.launch(Dispatchers.Main) { if (selectedId != null && selected?.id == selectedId && senderId == selectedId && messages.none { it.id == id }) messages = messages + ChatMessage(id, senderId, body, createdAt) } } }
        realtime?.start(); onDispose { realtime?.stop() }
    }

    if (checkingSession) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    if (session == null || api == null) { Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Spacer(Modifier.height(60.dp)); Icon(Icons.Default.Lock, null, Modifier.size(48.dp)); Text("Login required", style = MaterialTheme.typography.headlineSmall); Text("Please log in from the Profile tab first.", color = MaterialTheme.colorScheme.onSurfaceVariant) }; return }

    if (selected != null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(selected!!.username, style = MaterialTheme.typography.headlineSmall); TextButton(onClick = { selected = null; messages = emptyList() }) { Text("Back") } }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(messages) { m -> Card(Modifier.fillMaxWidth()) { Text(m.body, Modifier.padding(12.dp)) } } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(text, { text = it }, Modifier.weight(1f), label = { Text("Message") }, singleLine = true); IconButton(enabled = text.isNotBlank(), onClick = { val t = text; text = ""; scope.launch { runCatching { api.sendMessage(selected!!.id, t); messages = api.messages(selected!!.id) }.onFailure { message = it.message } } }) { Icon(Icons.Default.Send, "Send") } }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Friends", style = MaterialTheme.typography.headlineMedium); Text("Online people and accepted friends are shown here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (online.isNotEmpty()) { Text("Online now", style = MaterialTheme.typography.titleMedium); online.forEach { u -> ListItem(headlineContent = { Text(u.username) }, supportingContent = { Text("Online") }, trailingContent = { Button(onClick = { scope.launch { runCatching { message = api.send(u.id); reload() }.onFailure { message = it.message } } }) { Text("Add") } }) } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Search username") }, singleLine = true); Button(enabled = query.isNotBlank(), onClick = { scope.launch { results = runCatching { api.search(query) }.getOrElse { message = it.message; emptyList() } } }) { Text("Search") } }
        if (results.isNotEmpty()) { Text("Search results", style = MaterialTheme.typography.titleMedium); results.forEach { u -> ListItem(headlineContent = { Text(u.username) }, trailingContent = { Button(onClick = { scope.launch { runCatching { message = api.send(u.id); reload() }.onFailure { message = it.message } } }) { Text("Add") } }) } }
        if (requests.isNotEmpty()) { Text("Friend requests", style = MaterialTheme.typography.titleMedium); requests.forEach { r -> ListItem(headlineContent = { Text(r.user.username) }, trailingContent = { if (r.incoming) Button(onClick = { scope.launch { runCatching { api.accept(r.id); reload() }.onFailure { message = it.message } } }) { Text("Accept") } else Text("Pending") }) } }
        Text("Friends", style = MaterialTheme.typography.titleMedium)
        if (friends.isEmpty()) Text("No friends yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        friends.forEach { u -> ListItem(headlineContent = { Text(u.username) }, leadingContent = { Icon(Icons.Default.Person, "Friend") }, trailingContent = { TextButton(onClick = { selected = u }) { Text("Chat") } }) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }; if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
