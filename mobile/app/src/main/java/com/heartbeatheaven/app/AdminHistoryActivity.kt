package com.heartbeatheaven.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val ADMIN_HISTORY_URL = "https://fafvhyeesenpimxncupp.supabase.co/functions/v1/admin-message-history"
private const val ADMIN_MEDIA_URL = "https://fafvhyeesenpimxncupp.supabase.co/functions/v1/secure-media-access"
private const val ADMIN_HISTORY_KEY = "sb_" + "publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"

private data class AdminHistoryItem(
    val id: String, val sender: String, val receiver: String, val body: String,
    val type: String, val mediaUrl: String, val mediaPath: String, val mediaName: String,
    val createdAt: String, val deletedAt: String?
)

private fun adminHistoryRequest(context: Context, reason: String, type: String, offset: Int): JSONObject {
    val session = AuthApi(context).currentSession() ?: error("Please log in again.")
    if (!session.profile.isAdmin) error("Admin access required.")
    val body = JSONObject().put("reason", reason).put("message_type", type).put("limit", 50).put("offset", offset)
    val c = URL(ADMIN_HISTORY_URL).openConnection() as HttpURLConnection
    try {
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 30000; c.doOutput = true
        c.setRequestProperty("apikey", ADMIN_HISTORY_KEY)
        c.setRequestProperty("Authorization", "Bearer " + session.accessToken)
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = if (text.isBlank()) JSONObject() else JSONObject(text)
        if (c.responseCode !in 200..299) error(json.optString("error").ifBlank { "History request failed (" + c.responseCode + ")." })
        return json
    } finally { c.disconnect() }
}

private fun adminMediaRequest(context: Context, item: AdminHistoryItem, reason: String): String {
    val session = AuthApi(context).currentSession() ?: error("Please log in again.")
    if (!session.profile.isAdmin) error("Admin access required.")
    val body = JSONObject().put("message_id", item.id).put("media_url", item.mediaUrl).put("media_path", item.mediaPath).put("mode", "admin").put("reason", reason)
    val c = URL(ADMIN_MEDIA_URL).openConnection() as HttpURLConnection
    try {
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 30000; c.doOutput = true
        c.setRequestProperty("apikey", ADMIN_HISTORY_KEY)
        c.setRequestProperty("Authorization", "Bearer " + session.accessToken)
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = if (text.isBlank()) JSONObject() else JSONObject(text)
        if (c.responseCode !in 200..299) error(json.optString("error").ifBlank { "Media access failed (" + c.responseCode + ")." })
        return json.optString("url").ifBlank { error("No secure media URL returned.") }
    } finally { c.disconnect() }
}

private fun parseHistory(json: JSONObject): List<AdminHistoryItem> {
    val array = json.optJSONArray("history") ?: JSONArray()
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(AdminHistoryItem(
                id = o.optString("message_id"),
                sender = o.optString("sender_username").ifBlank { o.optString("sender_id") },
                receiver = o.optString("receiver_username").ifBlank { o.optString("receiver_id") },
                body = o.optString("body"),
                type = o.optString("message_type", "text"),
                mediaUrl = o.optString("media_url"),
                mediaPath = o.optString("media_path"),
                mediaName = o.optString("media_name"),
                createdAt = o.optString("created_at"),
                deletedAt = o.optString("deleted_at").takeIf { it.isNotBlank() && it != "null" }
            ))
        }
    }
}

class AdminHistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AdminHistoryScreen(onBack = { finish() }) } }
    }
}

@Composable
private fun AdminHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<AdminHistoryItem>>(emptyList()) }
    var offset by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun load(reset: Boolean) {
        val cleanReason = reason.trim()
        if (cleanReason.length < 5) { error = "Enter a reason before opening admin history."; return }
        val nextOffset = if (reset) 0 else offset + 50
        loading = true; error = null; message = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { adminHistoryRequest(context, cleanReason, type.trim().lowercase(), nextOffset) } }
                .onSuccess { json ->
                    val page = parseHistory(json)
                    items = if (reset) page else items + page
                    offset = nextOffset
                    hasMore = json.optBoolean("has_more", page.size == 50)
                    message = "21-day history • " + items.size + " loaded"
                }
                .onFailure { error = it.message ?: "Could not load history." }
            loading = false
        }
    }

    fun openMedia(item: AdminHistoryItem) {
        if (item.mediaUrl.isBlank()) return
        val cleanReason = reason.trim()
        if (cleanReason.length < 5) { error = "Enter a reason before opening media."; return }
        loading = true; error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { adminMediaRequest(context, item, cleanReason) } }
                .onSuccess { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { error = "No app on this phone can open this media type." } }
                .onFailure { error = it.message ?: "Could not open secure media." }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Icon(Icons.Default.History, null); Spacer(Modifier.width(8.dp))
            Column { Text("Admin History", style = MaterialTheme.typography.headlineSmall); Text("All users • last 21 days", style = MaterialTheme.typography.labelMedium) }
        }
        Text("History is retained for 21 days from the message date. Admin access is recorded.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(value = reason, onValueChange = { reason = it.take(500) }, label = { Text("Reason for access") }, supportingText = { Text(reason.length.toString() + "/500") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = type, onValueChange = { type = it.take(10) }, label = { Text("Type filter (optional: text/image/video/audio/file)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !loading, onClick = { load(true) }) { Text(if (loading) "Loading..." else "Load 21-day history") }
            if (items.isNotEmpty()) OutlinedButton(enabled = !loading, onClick = { load(true) }) { Text("Refresh") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 6.dp)) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp)) }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(items, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.sender + " → " + item.receiver, style = MaterialTheme.typography.titleMedium)
                        Text(item.type.uppercase() + " • " + item.createdAt, style = MaterialTheme.typography.labelSmall)
                        if (item.body.isNotBlank()) Text(item.body, maxLines = 8, overflow = TextOverflow.Ellipsis)
                        if (item.mediaUrl.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.mediaName.ifBlank { "Attachment" }, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                TextButton(enabled = !loading, onClick = { openMedia(item) }) { Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(4.dp)); Text("View") }
                            }
                        }
                        if (item.deletedAt != null) Text("Deleted at " + item.deletedAt, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (hasMore) item { Button(enabled = !loading, onClick = { load(false) }, modifier = Modifier.fillMaxWidth()) { Text("Load next 50") } }
            if (items.isEmpty() && !loading && message != null) item { Text("No messages found in the 21-day window.") }
        }
    }
}
