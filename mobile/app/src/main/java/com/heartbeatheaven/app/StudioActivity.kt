package com.heartbeatheaven.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
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

    if (session == null) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(70.dp))
            Icon(Icons.Default.Lock, null, Modifier.size(52.dp))
            Text("Studio login required", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Please log in from the Profile tab, then open Studio again.",
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
            "Your Studio is protected by the same HEARTBEAT HEAVEN account login used by Friends and Profile.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Music Studio", style = MaterialTheme.typography.titleLarge)
                Text("Song upload and management can be added here next.")
            }
        }
        OutlinedButton(onClick = { finishActivity(context) }) { Text("Back") }
    }
}

private fun finishActivity(context: android.content.Context) {
    (context as? ComponentActivity)?.finish()
}
