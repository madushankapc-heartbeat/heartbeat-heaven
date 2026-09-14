package com.heartbeatheaven.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
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
                c.requestMethod = method
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("apikey", FRIENDS_KEY)
                c.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
                c.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                return c.responseCode to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally { c.disconnect() }
        }
        auth.currentSession()?.let { session = it }
        var (code, text) = doRequest()
        if (code == 401) {
            session = auth.currentSession() ?: throw IllegalStateException("Your session has expired. Please log in again.")
            val retry = doRequest()
            code = retry.first
            text = retry.second
        }
        if (code !in 200..299) {
            val detail = runCatching {
                JSONObject(text).optString("message")
                    .ifBlank { JSONObject(text).optString("msg") }
                    .ifBlank { JSONObject(text).optString("error") }
            }.getOrDefault("")
            throw IllegalStateException(if (detail.isBlank()) "Request failed ($code)" else detail)
        }
        return text
    }

    suspend fun touchPresence() = withContext(Dispatchers.IO) {
        request("/rest/v1/profiles?id=eq.${userId()}&select=id", "PATCH", JSONObject().put("last_seen_at", Instant.now().toString()).toString())
    }

    suspend fun onlineUsers(): List<FriendUser> = withContext(Dispatchers.IO) {
        val since = Instant.now().minus(2, ChronoUnit.MINUTES).toString()
        val encoded = URLEncoder.encode(since, "UTF-8")
        val a = JSONArray(request("/rest/v1/profiles?last_seen_at=gte.$encoded&select=id,username,gender&limit=50", "GET"))
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optString("id") != userId()) add(FriendUser(o.optString("id"), o.optString("username"), o.optString("gender")))
            }
        }
    }

    suspend fun search(username: String): List<FriendUser> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(username.trim(), "UTF-8")
        val a = JSONArray(request("/rest/v1/profiles?username=ilike.*$q*&select=id,username,gender&limit=20", "GET"))
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optString("id") != userId()) add(FriendUser(o.optString("id"), o.optString("username"), o.optString("gender")))
            }
        }
    }

    suspend fun requests(): List<FriendRequest> = withContext(Dispatchers.IO) {
        val mine = userId()
        val a = JSONArray(request("/rest/v1/friendships?or=(requester_id.eq.$mine,addressee_id.eq.$mine)&status=eq.pending&select=id,requester_id,addressee_id", "GET"))
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val incoming = o.optString("addressee_id") == mine
                val uid = if (incoming) o.optString("requester_id") else o.optString("addressee_id")
                val p = JSONArray(request("/rest/v1/profiles?id=eq.$uid&select=id,username,gender", "GET"))
                if (p.length() > 0) {
                    val u = p.getJSONObject(0)
                    add(FriendRequest(o.optString("id"), FriendUser(uid, u.optString("username"), u.optString("gender")), incoming))
                }
            }
        }
    }

    suspend fun send(targetUserId: String): String = withContext(Dispatchers.IO) {
        val mine = userId()
        val existing = JSONArray(request("/rest/v1/friendships?or=(and(requester_id.eq.$mine,addressee_id.eq.$targetUserId),and(requester_id.eq.$targetUserId,addressee_id.eq.$mine))&select=id,status,requester_id,addressee_id", "GET"))
        if (existing.length() > 0) {
            val row = existing.getJSONObject(0)
            when (row.optString("status")) {
                "accepted" -> return@withContext "Already friends."
                "pending" -> return@withContext "Friend request already pending."
                "rejected" -> {
                    request("/rest/v1/friendships?id=eq.${row.optString("id")}", "PATCH", JSONObject().put("requester_id", mine).put("addressee_id", targetUserId).put("status", "pending").toString())
                    return@withContext "Friend request sent."
                }
                "blocked" -> return@withContext "This friendship is blocked."
            }
        }
        request("/rest/v1/friendships", "POST", JSONObject().put("requester_id", mine).put("addressee_id", targetUserId).toString())
        "Friend request sent."
    }

    suspend fun accept(id: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/friendships?id=eq.$id", "PATCH", JSONObject().put("status", "accepted").toString())
    }

    suspend fun friends(): List<FriendUser> = withContext(Dispatchers.IO) {
        val mine = userId()
        val a = JSONArray(request("/rest/v1/friendships?or=(requester_id.eq.$mine,addressee_id.eq.$mine)&status=eq.accepted&select=requester_id,addressee_id", "GET"))
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val uid = if (o.optString("requester_id") == mine) o.optString("addressee_id") else o.optString("requester_id")
                val p = JSONArray(request("/rest/v1/profiles?id=eq.$uid&select=id,username,gender", "GET"))
                if (p.length() > 0) {
                    val u = p.getJSONObject(0)
                    add(FriendUser(uid, u.optString("username"), u.optString("gender")))
                }
            }
        }
    }

    suspend fun messages(other: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val mine = userId()
        val a = JSONArray(request("/rest/v1/messages?or=(and(sender_id.eq.$mine,receiver_id.eq.$other),and(sender_id.eq.$other,receiver_id.eq.$mine))&order=created_at.asc&limit=100", "GET"))
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(ChatMessage(o.optString("id"), o.optString("sender_id"), o.optString("body"), o.optString("created_at")))
            }
        }
    }

    suspend fun sendMessage(other: String, body: String): ChatMessage? = withContext(Dispatchers.IO) {
        val clean = body.trim()
        if (clean.isBlank()) return@withContext null
        val response = request("/rest/v1/messages", "POST", JSONObject().put("sender_id", userId()).put("receiver_id", other).put("body", clean).toString())
        runCatching {
            val a = JSONArray(response)
            if (a.length() > 0) {
                val o = a.getJSONObject(0)
                ChatMessage(o.optString("id"), o.optString("sender_id"), o.optString("body"), o.optString("created_at"))
            } else null
        }.getOrNull()
    }
}

@Composable
internal fun FriendsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { AuthApi(context) }
    var session by remember { mutableStateOf<AuthSession?>(null) }
    var checkingSession by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FriendUser>>(emptyList()) }
    var online by remember { mutableStateOf<List<FriendUser>>(emptyList()) }
    var friends by remember { mutableStateOf<List<FriendUser>>(emptyList()) }
    var requests by remember { mutableStateOf<List<FriendRequest>>(emptyList()) }
    var selected by remember { mutableStateOf<FriendUser?>(null) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        session = withContext(Dispatchers.IO) { auth.currentSession() }
        checkingSession = false
    }

    val api = session?.let { remember(it.accessToken) { FriendsApi(auth, it) } }

    fun reload() {
        val a = api ?: return
        busy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    a.touchPresence()
                    Triple(a.friends(), a.requests(), a.onlineUsers())
                }
            }.onSuccess { (f, r, o) ->
                friends = f
                requests = r
                online = o
            }.onFailure { statusMessage = it.message ?: "Could not load Friends." }
            busy = false
        }
    }

    LaunchedEffect(session?.accessToken) { if (session != null) reload() }

    LaunchedEffect(api) {
        if (api != null) while (true) {
            runCatching {
                api.touchPresence()
                online = api.onlineUsers()
                friends = api.friends()
            }
            delay(30000)
        }
    }

    LaunchedEffect(selected?.id, api) {
        val current = selected ?: return@LaunchedEffect
        val a = api ?: return@LaunchedEffect
        while (true) {
            runCatching {
                val fresh = a.messages(current.id)
                if (fresh != messages) messages = fresh
            }
            delay(1500)
        }
    }

    DisposableEffect(api, selected?.id) {
        val selectedId = selected?.id
        val realtime = api?.let { currentApi ->
            RealtimeMessagesClient(
                { currentApi.token() },
                currentApi.userId(),
                FRIENDS_KEY
            ) { id, senderId, body, createdAt ->
                scope.launch(Dispatchers.Main) {
                    if (selectedId != null && selected?.id == selectedId && senderId == selectedId && messages.none { it.id == id }) {
                        messages = messages + ChatMessage(id, senderId, body, createdAt)
                    }
                }
            }
        }
        realtime?.start()
        onDispose { realtime?.stop() }
    }

    if (checkingSession) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    if (session == null || api == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(60.dp))
            Icon(Icons.Default.Lock, null, Modifier.size(48.dp))
            Text("Login required", style = MaterialTheme.typography.headlineSmall)
            Text("Please log in from the Profile tab first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    if (selected != null) {
        val chat = selected!!
        val listState = rememberLazyListState()
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        }

        Column(Modifier.fillMaxSize()) {
            Surface(shadowElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selected = null; messages = emptyList(); text = "" }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(chat.username, style = MaterialTheme.typography.titleLarge)
                        val isOnline = online.any { it.id == chat.id }
                        Text(if (isOnline) "Online" else "Offline", style = MaterialTheme.typography.bodySmall, color = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.Person, "Profile", Modifier.padding(end = 8.dp))
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(messages, key = { it.id }) { m ->
                    val mine = m.senderId == api.userId()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                            Surface(
                                color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(m.body, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), color = MaterialTheme.colorScheme.onSurface)
                            }
                            Text(
                                if (mine) "You  ${m.createdAt.takeLast(14).take(5)}" else "${chat.username}  ${m.createdAt.takeLast(14).take(5)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            statusMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }

            Surface(tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; statusMessage = null },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        maxLines = 4
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        enabled = text.isNotBlank(),
                        onClick = {
                            val outgoing = text.trim()
                            text = ""
                            statusMessage = null
                            val optimistic = ChatMessage("local-${System.nanoTime()}", api.userId(), outgoing, "")
                            messages = messages + optimistic
                            scope.launch {
                                runCatching {
                                    val saved = api.sendMessage(chat.id, outgoing)
                                    val fresh = api.messages(chat.id)
                                    messages = if (saved != null) fresh else fresh
                                }.onFailure {
                                    messages = messages.filterNot { it.id == optimistic.id }
                                    statusMessage = it.message ?: "Message could not be sent."
                                }
                            }
                        }
                    ) { Icon(Icons.Default.Send, "Send") }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Friends", style = MaterialTheme.typography.headlineMedium)
        Text("Online people and accepted friends are shown here.", color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (online.isNotEmpty()) {
            Text("Online now", style = MaterialTheme.typography.titleMedium)
            online.forEach { u ->
                ListItem(
                    headlineContent = { Text(u.username) },
                    supportingContent = { Text("Online") },
                    leadingContent = { Icon(Icons.Default.Circle, "Online", tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Button(onClick = { scope.launch { runCatching { statusMessage = api.send(u.id); reload() }.onFailure { statusMessage = it.message } } }) { Text("Add") }
                    }
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Search username") }, singleLine = true)
            Button(enabled = query.isNotBlank(), onClick = {
                scope.launch { results = runCatching { api.search(query) }.getOrElse { statusMessage = it.message; emptyList() } }
            }) { Text("Search") }
        }

        if (results.isNotEmpty()) {
            Text("Search results", style = MaterialTheme.typography.titleMedium)
            results.forEach { u ->
                ListItem(
                    headlineContent = { Text(u.username) },
                    leadingContent = { Icon(Icons.Default.PersonAdd, "Add friend") },
                    trailingContent = {
                        Button(onClick = { scope.launch { runCatching { statusMessage = api.send(u.id); reload() }.onFailure { statusMessage = it.message } } }) { Text("Add") }
                    }
                )
            }
        }

        if (requests.isNotEmpty()) {
            Text("Friend requests", style = MaterialTheme.typography.titleMedium)
            requests.forEach { r ->
                ListItem(
                    headlineContent = { Text(r.user.username) },
                    supportingContent = { Text(if (r.incoming) "Wants to be your friend" else "Pending") },
                    trailingContent = { if (r.incoming) Button(onClick = { scope.launch { runCatching { api.accept(r.id); reload() }.onFailure { statusMessage = it.message } } }) { Text("Accept") } else Text("Pending") }
                )
            }
        }

        Text("Friends", style = MaterialTheme.typography.titleMedium)
        if (friends.isEmpty()) Text("No friends yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        friends.forEach { u ->
            ListItem(
                headlineContent = { Text(u.username) },
                supportingContent = { Text(if (online.any { it.id == u.id }) "Online" else "Offline") },
                leadingContent = { Icon(Icons.Default.Person, "Friend") },
                trailingContent = { FilledTonalButton(onClick = { selected = u; statusMessage = null }) { Text("Chat") } }
            )
        }

        statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
