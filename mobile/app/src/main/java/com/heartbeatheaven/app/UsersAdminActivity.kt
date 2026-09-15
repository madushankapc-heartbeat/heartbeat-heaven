package com.heartbeatheaven.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

class UsersAdminActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { UsersAdminScreen(onBack = { finish() }) } }
    }
}

private data class AdminUser(
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
    val context = androidx.compose.ui.platform.LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var users by remember { mutableStateOf<List<AdminUser>>(emptyList()) }

    LaunchedEffect(Unit) {
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
                            add(AdminUser(o.optString("username", "User"), o.optString("email", "Not available"), o.optString("phone", "Not available"), o.optInt("age", 0).takeIf { it > 0 }?.toString() ?: "Not available", o.optString("gender", "Not available"), if (o.optString("role") == "admin") "Admin" else "User", o.optString("last_seen_at", "Not available")))
                        }
                    }
                } finally { connection.disconnect() }
            }.onSuccess { result -> users = result; loading = false }.onFailure { e -> error = e.message ?: "Could not load users."; loading = false }
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Text("Users", style = MaterialTheme.typography.headlineSmall)
        }
        Text("Private admin-only account overview", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            users.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.People, null, Modifier.size(48.dp))
                Spacer(Modifier.height(10.dp))
                Text("No users found.")
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(users) { user ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(user.username, style = MaterialTheme.typography.titleMedium)
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
    }
}
