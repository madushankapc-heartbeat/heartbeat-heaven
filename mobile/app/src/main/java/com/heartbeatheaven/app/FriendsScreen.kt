@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.heartbeatheaven.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
private const val FRIENDS_KEY = "sb_" + "publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"

private data class FriendUser(val id: String, val username: String, val gender: String)
private data class FriendRequest(val id: String, val user: FriendUser, val incoming: Boolean)
private data class ReactionCount(val reaction: String, val count: Int, val mine: Boolean)
private data class ChatMessage(
    val id: String,
    val senderId: String,
    val body: String,
    val createdAt: String,
    val deliveredAt: String = "",
    val readAt: String = "",
    val editedAt: String = "",
    val replyToId: String = "",
    val deletedAt: String = "",
    val reactions: List<ReactionCount> = emptyList()
)

private class FriendsApi(private val auth: AuthApi, initialSession: AuthSession) {
    private var session = initialSession

    fun token(): String = session.accessToken
    fun userId(): String = session.profile.id

    private fun request(path: String, method: String, body: String? = null): String {
        fun doRequest(): Pair<Int, String> {
            val connection = URL(FRIENDS_SUPABASE_URL + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.setRequestProperty("apikey", FRIENDS_KEY)
                connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val stream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                return connection.responseCode to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally {
                connection.disconnect()
            }
        }

        auth.currentSession()?.let { session = it }
        var (code, text) = doRequest()
        if (code == 401) {
            session = auth.currentSession()
                ?: throw IllegalStateException("Your session has expired. Please log in again.")
            val retry = doRequest()
            code = retry.first
            text = retry.second
        }
        if (code !in 200..299) {
            val detail = runCatching {
                JSONObject(text)
                    .optString("message")
                    .ifBlank { JSONObject(text).optString("msg") }
                    .ifBlank { JSONObject(text).optString("error") }
            }.getOrDefault("")
            throw IllegalStateException(if (detail.isBlank()) "Request failed ($code)" else detail)
        }
        return text
    }

    suspend fun touchPresence() = withContext(Dispatchers.IO) {
        request(
            "/rest/v1/profiles?id=eq.${userId()}&select=id",
            "PATCH",
            JSONObject().put("last_seen_at", Instant.now().toString()).toString()
        )
    }

    suspend fun onlineUsers(): List<FriendUser> = withContext(Dispatchers.IO) {
        val since = URLEncoder.encode(
            Instant.now().minus(2, ChronoUnit.MINUTES).toString(),
            "UTF-8"
        )
        val array = JSONArray(
            request(
                "/rest/v1/profiles?last_seen_at=gte.$since&select=id,username,gender&limit=50",
                "GET"
            )
        )
        buildList {
            for (i in 0 until array.length()) {
                val row = array.getJSONObject(i)
                if (row.optString("id") != userId()) {
                    add(
                        FriendUser(
                            row.optString("id"),
                            row.optString("username"),
                            row.optString("gender")
                        )
                    )
                }
            }
        }
    }

    suspend fun search(username: String): List<FriendUser> = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode(username.trim(), "UTF-8")
        val array = JSONArray(
            request(
                "/rest/v1/profiles?username=ilike.*$query*&select=id,username,gender&limit=20",
                "GET"
            )
        )
        buildList {
            for (i in 0 until array.length()) {
                val row = array.getJSONObject(i)
                if (row.optString("id") != userId()) {
                    add(
                        FriendUser(
                            row.optString("id"),
                            row.optString("username"),
                            row.optString("gender")
                        )
                    )
                }
            }
        }
    }

    suspend fun requests(): List<FriendRequest> = withContext(Dispatchers.IO) {
        val mine = userId()
        val array = JSONArray(
            request(
                "/rest/v1/friendships?or=(requester_id.eq.$mine,addressee_id.eq.$mine)&status=eq.pending&select=id,requester_id,addressee_id",
                "GET"
            )
        )
        buildList {
            for (i in 0 until array.length()) {
                val row = array.getJSONObject(i)
                val incoming = row.optString("addressee_id") == mine
                val otherId = if (incoming) row.optString("requester_id") else row.optString("addressee_id")
                val profile = JSONArray(
                    request("/rest/v1/profiles?id=eq.$otherId&select=id,username,gender", "GET")
                )
                if (profile.length() > 0) {
                    val user = profile.getJSONObject(0)
                    add(
                        FriendRequest(
                            row.optString("id"),
                            FriendUser(otherId, user.optString("username"), user.optString("gender")),
                            incoming
                        )
                    )
                }
            }
        }
    }

    suspend fun send(targetUserId: String): String = withContext(Dispatchers.IO) {
        val mine = userId()
        val existing = JSONArray(
            request(
                "/rest/v1/friendships?or=(and(requester_id.eq.$mine,addressee_id.eq.$targetUserId),and(requester_id.eq.$targetUserId,addressee_id.eq.$mine))&select=id,status,requester_id,addressee_id",
                "GET"
            )
        )
        if (existing.length() > 0) {
            val row = existing.getJSONObject(0)
            when (row.optString("status")) {
                "accepted" -> return@withContext "Already friends."
                "pending" -> return@withContext "Friend request already pending."
                "rejected" -> {
                    request(
                        "/rest/v1/friendships?id=eq.${row.optString("id")}",
                        "PATCH",
                        JSONObject()
                            .put("requester_id", mine)
                            .put("addressee_id", targetUserId)
                            .put("status", "pending")
                            .toString()
                    )
                    return@withContext "Friend request sent."
                }
                "blocked" -> return@withContext "This friendship is blocked."
            }
        }
        request(
            "/rest/v1/friendships",
            "POST",
            JSONObject().put("requester_id", mine).put("addressee_id", targetUserId).toString()
        )
        "Friend request sent."
    }

    suspend fun accept(id: String) = withContext(Dispatchers.IO) {
        request(
            "/rest/v1/friendships?id=eq.$id",
            "PATCH",
            JSONObject().put("status", "accepted").toString()
        )
    }

    suspend fun friends(): List<FriendUser> = withContext(Dispatchers.IO) {
        val mine = userId()
        val array = JSONArray(
            request(
                "/rest/v1/friendships?or=(requester_id.eq.$mine,addressee_id.eq.$mine)&status=eq.accepted&select=requester_id,addressee_id",
                "GET"
            )
        )
        buildList {
            for (i in 0 until array.length()) {
                val row = array.getJSONObject(i)
                val otherId = if (row.optString("requester_id") == mine) {
                    row.optString("addressee_id")
                } else {
                    row.optString("requester_id")
                }
                val profile = JSONArray(
                    request("/rest/v1/profiles?id=eq.$otherId&select=id,username,gender", "GET")
                )
                if (profile.length() > 0) {
                    val user = profile.getJSONObject(0)
                    add(
                        FriendUser(
                            otherId,
                            user.optString("username"),
                            user.optString("gender")
                        )
                    )
                }
            }
        }
    }

    suspend fun messages(other: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val mine = userId()
        val array = JSONArray(
            request(
                "/rest/v1/messages?or=(and(sender_id.eq.$mine,receiver_id.eq.$other),and(sender_id.eq.$other,receiver_id.eq.$mine))&select=id,sender_id,body,created_at,delivered_at,read_at,edited_at,reply_to_id,deleted_at&order=created_at.asc&limit=100",
                "GET"
            )
        )
        val raw = buildList {
            for (i in 0 until array.length()) {
                val row = array.getJSONObject(i)
                add(
                    ChatMessage(
                        row.optString("id"),
                        row.optString("sender_id"),
                        row.optString("body"),
                        row.optString("created_at"),
                        row.optString("delivered_at"),
                        row.optString("read_at"),
                        row.optString("edited_at"),
                        row.optString("reply_to_id"),
                        row.optString("deleted_at")
                    )
                )
            }
        }
        if (raw.isEmpty()) return@withContext emptyList()

        val ids = raw.joinToString(",") { it.id }
        val deleted = runCatching {
            val deletionRows = JSONArray(
                request(
                    "/rest/v1/message_deletions?user_id=eq.${userId()}&message_id=in.($ids)&select=message_id",
                    "GET"
                )
            )
            buildSet {
                for (i in 0 until deletionRows.length()) {
                    add(deletionRows.getJSONObject(i).optString("message_id"))
                }
            }
        }.getOrDefault(emptySet())

        val reactions = runCatching {
            val reactionRows = JSONArray(
                request(
                    "/rest/v1/message_reactions?message_id=in.($ids)&select=message_id,user_id,reaction",
                    "GET"
                )
            )
            buildList {
                for (i in 0 until reactionRows.length()) {
                    val row = reactionRows.getJSONObject(i)
                    add(
                        Triple(
                            row.optString("message_id"),
                            row.optString("user_id"),
                            row.optString("reaction")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())

        raw.filterNot { deleted.contains(it.id) }.map { message ->
            val grouped = reactions
                .filter { it.first == message.id }
                .groupBy { it.third }
                .map { (reaction, rows) ->
                    ReactionCount(
                        reaction,
                        rows.size,
                        rows.any { it.second == mine }
                    )
                }
            message.copy(reactions = grouped)
        }
    }

    private fun parseMessage(response: String): ChatMessage? = runCatching {
        val objectValue = if (response.trimStart().startsWith("[")) {
            JSONArray(response).optJSONObject(0)
        } else {
            JSONObject(response)
        }
        if (objectValue == null) {
            null
        } else {
            ChatMessage(
                objectValue.optString("id"),
                objectValue.optString("sender_id"),
                objectValue.optString("body"),
                objectValue.optString("created_at"),
                objectValue.optString("delivered_at"),
                objectValue.optString("read_at"),
                objectValue.optString("edited_at"),
                objectValue.optString("reply_to_id"),
                objectValue.optString("deleted_at")
            )
        }
    }.getOrNull()

    suspend fun markDelivered(id: String) = withContext(Dispatchers.IO) {
        request(
            "/rest/v1/messages?id=eq.$id&receiver_id=eq.${userId()}",
            "PATCH",
            JSONObject().put("delivered_at", Instant.now().toString()).toString()
        )
    }

    suspend fun markSeen(other: String) = withContext(Dispatchers.IO) {
        request(
            "/rest/v1/messages?sender_id=eq.$other&receiver_id=eq.${userId()}&read_at=is.null",
            "PATCH",
            JSONObject().put("read_at", Instant.now().toString()).toString()
        )
    }

    suspend fun sendMessage(other: String, body: String, replyToId: String? = null): ChatMessage? = withContext(Dispatchers.IO) {
        val clean = body.trim()
        if (clean.isBlank()) return@withContext null
        val json = JSONObject()
            .put("sender_id", userId())
            .put("receiver_id", other)
            .put("body", clean)
        if (!replyToId.isNullOrBlank()) json.put("reply_to_id", replyToId)
        parseMessage(request("/rest/v1/messages", "POST", json.toString()))
    }

    suspend fun editMessage(id: String, body: String): ChatMessage = withContext(Dispatchers.IO) {
        val clean = body.trim()
        if (clean.isBlank()) throw IllegalStateException("Message cannot be empty")
        if (clean.length > 4000) throw IllegalStateException("Message is too long")
        parseMessage(
            request(
                "/rest/v1/rpc/edit_my_message",
                "POST",
                JSONObject().put("p_message_id", id).put("p_body", clean).toString()
            )
        ) ?: throw IllegalStateException("Message could not be edited")
    }

    suspend fun deleteForMe(id: String) = withContext(Dispatchers.IO) {
        request(
            "/rest/v1/rpc/delete_message_for_me",
            "POST",
            JSONObject().put("p_message_id", id).toString()
        )
    }

    suspend fun deleteForEveryone(id: String): ChatMessage = withContext(Dispatchers.IO) {
        parseMessage(
            request(
                "/rest/v1/rpc/delete_message_for_everyone",
                "POST",
                JSONObject().put("p_message_id", id).toString()
            )
        ) ?: throw IllegalStateException("Message could not be deleted")
    }

    suspend fun setReaction(id: String, reaction: String) = withContext(Dispatchers.IO) {
        request(
            "/rest/v1/rpc/set_message_reaction",
            "POST",
            JSONObject().put("p_message_id", id).put("p_reaction", reaction).toString()
        )
    }

    suspend fun removeReaction(id: String) = withContext(Dispatchers.IO) {
        request(
            "/rest/v1/rpc/remove_message_reaction",
            "POST",
            JSONObject().put("p_message_id", id).toString()
        )
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
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editText by remember { mutableStateOf("") }
    var actionMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var reactionTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var deleteEveryone by remember { mutableStateOf(false) }
    var replyToMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        session = withContext(Dispatchers.IO) { auth.currentSession() }
        checkingSession = false
    }

    val api = session?.let { currentSession ->
        remember(currentSession.accessToken) { FriendsApi(auth, currentSession) }
    }

    fun reload() {
        val currentApi = api ?: return
        busy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    currentApi.touchPresence()
                    Triple(
                        currentApi.friends(),
                        currentApi.requests(),
                        currentApi.onlineUsers()
                    )
                }
            }.onSuccess { value ->
                friends = value.first
                requests = value.second
                online = value.third
            }.onFailure { error ->
                statusMessage = error.message ?: "Could not load Friends."
            }
            busy = false
        }
    }

    LaunchedEffect(session?.accessToken) {
        if (session != null) reload()
    }

    LaunchedEffect(api) {
        if (api != null) {
            while (true) {
                runCatching {
                    api.touchPresence()
                    online = api.onlineUsers()
                    friends = api.friends()
                }
                delay(30000)
            }
        }
    }

    LaunchedEffect(selected?.id, api) {
        val current = selected ?: return@LaunchedEffect
        val currentApi = api ?: return@LaunchedEffect
        while (true) {
            runCatching {
                currentApi.markSeen(current.id)
                val fresh = currentApi.messages(current.id)
                fresh
                    .filter { it.senderId == current.id && it.deliveredAt.isBlank() }
                    .forEach { message -> currentApi.markDelivered(message.id) }
                if (fresh != messages) messages = fresh
            }
            delay(1500)
        }
    }

    DisposableEffect(api, selected?.id) {
        val selectedId = selected?.id
        val realtime = api?.let { currentApi ->
            RealtimeMessagesClient(
                tokenProvider = { currentApi.token() },
                currentUserId = currentApi.userId(),
                apiKey = FRIENDS_KEY
            ) { id, senderId, body, createdAt ->
                scope.launch(Dispatchers.Main) {
                    if (
                        selectedId != null &&
                        selected?.id == selectedId &&
                        senderId == selectedId &&
                        messages.none { it.id == id }
                    ) {
                        messages = messages + ChatMessage(
                            id = id,
                            senderId = senderId,
                            body = body,
                            createdAt = createdAt,
                            deliveredAt = Instant.now().toString()
                        )
                        scope.launch(Dispatchers.IO) {
                            currentApi.markDelivered(id)
                            currentApi.markSeen(selectedId)
                        }
                    }
                }
            }
        }
        realtime?.start()
        onDispose { realtime?.stop() }
    }

    if (checkingSession) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (session == null || api == null) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(60.dp))
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(48.dp))
            Text("Login required", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Please log in from the Profile tab first.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    if (selected != null) {
        val chat = selected!!
        val listState = rememberLazyListState()

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        }

        if (editingMessage != null) {
            AlertDialog(
                onDismissRequest = { editingMessage = null },
                title = { Text("Edit message") },
                text = {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { value -> if (value.length <= 4000) editText = value },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 6,
                        supportingText = { Text("${editText.length}/4000") }
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = editText.trim().isNotBlank(),
                        onClick = {
                            val target = editingMessage ?: return@TextButton
                            editingMessage = null
                            scope.launch {
                                runCatching { api.editMessage(target.id, editText) }
                                    .onSuccess { updated ->
                                        messages = messages.map {
                                            if (it.id == updated.id) {
                                                it.copy(body = updated.body, editedAt = updated.editedAt)
                                            } else {
                                                it
                                            }
                                        }
                                    }
                                    .onFailure { error ->
                                        statusMessage = error.message ?: "Message could not be edited."
                                    }
                            }
                        }
                    ) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { editingMessage = null }) { Text("Cancel") }
                }
            )
        }

        actionMessage?.let { target ->
            AlertDialog(
                onDismissRequest = { actionMessage = null },
                title = { Text("Message") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("message", target.body))
                                actionMessage = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text("Copy")
                        }
                        if (target.deletedAt.isBlank()) {
                            TextButton(
                                onClick = {
                                    replyToMessage = target
                                    actionMessage = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Reply, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Text("Reply")
                            }
                            TextButton(
                                onClick = {
                                    reactionTarget = target
                                    actionMessage = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Favorite, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Text("React")
                            }
                        }
                        if (target.senderId == api.userId() && target.deletedAt.isBlank()) {
                            TextButton(
                                onClick = {
                                    editingMessage = target
                                    editText = target.body
                                    actionMessage = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Text("Edit")
                            }
                        }
                        TextButton(
                            onClick = {
                                deleteTarget = target
                                deleteEveryone = false
                                actionMessage = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text("Delete for me")
                        }
                        if (target.senderId == api.userId() && target.deletedAt.isBlank()) {
                            TextButton(
                                onClick = {
                                    deleteTarget = target
                                    deleteEveryone = true
                                    actionMessage = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.DeleteForever, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Text("Delete for everyone")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { actionMessage = null }) { Text("Close") }
                }
            )
        }

        reactionTarget?.let { target ->
            val choices = listOf("👍", "❤️", "😂", "😮", "😢", "😡")
            AlertDialog(
                onDismissRequest = { reactionTarget = null },
                title = { Text("React to message") },
                text = {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        choices.forEach { reaction ->
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        runCatching { api.setReaction(target.id, reaction) }
                                            .onSuccess {
                                                reactionTarget = null
                                                messages = api.messages(chat.id)
                                            }
                                            .onFailure { error ->
                                                statusMessage = error.message ?: "Reaction failed."
                                            }
                                    }
                                }
                            ) {
                                Text(reaction, style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                runCatching { api.removeReaction(target.id) }
                                    .onSuccess {
                                        reactionTarget = null
                                        messages = api.messages(chat.id)
                                    }
                                    .onFailure { error ->
                                        statusMessage = error.message ?: "Reaction could not be removed."
                                    }
                            }
                        }
                    ) { Text("Remove my reaction") }
                }
            )
        }

        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = {
                    Text(if (deleteEveryone) "Delete for everyone?" else "Delete for me?")
                },
                text = {
                    Text(
                        if (deleteEveryone) {
                            "This will replace the message with a deleted-message notice for everyone in this chat."
                        } else {
                            "This removes the message from your view only."
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deleteTarget = null
                            scope.launch {
                                runCatching {
                                    if (deleteEveryone) {
                                        api.deleteForEveryone(target.id)
                                    } else {
                                        api.deleteForMe(target.id)
                                    }
                                }
                                    .onSuccess { messages = api.messages(chat.id) }
                                    .onFailure { error ->
                                        statusMessage = error.message ?: "Message could not be deleted."
                                    }
                            }
                        }
                    ) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
                }
            )
        }

        Column(Modifier.fillMaxSize()) {
            Surface(shadowElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            selected = null
                            messages = emptyList()
                            text = ""
                            replyToMessage = null
                        }
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(chat.username, style = MaterialTheme.typography.titleLarge)
                        val isOnline = online.any { it.id == chat.id }
                        Text(
                            if (isOnline) "Online" else "Offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isOnline) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Icon(
                        Icons.Default.Person,
                        contentDescription = "Profile",
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    val mine = message.senderId == api.userId()
                    val replySource = messages.firstOrNull { it.id == message.replyToId }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                    ) {
                        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                            Surface(
                                color = if (mine) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = RoundedCornerShape(18.dp),
                                modifier = if (!message.id.startsWith("local-")) {
                                    Modifier.combinedClickable(
                                        onClick = {},
                                        onLongClick = { actionMessage = message }
                                    )
                                } else {
                                    Modifier
                                }
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                                    if (replySource != null) {
                                        Text(
                                            "↩ ${replySource.body.take(80)}",
                                            modifier = Modifier.padding(bottom = 5.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2
                                        )
                                    }
                                    Text(
                                        if (message.deletedAt.isNotBlank()) {
                                            "This message was deleted"
                                        } else {
                                            message.body
                                        },
                                        color = if (message.deletedAt.isNotBlank()) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        style = if (message.deletedAt.isNotBlank()) {
                                            MaterialTheme.typography.bodyMedium
                                        } else {
                                            MaterialTheme.typography.bodyLarge
                                        }
                                    )
                                    if (message.reactions.isNotEmpty()) {
                                        Spacer(Modifier.height(4.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            message.reactions.forEach { reaction ->
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    tonalElevation = 2.dp
                                                ) {
                                                    Text(
                                                        "${reaction.reaction} ${reaction.count}",
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            val delivery = if (mine) {
                                when {
                                    message.readAt.isNotBlank() -> "✓✓ Seen"
                                    message.deliveredAt.isNotBlank() -> "✓✓ Delivered"
                                    else -> "✓ Sent"
                                }
                            } else {
                                ""
                            }
                            val edited = if (message.editedAt.isNotBlank()) "  • edited" else ""
                            Text(
                                if (mine) {
                                    "You  ${ChatTimeFormatter.time(message.createdAt)}  $delivery$edited"
                                } else {
                                    "${chat.username}  ${ChatTimeFormatter.time(message.createdAt)}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (mine && message.readAt.isNotBlank()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            statusMessage?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            replyToMessage?.let { reply ->
                Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Replying to", style = MaterialTheme.typography.labelSmall)
                            Text(
                                reply.body.take(100),
                                maxLines = 2,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(onClick = { replyToMessage = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel reply")
                        }
                    }
                }
            }

            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { value ->
                            text = value
                            statusMessage = null
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        maxLines = 4
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        enabled = text.isNotBlank(),
                        onClick = {
                            val outgoing = text.trim()
                            val replyId = replyToMessage?.id
                            text = ""
                            replyToMessage = null
                            val optimistic = ChatMessage(
                                id = "local-${System.nanoTime()}",
                                senderId = api.userId(),
                                body = outgoing,
                                createdAt = "",
                                replyToId = replyId.orEmpty()
                            )
                            messages = messages + optimistic
                            scope.launch {
                                runCatching { api.sendMessage(chat.id, outgoing, replyId) }
                                    .onSuccess { sent ->
                                        messages = if (sent != null) {
                                            messages.map { if (it.id == optimistic.id) sent else it }
                                        } else {
                                            api.messages(chat.id)
                                        }
                                    }
                                    .onFailure { error ->
                                        messages = messages.filterNot { it.id == optimistic.id }
                                        statusMessage = error.message ?: "Message could not be sent."
                                    }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Friends", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Online people and accepted friends are shown here.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (online.isNotEmpty()) {
            Text("Online now", style = MaterialTheme.typography.titleMedium)
            online.forEach { user ->
                ListItem(
                    headlineContent = { Text(user.username) },
                    supportingContent = { Text("Online") },
                    leadingContent = {
                        Icon(
                            Icons.Default.Circle,
                            contentDescription = "Online",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        Button(
                            onClick = {
                                scope.launch {
                                    runCatching { api.send(user.id) }
                                        .onSuccess { message ->
                                            statusMessage = message
                                            reload()
                                        }
                                        .onFailure { error -> statusMessage = error.message }
                                }
                            }
                        ) { Text("Add") }
                    }
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                label = { Text("Search username") },
                singleLine = true
            )
            Button(
                enabled = query.isNotBlank(),
                onClick = {
                    scope.launch {
                        results = runCatching { api.search(query) }
                            .getOrElse { error ->
                                statusMessage = error.message
                                emptyList()
                            }
                    }
                }
            ) { Text("Search") }
        }

        if (results.isNotEmpty()) {
            Text("Search results", style = MaterialTheme.typography.titleMedium)
            results.forEach { user ->
                ListItem(
                    headlineContent = { Text(user.username) },
                    leadingContent = {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Add friend")
                    },
                    trailingContent = {
                        Button(
                            onClick = {
                                scope.launch {
                                    runCatching { api.send(user.id) }
                                        .onSuccess { message ->
                                            statusMessage = message
                                            reload()
                                        }
                                        .onFailure { error -> statusMessage = error.message }
                                }
                            }
                        ) { Text("Add") }
                    }
                )
            }
        }

        if (requests.isNotEmpty()) {
            Text("Friend requests", style = MaterialTheme.typography.titleMedium)
            requests.forEach { requestItem ->
                ListItem(
                    headlineContent = { Text(requestItem.user.username) },
                    supportingContent = {
                        Text(if (requestItem.incoming) "Wants to be your friend" else "Pending")
                    },
                    trailingContent = {
                        if (requestItem.incoming) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        runCatching { api.accept(requestItem.id) }
                                            .onSuccess { reload() }
                                            .onFailure { error -> statusMessage = error.message }
                                    }
                                }
                            ) { Text("Accept") }
                        } else {
                            Text("Pending")
                        }
                    }
                )
            }
        }

        Text("Friends", style = MaterialTheme.typography.titleMedium)
        if (friends.isEmpty()) {
            Text("No friends yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        friends.forEach { user ->
            ListItem(
                headlineContent = { Text(user.username) },
                supportingContent = {
                    Text(if (online.any { it.id == user.id }) "Online" else "Offline")
                },
                leadingContent = { Icon(Icons.Default.Person, contentDescription = "Friend") },
                trailingContent = {
                    FilledTonalButton(onClick = {
                        selected = user
                        statusMessage = null
                    }) { Text("Chat") }
                }
            )
        }

        statusMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.primary)
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
