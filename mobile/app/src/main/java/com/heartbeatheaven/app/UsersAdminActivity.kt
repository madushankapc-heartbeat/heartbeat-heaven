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

@Composable
private fun UsersAdminScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
                val connection = URL("https://fafvhyeesenpimxncupp.supabase.co/rest/v1/rpc/admin_users").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("apikey", "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs")
                    connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write("{}".toByteArray()) }
                    val text = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
                    if (connection.responseCode !in 200..299) error("Could not load users (${connection.responseCode}).")
                    val json = JSONArray(text)
                    buildList {
                        for (i in 0 until json.length()) {
                            val o = json.getJSONObject(i)
                            add(
                                AdminUser(
                                    id = o.optString("id"),
                                    username = o.optString("username", "User"),
                                    email = o.optString("email", "Not available"),
                                    phone = o.optString("phone", "Not available"),
                                    age = o.optInt("age", 0).takeIf { it > 0 }?.toString() ?: "Not available",
                                    gender = o.optString("gender", "Not available"),
                                    role = if (o.optString("role") == "admin") "Admin" else "User",
                                    lastSeen = o.optString("last_seen_at", "Not available")
                                )
                            )
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
                val connection = URL("https://fafvhyeesenpimxncupp.supabase.co/rest/v1/rpc/admin_delete_user").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("apikey", "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs")
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
        }.onSuccess {
            users = users.filterNot { it.id == user.id }
            error = null
        }.onFailure { e -> error = e.message ?: "Could not delete user." }
        deletingId = null
    }

    LaunchedEffect(Unit) { loadUsers() }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (deletingId == null) deleteTarget = null },
            title = { Text("Delete account?") },
            text = { Text("This permanently deletes ${target.username}'s account and related profile data. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    enabled = deletingId == null,
                    onClick = {
                        deleteTarget = null
                        scope.launch { deleteUser(target) }
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = deletingId == null, onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Text("Users", style = MaterialTheme.typography.headlineSmall)
        }
        Text("Private admin-only account overview", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null && users.isEmpty() -> Text(error!!, color = MaterialTheme.colorScheme.error)
            users.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.People, null, Modifier.size(48.dp))
                Spacer(Modifier.height(10.dp))
                Text("No users found.")
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(users, key = { it.id }) { user ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(user.username, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                if (user.role != "Admin") {
                                    IconButton(enabled = deletingId == null, onClick = { deleteTarget = user }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete account", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            Text(user.email)
                            Text("Mobile: ${user.phone}")
                            Text("Age: ${user.age} • ${user.gender.replaceFirstChar { it.uppercase() }}")
                            Text("Role: ${user.role}", color = MaterialTheme.colorScheme.primary)
                            if (user.lastSeen != "Not available") Text("Last seen: ${user.lastSeen}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (error != null && users.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
    }
}
