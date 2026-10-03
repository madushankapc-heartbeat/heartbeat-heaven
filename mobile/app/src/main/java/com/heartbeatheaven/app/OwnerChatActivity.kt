package com.heartbeatheaven.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val OWNER_CHAT_URL = "https://fafvhyeesenpimxncupp.supabase.co/functions/v1/owner-chat"
private const val OWNER_CHAT_KEY = "sb_" + "publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"
private val OWNER_CHAT_CATEGORIES = listOf("General", "Report a bug", "Report an issue/user", "Suggest an improvement", "Ask a question")

private data class OwnerMessage(val id: String, val body: String, val senderType: String, val createdAt: String)

private fun guestToken(context: Context): String {
    val p = context.getSharedPreferences("owner_chat", Context.MODE_PRIVATE)
    return p.getString("guest_token", null) ?: UUID.randomUUID().toString().also { p.edit().putString("guest_token", it).apply() }
}

private fun ownerChatRequest(context: Context, payload: JSONObject): JSONObject = runCatching {
    val session = AuthApi(context).currentSession()
    val connection = URL(OWNER_CHAT_URL).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("apikey", OWNER_CHAT_KEY)
        if (session != null) connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        connection.setRequestProperty("x-guest-token", guestToken(context))
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = if (text.isBlank()) JSONObject() else JSONObject(text)
        if (connection.responseCode !in 200..299) error(json.optString("error").ifBlank { "Owner chat request failed (${connection.responseCode})." })
        json
    } finally { connection.disconnect() }
}.getOrElse { throw it }

private fun parseOwnerMessages(root: JSONObject): List<OwnerMessage> {
    val array = root.optJSONArray("messages") ?: JSONArray()
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(OwnerMessage(o.optString("id"), o.optString("body"), o.optString("sender_type"), o.optString("created_at")))
        }
    }
}

private suspend fun loadOwnerMessages(context: Context, category: String): List<OwnerMessage> = withContext(Dispatchers.IO) {
    ownerChatRequest(context, JSONObject().put("action", "create").put("category", category))
    val root = ownerChatRequest(context, JSONObject().put("action", "list"))
    parseOwnerMessages(root)
}

private suspend fun sendOwnerMessage(context: Context, category: String, body: String): List<OwnerMessage> = withContext(Dispatchers.IO) {
    val created = ownerChatRequest(context, JSONObject().put("action", "create").put("category", category))
    val conversationId = created.optJSONObject("conversation")?.optString("id").orEmpty()
    if (conversationId.isBlank()) error("Conversation could not be created. Please try again.")
    ownerChatRequest(context, JSONObject().put("action", "send").put("conversation_id", conversationId).put("category", category).put("body", body))
    val refreshed = ownerChatRequest(context, JSONObject().put("action", "list"))
    parseOwnerMessages(refreshed)
}

class OwnerChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { OwnerChatScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnerChatScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf("General") }
    var messages by remember { mutableStateOf<List<OwnerMessage>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    fun reload() { scope.launch { loading = true; error = null; runCatching { loadOwnerMessages(context, category) }.onSuccess { messages = it }.onFailure { error = it.message ?: "Unable to load owner chat." }; loading = false } }
    LaunchedEffect(category) { reload() }

    LaunchedEffect(category) {
        while (true) {
            delay(2_000L)
            if (!loading && !sending) {
                runCatching { loadOwnerMessages(context, category) }
                    .onSuccess { fresh -> if (fresh != messages) messages = fresh }
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Column { Text("Contact Owner", fontWeight = FontWeight.Bold); Text("Report bugs • Ask questions • Suggest improvements", fontSize = 11.sp) } }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Category: $category") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { OWNER_CHAT_CATEGORIES.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { category = item; menuOpen = false }) } }
            }
            if (loading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (messages.isEmpty()) item { Text("Hi! Send us a message and we'll get back to you here.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(messages, key = { it.id }) { message ->
                    val mine = message.senderType == "user"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Surface(shape = RoundedCornerShape(16.dp), color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.widthIn(max = 310.dp)) { Text(message.body, Modifier.padding(12.dp)) }
                    }
                }
                if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error) }
            }
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(value = text, onValueChange = { if (it.length <= 2000) text = it }, modifier = Modifier.weight(1f), placeholder = { Text("Write a message…") }, maxLines = 4, enabled = !sending)
                Spacer(Modifier.width(8.dp))
                IconButton(enabled = text.trim().isNotEmpty() && !sending, onClick = {
                    val body = text.trim(); text = ""; sending = true; error = null
                    scope.launch { runCatching { sendOwnerMessage(context, category, body) }.onSuccess { messages = it }.onFailure { error = it.message ?: "Unable to send message." }; sending = false }
                }) { Icon(Icons.Default.Send, "Send") }
            }
        }
    }
}
