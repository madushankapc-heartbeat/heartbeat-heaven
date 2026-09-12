package com.heartbeatheaven.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

private const val API_BASE = "https://heartbeat-heaven.onrender.com"

private data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val genre: String,
    val language: String,
    val mood: String,
    val description: String,
    val lyrics: String,
    val coverUrl: String,
    val audioUrl: String,
    val releaseDate: String?
)

private data class SongVersion(
    val id: Long,
    val versionName: String,
    val artist: String,
    val coverUrl: String,
    val audioUrl: String
)

private suspend fun fetchSongs(): List<Song> = withContext(Dispatchers.IO) {
    val connection = java.net.URL("$API_BASE/api/songs").openConnection() as java.net.HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        if (connection.responseCode !in 200..299) error("Server returned ${connection.responseCode}")
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val array = JSONArray(body)
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    Song(
                        id = o.optLong("id"),
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        genre = o.optString("genre"),
                        language = o.optString("language"),
                        mood = o.optString("mood"),
                        description = o.optString("description"),
                        lyrics = o.optString("lyrics"),
                        coverUrl = o.optString("cover_url"),
                        audioUrl = o.optString("audio_url"),
                        releaseDate = o.optString("release_date").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    } finally {
        connection.disconnect()
    }
}

private suspend fun fetchVersions(song: Song): List<SongVersion> = withContext(Dispatchers.IO) {
    val connection = java.net.URL("$API_BASE/api/songs/${song.id}/hub").openConnection() as java.net.HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        if (connection.responseCode !in 200..299) {
            return@withContext listOf(
                SongVersion(song.id, "Original Version", song.artist, song.coverUrl, song.audioUrl)
            )
        }

        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val hub = org.json.JSONObject(body)
        val original = hub.optJSONObject("original")
        val versionArray = hub.optJSONArray("versions") ?: JSONArray()
        val result = mutableListOf<SongVersion>()

        val originalSong = original ?: org.json.JSONObject().apply {
            put("id", song.id)
            put("artist", song.artist)
            put("cover_url", song.coverUrl)
            put("audio_url", song.audioUrl)
        }

        result.add(
            SongVersion(
                id = song.id,
                versionName = originalSong.optString("version_name").ifBlank { "Original Version" },
                artist = originalSong.optString("artist").ifBlank { song.artist },
                coverUrl = originalSong.optString("cover_url").ifBlank { song.coverUrl },
                audioUrl = originalSong.optString("audio_url").ifBlank { song.audioUrl }
            )
        )

        for (i in 0 until versionArray.length()) {
            val item = versionArray.optJSONObject(i) ?: continue
            val id = item.optLong("id", -(song.id * 1000 + i + 1))
            result.add(
                SongVersion(
                    id = id,
                    versionName = item.optString("version_name").ifBlank { "Version ${i + 1}" },
                    artist = item.optString("artist").ifBlank { song.artist },
                    coverUrl = item.optString("cover_url").ifBlank { song.coverUrl },
                    audioUrl = item.optString("audio_url").ifBlank { song.audioUrl }
                )
            )
        }

        result.filter { it.audioUrl.isNotBlank() }
    } catch (_: Exception) {
        listOf(SongVersion(song.id, "Original Version", song.artist, song.coverUrl, song.audioUrl))
    } finally {
        connection.disconnect()
    }
}

private fun thumbnailUrl(url: String): String {
    val clean = url.trim()
    if (clean.isBlank()) return ""
    return if (clean.contains("/storage/v1/object/public/")) {
        clean.replace("/storage/v1/object/public/", "/storage/v1/render/image/public/") +
            if (clean.contains("?")) "&width=144&height=144&resize=cover&quality=55" else "?width=144&height=144&resize=cover&quality=55"
    } else clean
}

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var currentSongId by mutableStateOf<Long?>(null)
    private var isPlaying by mutableStateOf(false)

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlayingNow: Boolean) {
            isPlaying = isPlayingNow
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) isPlaying = false
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            isPlaying = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HeartbeatTheme {
                HeartbeatApp(currentSongId, isPlaying, ::play, ::pause, ::stop)
            }
        }
    }

    private fun play(song: Song) {
        val url = song.audioUrl.trim()
        if (url.isBlank()) return
        player?.removeListener(playerListener)
        player?.release()
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            exoPlayer.addListener(playerListener)
            exoPlayer.setMediaItem(MediaItem.fromUri(url))
            currentSongId = song.id
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    private fun pause() {
        player?.pause()
        isPlaying = false
    }

    private fun stop() {
        player?.removeListener(playerListener)
        player?.stop()
        player?.release()
        player = null
        currentSongId = null
        isPlaying = false
    }

    override fun onDestroy() {
        player?.removeListener(playerListener)
        player?.release()
        player = null
        super.onDestroy()
    }
}

@Composable
private fun HeartbeatTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@Composable
private fun CoverImage(url: String, contentDescription: String?, modifier: Modifier, thumbnail: Boolean = false) {
    val context = LocalContext.current
    val imageUrl = if (thumbnail) thumbnailUrl(url) else url.trim()
    val request = remember(imageUrl, thumbnail) {
        ImageRequest.Builder(context)
            .data(imageUrl.ifBlank { null })
            .size(if (thumbnail) 144 else 900)
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        placeholder = painterResource(android.R.drawable.ic_menu_gallery),
        error = painterResource(android.R.drawable.ic_menu_gallery),
        fallback = painterResource(android.R.drawable.ic_menu_gallery)
    )
}

private fun versionAsSong(parent: Song, version: SongVersion): Song = Song(
    id = version.id,
    title = parent.title,
    artist = version.artist,
    genre = parent.genre,
    language = parent.language,
    mood = parent.mood,
    description = parent.description,
    lyrics = parent.lyrics,
    coverUrl = version.coverUrl,
    audioUrl = version.audioUrl,
    releaseDate = parent.releaseDate
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeartbeatApp(
    currentSongId: Long?,
    isPlaying: Boolean,
    onPlay: (Song) -> Unit,
    onPause: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<Song?>(null) }
    var versions by remember { mutableStateOf<List<SongVersion>>(emptyList()) }
    var versionsLoading by remember { mutableStateOf(false) }
    var visibleVersionCount by remember { mutableStateOf(3) }
    var query by remember { mutableStateOf("") }
    val favorites = remember { FavoriteStore(context) }
    var favoriteIds by remember { mutableStateOf(favorites.ids()) }
    val versionListState = rememberLazyListState()

    LaunchedEffect(retryKey) {
        loading = true
        try {
            songs = fetchSongs()
            error = null
        } catch (_: Exception) {
            error = "Unable to load songs. Please check your connection."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(selected?.id) {
        val song = selected ?: return@LaunchedEffect
        versionsLoading = true
        visibleVersionCount = 3
        versions = fetchVersions(song)
        versionsLoading = false
    }

    LaunchedEffect(versionListState, versions.size, visibleVersionCount) {
        snapshotFlow { versionListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                if (versions.size > visibleVersionCount && lastVisible >= visibleVersionCount) {
                    visibleVersionCount = minOf(visibleVersionCount + 3, versions.size)
                }
            }
    }

    val visibleSongs = songs.filter { song ->
        val q = query.trim().lowercase()
        q.isBlank() || listOf(song.title, song.artist, song.genre, song.language, song.mood)
            .joinToString(" ").lowercase().contains(q)
    }.let { list -> if (tab == 2) list.filter { favoriteIds.contains(it.id) } else list }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("HEARTBEAT HEAVEN", fontWeight = FontWeight.Bold)
                    Text("Original Music by Madushanka", fontSize = 11.sp)
                }
            })
        },
        bottomBar = {
            NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Default.Search, null) }, label = { Text("Search") })
                NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Default.Favorite, null) }, label = { Text("Favorites") })
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (tab == 1) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    label = { Text("Search songs, artists...") },
                    singleLine = true
                )
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (error != null) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { retryKey++ }) { Text("Retry") }
                    }
                }
            } else if (selected != null) {
                SongDetails(
                    song = selected!!,
                    versions = versions.take(visibleVersionCount),
                    totalVersions = versions.size,
                    versionsLoading = versionsLoading,
                    listState = versionListState,
                    isFavorite = favoriteIds.contains(selected!!.id),
                    currentSongId = currentSongId,
                    isPlaying = isPlaying,
                    onBack = { selected = null },
                    onPlay = { if (currentSongId == selected!!.id && isPlaying) onPause() else onPlay(selected!!) },
                    onPlayVersion = { version -> onPlay(versionAsSong(selected!!, version)) },
                    onFavorite = { favoriteIds = favorites.toggle(selected!!.id) },
                    onShare = { shareSong(context, selected!!) }
                )
            } else {
                if (tab == 0) Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Your music, your moments.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Fast, lightweight music listening", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(visibleSongs, key = { it.id }) { song ->
                        SongCard(
                            song = song,
                            isFavorite = favoriteIds.contains(song.id),
                            isPlaying = currentSongId == song.id && isPlaying,
                            onClick = { selected = song },
                            onPlay = { if (currentSongId == song.id && isPlaying) onPause() else onPlay(song) },
                            onFavorite = { favoriteIds = favorites.toggle(song.id) }
                        )
                    }
                }
            }
        }

        if (currentSongId != null && selected == null) {
            songs.firstOrNull { it.id == currentSongId }?.let { MiniPlayer(it, isPlaying, onPlay, onPause, onClose) }
        }
    }
}

@Composable
private fun SongCard(
    song: Song,
    isFavorite: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onFavorite: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(song.coverUrl, song.title, Modifier.size(56.dp), thumbnail = true)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(song.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${song.language} • ${song.genre}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onFavorite) {
                Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorite")
            }
            IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, "Play") }
        }
    }
}

@Composable
private fun SongDetails(
    song: Song,
    versions: List<SongVersion>,
    totalVersions: Int,
    versionsLoading: Boolean,
    listState: LazyListState,
    isFavorite: Boolean,
    currentSongId: Long?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayVersion: (SongVersion) -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Button(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.height(4.dp))
            CoverImage(song.coverUrl, song.title, Modifier.fillMaxWidth().height(300.dp))
            Spacer(Modifier.height(8.dp))
            Text(song.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(song.artist, style = MaterialTheme.typography.titleMedium)
            Text("${song.language} • ${song.genre} • ${song.mood}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlay) { Icon(Icons.Default.PlayArrow, null); Text(if (currentSongId == song.id && isPlaying) " Pause" else " Play") }
                IconButton(onClick = onFavorite) { Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorite") }
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Share") }
            }
            if (song.description.isNotBlank()) {
                Text("About", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(song.description)
            }
        }
        item {
            Text("Original & Versions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("More versions load as you scroll.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (versionsLoading) CircularProgressIndicator(modifier = Modifier.padding(vertical = 8.dp))
            else if (totalVersions == 0) Text("No extra versions available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(versions, key = { it.id }) { version ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    CoverImage(version.coverUrl, version.versionName, Modifier.size(52.dp), thumbnail = true)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(version.versionName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(version.artist, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onPlayVersion(version) }) { Icon(Icons.Default.PlayArrow, "Play ${version.versionName}") }
                }
            }
        }
        if (song.lyrics.isNotBlank()) item {
            Text("Lyrics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(song.lyrics)
        }
    }
}

@Composable
private fun MiniPlayer(song: Song, isPlaying: Boolean, onPlay: (Song) -> Unit, onPause: () -> Unit, onClose: () -> Unit) {
    Surface(shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(song.coverUrl, null, Modifier.size(48.dp), thumbnail = true)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(song.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { if (isPlaying) onPause() else onPlay(song) }) { Icon(Icons.Default.PlayArrow, "Play") }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
        }
    }
}

private class FavoriteStore(context: Context) {
    private val prefs = context.getSharedPreferences("heartbeat_favorites", Context.MODE_PRIVATE)

    fun ids(): Set<Long> = prefs.getStringSet("ids", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

    fun toggle(id: Long): Set<Long> {
        val next = ids().toMutableSet()
        if (!next.add(id)) next.remove(id)
        prefs.edit().putStringSet("ids", next.map { it.toString() }.toSet()).apply()
        return next
    }
}

private fun shareSong(context: Context, song: Song) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "${song.title} — ${song.artist}\n$API_BASE/song.html?id=${song.id}")
    }
    context.startActivity(Intent.createChooser(intent, "Share song"))
}
