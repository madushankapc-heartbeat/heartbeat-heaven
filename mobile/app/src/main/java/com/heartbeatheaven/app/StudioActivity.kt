package com.heartbeatheaven.app

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
import kotlinx.coroutines.withContext

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
    var session by remember { mutableStateOf<AuthSession?>(null) }
    var loading by remember { mutableStateOf(true) }

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
            Text(
                "Only authorized HEARTBEAT HEAVEN administrators can open Studio.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        Text(
            "Admin workspace for HEARTBEAT HEAVEN. Song management and private user administration stay here.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Upload, null)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Music Studio", style = MaterialTheme.typography.titleLarge)
                    Text("Upload songs, covers and additional versions.")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.People, null)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Users", style = MaterialTheme.typography.titleLarge)
                    Text("Admin-only user management will be connected here.")
                }
            }
        }

        OutlinedButton(onClick = { finishActivity(context) }) { Text("Back") }
    }
}

private fun finishActivity(context: android.content.Context) {
    (context as? ComponentActivity)?.finish()
}
