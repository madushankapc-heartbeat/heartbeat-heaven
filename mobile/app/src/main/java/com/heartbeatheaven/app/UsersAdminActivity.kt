package com.heartbeatheaven.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val ADMIN_SUPABASE_URL = "https://fafvhyeesenpimxncupp.supabase.co"
private const val ADMIN_SUPABASE_KEY = "sb_" + "publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"
private const val OWNER_CHAT_URL_ADMIN = "$ADMIN_SUPABASE_URL/functions/v1/owner-chat"

class UsersAdminActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { UsersAdminScreen(onBack = { finish() }) } }
    }
}

private data class AdminUser(
    val id: String,
    val username: String,
    val email: String,
    val phone: String,
    val age: String,
    val gender: String,
    val role: String,
    val lastSeen: String
)

private data class OwnerConversation(
    val id: String,
    val label: String,
    val category: String,
    val status: String,
    val updatedAt: String
)

private data class AdminChatMessage(val id: String, val body: String, val senderType: String, val createdAt: String)

private fun adminOwnerChatRequest(context: android.content.Context, payload: JSONObject): JSONObject = runCatching {
    val session = AuthApi(context).currentSession() ?: error("Please log in again.")
    if (!session.profile.isAdmin) error("Admin access required.")
    val connection = URL(OWNER_CHAT_URL_ADMIN).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("apikey", ADMIN_SUPABASE_KEY)
        connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = if (text.isBlank()) JSONObject() else JSONObject(text)
        if (connection.responseCode !in 200..299) error(json.optString("error").ifBlank { "Owner messages request failed (${connection.responseCode})." })
        json
    } finally { connection.disconnect() }
}.getOrElse { throw it }

private suspend fun loadOwnerConversations(context: android.content.Context): List<OwnerConversation> = withContext(Dispatchers.IO) {
    val root = adminOwnerChatRequest(context, JSONObject().put("action", "list"))
    val array = root.optJSONArray("conversations") ?: JSONArray()
    buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(OwnerConversation(o.optString("id"), o.optString("label", "Guest User"), o.optString("category", "General"), o.optString("status", "open"), o.optString("updated_at")))
        }
    }
}

private suspend fun loadOwnerConversation(context: android.content.Context, conversationId: String): List<AdminChatMessage> = withContext(Dispatchers.IO) {
    val root = adminOwnerChatRequest(context, JSONObject().put("action", "list").put("conversation_id", conversationId))
    val array = root.optJSONArray("messages") ?: JSONArray()
    buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(AdminChatMessage(o.optString("id"), o.optString("body"), o.optString("sender_type"), o.optString("created_at")))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsersAdminScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMessages by remember { mutableStateOf(false) }
    var conversations by remember { mutableStateOf<List<OwnerConversation>>(emptyList()) }
    var selectedConversation by remember { mutableStateOf<OwnerConversation?>(null) }
    var chatMessages by remember { mutableStateOf<List<AdminChatMessage>>(emptyList()) }
    var replyText by remember { mutableStateOf("") }
    var chatLoading by remember { mutableStateOf(false) }
    var chatError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var users by remember { mutableStateOf<List<AdminUser>>(emptyList()) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<AdminUser?>(null) }

    suspend fun loadUsers() {
        withContext(Dispatchers.IO) {
            runCatching {
                val auth = AuthApi(context)
                val session = auth.currentSession() ?: error("Please log in again.")
                if (!session.profile.isAdmin) error("Admin access required.")
                val connection = URL("$ADMIN_SUPABASE_URL/rest/v1/rpc/admin_users").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("apikey", ADMIN_SUPABASE_KEY)
                    connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write("{}".toByteArray()) }
                    val text = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
                    if (connection.responseCode !in 200..299) error("Could not load users (${connection.responseCode}).")
                    val json = JSONArray(text)
                    buildList {
                        for (i in 0 until json.length()) {
                            val o = json.getJSONObject(i)
                            add(AdminUser(o.optString("id"), o.optString("username", "User"), o.optString("email", "Not available"), o.optString("phone", "Not available"), o.optInt("age", 0).takeIf { it > 0 }?.toString() ?: "Not available", o.optString("gender", "Not available"), if (o.optString("role") == "admin") "Admin" else "User", o.optString("last_seen_at", "Not available")))
                        }
                    }
                } finally { connection.disconnect() }
            }.onSuccess { result -> users = result; loading = false; error = null }
             .onFailure { e -> error = e.message ?: "Could not load users."; loading = false }
        }
    }

    suspend fun deleteUser(user: AdminUser) {
        deletingId = user.id
        withContext(Dispatchers.IO) {
            runCatching {
                val auth = AuthApi(context)
                val session = auth.currentSession() ?: error("Please log in again.")
                if (!session.profile.isAdmin) error("Admin access required.")
                val connection = URL("$ADMIN_SUPABASE_URL/rest/v1/rpc/admin_delete_user").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("apikey", ADMIN_SUPABASE_KEY)
                    connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(JSONObject().put("p_user_id", user.id).toString().toByteArray()) }
                    val text = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
                    if (connection.responseCode !in 200..299) {
                        val detail = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
                        error(detail.ifBlank { "Could not delete user (${connection.responseCode})." })
                    }
                } finally { connection.disconnect() }
            }
        }.onSuccess { users = users.filterNot { it.id == user.id }; error = null }.onFailure { e -> error = e.message ?: "Could not delete user." }
        deletingId = null
    }

    fun refreshConversations() {
        scope.launch {
            chatError = null
            runCatching { loadOwnerConversations(context) }.onSuccess { conversations = it }.onFailure { chatError = it.message ?: "Could not load owner messages." }
        }
    }

    fun openConversation(conversation: OwnerConversation) {
        selectedConversation = conversation
        chatLoading = true
        chatError = null
        scope.launch {
            runCatching { loadOwnerConversation(context, conversation.id) }.onSuccess { chatMessages = it }.onFailure { chatError = it.message ?: "Could not load conversation." }
            runCatching { adminOwnerChatRequest(context, JSONObject().put("action", "read").put("conversation_id", conversation.id)) }
            chatLoading = false
        }
    }

    fun sendReply() {
        val conversation = selectedConversation ?: return
        val body = replyText.trim()
        if (body.isEmpty()) return
        replyText = ""
        scope.launch {
            runCatching { adminOwnerChatRequest(context, JSONObject().put("action", "reply").put("conversation_id", conversation.id).put("body", body)); loadOwnerConversation(context, conversation.id) }
                .onSuccess { chatMessages = it; refreshConversations() }
                .onFailure { chatError = it.message ?: "Could not send reply." }
        }
    }

    LaunchedEffect(Unit) { loadUsers() }
    LaunchedEffect(showMessages) { if (showMessages) refreshConversations() }

    deleteTarget?.let { target ->
        AlertDialog(onDismissRequest = { if (deletingId == null) deleteTarget = null }, title = { Text("Delete account?") }, text = { Text("This permanently deletes ${target.username}'s account and related profile data. This cannot be undone.") },
            confirmButton = { TextButton(enabled = deletingId == null, onClick = { deleteTarget = null; scope.launch { deleteUser(target) } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(enabled = deletingId == null, onClick = { deleteTarget = null }) { Text("Cancel") } })
    }

    if (showMessages) {
        if (selectedConversation == null) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { showMessages = false }) { Icon(Icons.Default.ArrowBack, "Back") }; Text("Owner Messages", style = MaterialTheme.typography.headlineSmall) }
                Text("Messages from users and guests", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
                if (chatError != null && conversations.isEmpty()) Text(chatError!!, color = MaterialTheme.colorScheme.error)
                if (conversations.isEmpty() && chatError == null) Text("No owner messages yet.")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(conversations, key = { it.id }) { c ->
                        Card(onClick = { openConversation(c) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(Modifier.fillMaxWidth()) { Text(c.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); Text(c.status.replaceFirstChar { it.uppercase() }) }
                                Text(c.category, color = MaterialTheme.colorScheme.primary)
                                if (c.updatedAt.isNotBlank()) Text(c.updatedAt, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        } else {
            val c = selectedConversation!!
            Scaffold(topBar = { TopAppBar(title = { Column { Text(c.label); Text(c.category, style = MaterialTheme.typography.labelSmall) } }, navigationIcon = { IconButton(onClick = { selectedConversation = null; chatMessages = emptyList(); refreshConversations() }) { Icon(Icons.Default.ArrowBack, "Back") } }) }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (chatLoading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(chatMessages, key = { it.id }) { m -> Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.senderType == "admin") Arrangement.End else Arrangement.Start) { Surface(color = if (m.senderType == "admin") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.widthIn(max = 310.dp)) { Text(m.body, Modifier.padding(12.dp)) } } }
                        if (chatError != null) item { Text(chatError!!, color = MaterialTheme.colorScheme.error) }
                    }
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(replyText, { if (it.length <= 2000) replyText = it }, Modifier.weight(1f), placeholder = { Text("Reply to user…") }, maxLines = 4)
                        Spacer(Modifier.width(8.dp)); IconButton(enabled = replyText.trim().isNotEmpty(), onClick = { sendReply() }) { Icon(Icons.Default.Mail, "Send reply") }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }; Text("Users", style = MaterialTheme.typography.headlineSmall) }
            Text("Private admin-only account overview", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
            Button(onClick = { showMessages = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Mail, null); Spacer(Modifier.width(8.dp)); Text("Owner Messages") }
            Spacer(Modifier.height(10.dp))
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null && users.isEmpty() -> Text(error!!, color = MaterialTheme.colorScheme.error)
                users.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(Icons.Default.People, null, Modifier.size(48.dp)); Spacer(Modifier.height(10.dp)); Text("No users found.") }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(users, key = { it.id }) { user -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(user.username, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); if (user.role != "Admin") IconButton(enabled = deletingId == null, onClick = { deleteTarget = user }) { Icon(Icons.Default.Delete, "Delete account", tint = MaterialTheme.colorScheme.error) } }
                        Text(user.email); Text("Mobile: ${user.phone}"); Text("Age: ${user.age} • ${user.gender.replaceFirstChar { it.uppercase() }}"); Text("Role: ${user.role}", color = MaterialTheme.colorScheme.primary); if (user.lastSeen != "Not available") Text("Last seen: ${user.lastSeen}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } } }
                }
            }
            if (error != null && users.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(error!!, color = MaterialTheme.colorScheme.error) }
        }
    }
}
