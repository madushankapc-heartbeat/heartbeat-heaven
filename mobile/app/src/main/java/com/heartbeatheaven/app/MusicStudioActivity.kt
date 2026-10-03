package com.heartbeatheaven.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
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

class MusicStudioActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { MusicStudioScreen(onBack = { finish() }) } }
    }
}

private data class StudioSong(
    val id: Long,
    val title: String,
    val artist: String,
    val versionName: String,
    val parentSongId: Long?
)

@Composable
private fun MusicStudioScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var songs by remember { mutableStateOf<List<StudioSong>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                val auth = AuthApi(context)
                val session = auth.currentSession() ?: error("Please log in again.")
                if (!session.profile.isAdmin) error("Admin access required.")
                val connection = URL("https://fafvhyeesenpimxncupp.supabase.co/rest/v1/songs?select=id,title,artist,version_name,parent_song_id&order=parent_song_id.asc.nullsfirst,created_at.desc").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("apikey", "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs")
                    connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
                    val text = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
                    if (connection.responseCode !in 200..299) error("Could not load songs (${connection.responseCode}).")
                    val json = JSONArray(text)
                    buildList {
                        for (i in 0 until json.length()) {
                            val o = json.getJSONObject(i)
                            add(StudioSong(o.optLong("id"), o.optString("title", "Untitled"), o.optString("artist", "Madushanka"), o.optString("version_name", "Original Version"), if (o.isNull("parent_song_id")) null else o.optLong("parent_song_id")))
                        }
                    }
                } finally { connection.disconnect() }
            }.onSuccess { result -> songs = result; loading = false }.onFailure { e -> error = e.message ?: "Could not load Music Studio."; loading = false }
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Text("Music Studio", style = MaterialTheme.typography.headlineSmall)
        }
        Text("Manage songs and versions", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            songs.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.MusicNote, null, Modifier.size(48.dp))
                Spacer(Modifier.height(10.dp))
                Text("No songs found.")
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(songs) { song ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(song.title, style = MaterialTheme.typography.titleMedium)
                            Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (song.parentSongId == null) "Original • ${song.versionName}" else "Version • ${song.versionName}", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
