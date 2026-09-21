package com.heartbeatheaven.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private data class AppNotification(
    val id: String,
    val kind: String,
    val title: String,
    val body: String,
    val entityId: String?,
    val readAt: String?,
    val createdAt: String
)

private class NotificationsApi(private val auth: AuthApi) {
    private val base = "https://fafvhyeesenpimxncupp.supabase.co"
    private val key = "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"

    private fun request(path: String, method: String, body: String? = null): String {
        val session = auth.currentSession() ?: throw IllegalStateException("Please log in again.")
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("apikey", key)
            c.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (c.responseCode !in 200..299) throw IllegalStateException("Notification request failed (${c.responseCode})")
            return text
        } finally { c.disconnect() }
    }

    suspend fun list(): List<AppNotification> = withContext(Dispatchers.IO) {
        val session = auth.currentSession() ?: return@withContext emptyList()
        val userId = URLEncoder.encode(session.profile.id, "UTF-8")
        val array = JSONArray(request("/rest/v1/notifications?recipient_id=eq.$userId&select=id,kind,title,body,entity_id,read_at,created_at&order=created_at.desc&limit=100", "GET"))
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(AppNotification(
                    id = o.optString("id"),
                    kind = o.optString("kind"),
                    title = o.optString("title"),
                    body = o.optString("body"),
                    entityId = o.optString("entity_id").takeIf { it.isNotBlank() },
                    readAt = o.optString("read_at").takeIf { it.isNotBlank() },
                    createdAt = o.optString("created_at")
                ))
            }
        }
    }

    suspend fun markRead(id: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/notifications?id=eq.${URLEncoder.encode(id, "UTF-8")}", "PATCH", JSONObject().put("read_at", "now()").toString())
    }

    suspend fun markAllRead() = withContext(Dispatchers.IO) {
        val session = auth.currentSession() ?: return@withContext
        request("/rest/v1/notifications?recipient_id=eq.${URLEncoder.encode(session.profile.id, "UTF-8")}&read_at=is.null", "PATCH", JSONObject().put("read_at", "now()").toString())
    }
}

@Composable
internal fun NotificationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val auth = remember { AuthApi(context) }
    val api = remember { NotificationsApi(auth) }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<AppNotification>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            try {
                loading = true
                items = api.list()
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Could not load notifications."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    DisposableEffect(Unit) {
        val session = auth.currentSession()
        val realtime = session?.let {
            RealtimeNotificationsClient(
                accessTokenProvider = { auth.currentSession()?.accessToken.orEmpty() },
                userId = it.profile.id,
                apiKey = "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs",
                onNotificationChange = { reload() }
            )
        }
        realtime?.start()
        onDispose { realtime?.stop() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Notifications", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (items.any { it.readAt == null }) {
                IconButton(onClick = {
                    scope.launch {
                        runCatching { api.markAllRead(); items = api.list() }
                    }
                }) { Icon(Icons.Default.DoneAll, "Mark all as read") }
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!, color = MaterialTheme.colorScheme.error) }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Notifications, null, Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No notifications yet.")
                }
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { n ->
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            if (n.readAt == null) {
                                scope.launch {
                                    runCatching { api.markRead(n.id); items = api.list() }
                                }
                            }
                        }
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Notifications, null, Modifier.padding(top = 2.dp))
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(n.title, fontWeight = if (n.readAt == null) FontWeight.Bold else FontWeight.Normal)
                                if (n.body.isNotBlank()) Text(n.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (n.readAt == null) {
                                Box(Modifier.size(9.dp).padding(top = 4.dp)) {
                                    Surface(Modifier.fillMaxSize(), shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primary) {}
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
