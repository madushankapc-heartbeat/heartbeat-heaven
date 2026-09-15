package com.heartbeatheaven.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val WEBSITE_STUDIO_HANDOFF_URL = "https://heartbeat-heaven.onrender.com/api/studio/mobile-handoff"

class StudioActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { StudioScreen() } }
    }
}

@Composable
internal fun StudioScreen() {
    val context = LocalContext.current
    val auth = remember { AuthApi(context) }
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<AuthSession?>(null) }
    var loading by remember { mutableStateOf(true) }
    var openingStudio by remember { mutableStateOf(false) }
    var studioError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        session = withContext(Dispatchers.IO) { auth.currentSession() }
        loading = false
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    if (session == null || !session!!.profile.isAdmin) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(70.dp))
            Icon(Icons.Default.Lock, null, Modifier.size(52.dp))
            Text("Studio access restricted", style = MaterialTheme.typography.headlineSmall)
            Text("Only authorized HEARTBEAT HEAVEN administrators can open Studio.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { finishActivity(context) }) { Text("Back to app") }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Studio", style = MaterialTheme.typography.headlineMedium)
        Text("Welcome, ${session!!.profile.username}.", style = MaterialTheme.typography.titleMedium)
        Text("Admin workspace for HEARTBEAT HEAVEN. Song management and private user administration stay here.", color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(
            onClick = {
                if (openingStudio) return@Card
                studioError = null
                openingStudio = true
                scope.launch {
                    try {
                        val current = withContext(Dispatchers.IO) { auth.currentSession() }
                        val accessToken = current?.accessToken ?: ""
                        if (current == null || !current.profile.isAdmin || accessToken.isBlank()) {
                            error("Admin session is no longer valid. Please sign in again.")
                        }
                        val handoffUrl = withContext(Dispatchers.IO) { requestStudioHandoff(accessToken) }
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(handoffUrl)))
                    } catch (e: Exception) {
                        studioError = e.message ?: "Unable to open Music Studio."
                    } finally {
                        openingStudio = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Upload, null)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Music Studio", style = MaterialTheme.typography.titleLarge)
                    Text(if (openingStudio) "Opening website Studio…" else "Open the full HEARTBEAT HEAVEN Studio.")
                }
                if (openingStudio) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }

        Card(onClick = { context.startActivity(Intent(context, UsersAdminActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.People, null)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Users", style = MaterialTheme.typography.titleLarge)
                    Text("View private account details and activity.")
                }
            }
        }

        if (studioError != null) {
            Text(studioError!!, color = MaterialTheme.colorScheme.error)
        }

        OutlinedButton(onClick = { finishActivity(context) }) { Text("Back") }
    }
}

private suspend fun requestStudioHandoff(accessToken: String): String {
    val connection = URL(WEBSITE_STUDIO_HANDOFF_URL).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.doInput = true
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }

        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (connection.responseCode !in 200..299) {
            val message = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
            error(if (message.isBlank()) "Studio handoff failed (${connection.responseCode})." else message)
        }

        val handoffUrl = JSONObject(body).optString("handoff_url").trim()
        if (handoffUrl.isBlank()) error("Studio handoff URL was not returned.")
        return handoffUrl
    } finally {
        connection.disconnect()
    }
}

private fun finishActivity(context: Context) {
    (context as? ComponentActivity)?.finish()
}
