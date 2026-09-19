// CI build source is intentionally kept explicit.
package com.heartbeatheaven.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val API_BASE = "https://heartbeat-heaven.onrender.com"
private const val YOUTUBE_URL = "https://www.youtube.com/@ViBORA-r1i"

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
    val releaseDate: String?,
    val versionName: String = "Original Version"
)

private fun songFromJson(o: JSONObject): Song = Song(
    id = o.optLong("id"), title = o.optString("title"), artist = o.optString("artist"), genre = o.optString("genre"),
    language = o.optString("language"), mood = o.optString("mood"), description = o.optString("description"), lyrics = o.optString("lyrics"),
    coverUrl = o.optString("cover_url"), audioUrl = o.optString("audio_url"), releaseDate = o.optString("release_date").takeIf { it.isNotBlank() },
    versionName = o.optString("version_name").ifBlank { "Original Version" }
)

private suspend fun fetchSongs(): List<Song> = withContext(Dispatchers.IO) {
    val connection = java.net.URL("$API_BASE/api/songs").openConnection() as java.net.HttpURLConnection
    try {
        connection.requestMethod = "GET"; connection.connectTimeout = 15000; connection.readTimeout = 20000
        if (connection.responseCode !in 200..299) error("Server returned ${connection.responseCode}")
        val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        buildList { for (i in 0 until array.length()) add(songFromJson(array.getJSONObject(i))) }
    } finally { connection.disconnect() }
}

private suspend fun fetchSongHub(id: Long): Pair<Song, List<Song>> = withContext(Dispatchers.IO) {
    val connection = java.net.URL("$API_BASE/api/songs/$id/hub").openConnection() as java.net.HttpURLConnection
    try {
        connection.requestMethod = "GET"; connection.connectTimeout = 15000; connection.readTimeout = 20000
        if (connection.responseCode !in 200..299) error("Server returned ${connection.responseCode}")
        val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val original = songFromJson(root.getJSONObject("original")); val versionsJson = root.optJSONArray("versions") ?: JSONArray()
        val versions = buildList { for (i in 0 until versionsJson.length()) add(songFromJson(versionsJson.getJSONObject(i))) }
        original to versions
    } finally { connection.disconnect() }
}

private fun thumbnailUrl(url: String): String {
    val clean = url.trim(); if (clean.isBlank()) return ""
    return if (clean.contains("/storage/v1/object/public/")) clean.replace("/storage/v1/object/public/", "/storage/v1/render/image/public/") + "?width=144&height=144&resize=cover&quality=55" else clean
}

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var currentSong by mutableStateOf<Song?>(null)
    private var currentSongId by mutableStateOf<Long?>(null)
    private var isPlaying by mutableStateOf(false)
    private var positionMs by mutableLongStateOf(0L)
    private var durationMs by mutableLongStateOf(0L)

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlayingNow: Boolean) { isPlaying = isPlayingNow }
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) durationMs = player?.duration?.coerceAtLeast(0L) ?: 0L
            if (state == Player.STATE_ENDED) stopPlayback()
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) { isPlaying = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LaunchedEffect(currentSongId, isPlaying) {
                    while (currentSongId != null) {
                        positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
                        durationMs = player?.duration?.takeIf { it > 0 } ?: durationMs
                        delay(if (isPlaying) 250L else 500L)
                    }
                }
                HeartbeatApp(currentSong, currentSongId, isPlaying, positionMs, durationMs, ::playSong, ::pausePlayback, ::seekPlayback, ::stopPlayback)
            }
        }
    }

    private fun playSong(song: Song) {
        val url = song.audioUrl.trim(); if (url.isBlank()) return
        if (currentSongId == song.id && player != null) { player?.play(); return }
        player?.removeListener(listener); player?.release(); currentSong = song; currentSongId = song.id; positionMs = 0L; durationMs = 0L
        player = ExoPlayer.Builder(this).build().also { p -> p.addListener(listener); p.setMediaItem(MediaItem.fromUri(url)); p.prepare(); p.playWhenReady = true }
    }
    private fun pausePlayback() { player?.pause(); isPlaying = false }
    private fun seekPlayback(ms: Long) { val value = ms.coerceAtLeast(0L); player?.seekTo(value); positionMs = value }
    private fun stopPlayback() { player?.removeListener(listener); player?.stop(); player?.release(); player = null; currentSong = null; currentSongId = null; isPlaying = false; positionMs = 0L; durationMs = 0L }
    override fun onDestroy() { player?.removeListener(listener); player?.release(); player = null; super.onDestroy() }
}

@Composable
private fun Cover(url: String, modifier: Modifier, thumb: Boolean = false) {
    val context = LocalContext.current; val image = if (thumb) thumbnailUrl(url) else url.trim()
    val request = remember(image, thumb) { ImageRequest.Builder(context).data(image.ifBlank { null }).size(if (thumb) 144 else 900).crossfade(true).build() }
    AsyncImage(model = request, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop, placeholder = painterResource(android.R.drawable.ic_menu_gallery), error = painterResource(android.R.drawable.ic_menu_gallery))
}

private fun timeText(ms: Long): String { val seconds = (ms.coerceAtLeast(0L) / 1000).toInt(); return "%d:%02d".format(seconds / 60, seconds % 60) }

@Composable
private fun Progress(position: Long, duration: Long, onSeek: (Long) -> Unit, small: Boolean = false) {
    val d = duration.coerceAtLeast(0L); val p = position.coerceIn(0L, if (d > 0) d else 0L)
    Column { Slider(value = if (d > 0) p.toFloat() else 0f, onValueChange = { if (d > 0) onSeek(it.toLong()) }, valueRange = 0f..d.toFloat().coerceAtLeast(1f), enabled = d > 0)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(timeText(p), fontSize = if (small) 10.sp else 12.sp); Text(timeText(d), fontSize = if (small) 10.sp else 12.sp) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeartbeatApp(song: Song?, songId: Long?, playing: Boolean, position: Long, duration: Long, onPlay: (Song) -> Unit, onPause: () -> Unit, onSeek: (Long) -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }; var loading by remember { mutableStateOf(true) }; var error by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(0) }; var search by remember { mutableStateOf("") }; var selected by remember { mutableStateOf<Song?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val favorites = remember { FavoriteStore(context) }; var favoriteIds by remember { mutableStateOf(favorites.ids()) }
    val authSession = remember { AuthApi(context).currentSession() }

    suspend fun refreshSongs() {
        try {
            songs = fetchSongs()
            error = null
        } catch (e: Exception) {
            error = "Unable to load songs. Please check your connection."
        }
    }

    LaunchedEffect(Unit) {
        refreshSongs()
        loading = false
    }

    LaunchedEffect(authSession?.accessToken) {
        if (authSession != null) {
            val auth = AuthApi(context)
            val callApi = CallApi(context, auth)
            while (true) {
                runCatching {
                    val incoming = withContext(Dispatchers.IO) { callApi.incomingRinging().firstOrNull() }
                    if (incoming != null) {
                        val prefs = context.getSharedPreferences("heartbeat_call_state", Context.MODE_PRIVATE)
                        val already = prefs.getString("incoming_call_launched_id", "").orEmpty()
                        if (already != incoming.id) {
                            prefs.edit().putString("incoming_call_launched_id", incoming.id).apply()
                            CallNotificationManager.showIncoming(context, incoming.id, incoming.callType)
                            val activity = context as? MainActivity
                            if (activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
                                context.startActivity(Intent(context, CallActivity::class.java).apply {
                                    putExtra("call_id", incoming.id)
                                    putExtra("call_type", incoming.callType)
                                })
                            }
                        }
                    }
                }
                delay(2000)
            }
        }
    }

    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            refreshing = true
            if (tab != 1 && tab != 4) refreshSongs()
            refreshing = false
        }
    }
    val filtered = songs.filter { s -> val q = search.trim().lowercase(); q.isBlank() || listOf(s.title, s.artist, s.genre, s.language, s.mood).joinToString(" ").lowercase().contains(q) }.filter { if (tab == 2) favoriteIds.contains(it.id) else true }

    Scaffold(
        topBar = { TopAppBar(
        title = { Column { Text("HEARTBEAT HEAVEN", fontWeight = FontWeight.Bold); Text("Original Music by Madushanka", fontSize = 11.sp) } },
        actions = {
            IconButton(
                enabled = !refreshing,
                onClick = {
                    refreshTrigger += 1
                }
            ) {
                if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, "Refresh")
            }
            if (authSession?.profile?.isAdmin == true) {
                IconButton(onClick = { context.startActivity(Intent(context, StudioActivity::class.java)) }) {
                    Icon(Icons.Default.LibraryMusic, "Studio")
                }
            }
        }
    ) },
        bottomBar = { Column {
            if (song != null && selected == null) MiniPlayer(song, playing, position, duration, onPlay, onPause, onSeek, onStop)
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0; selected = null }, { Icon(Icons.Default.Home, "Home") }, label = { Text("Home") })
                NavigationBarItem(tab == 1, { tab = 1; selected = null }, { Icon(Icons.Default.People, "Friends") }, label = { Text("Friends") })
                NavigationBarItem(tab == 2, { tab = 2; selected = null }, { Icon(Icons.Default.Favorite, "Favorites") }, label = { Text("Favorites") })
                NavigationBarItem(tab == 3, { tab = 3; selected = null }, { Icon(Icons.Default.Search, "Search") }, label = { Text("Search") })
                NavigationBarItem(tab == 4, { tab = 4; selected = null }, { Icon(Icons.Default.Person, "Profile") }, label = { Text("Profile") })
            }
        } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                tab == 1 -> FriendsScreen(refreshTrigger)
                tab == 4 -> AccountScreen(refreshTrigger)
                selected != null -> DetailScreen(selected!!, songId, playing, position, duration, { selected = null }, { target -> if (songId == target.id && playing) onPause() else onPlay(target) }, onSeek, { favoriteIds = favorites.toggle(selected!!.id) }, { shareSong(context, selected!!) })
                else -> {
                    if (tab == 3) OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(16.dp), label = { Text("Search songs, artists...") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
                    if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else if (error != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!) }
                    else {
                        if (tab == 0) Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Text("Your music, your moments.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Fast, lightweight music listening", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(12.dp)); Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(YOUTUBE_URL))) }, Modifier.fillMaxWidth()) { Text("▶ Watch ViBORA on YouTube") } }
                        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(filtered, key = { it.id }) { s -> SongCard(s, favoriteIds.contains(s.id), { selected = s }, { favoriteIds = favorites.toggle(s.id) }) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongCard(s: Song, favorite: Boolean, onOpen: () -> Unit, onFavorite: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(16.dp)) { Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Cover(s.coverUrl, Modifier.size(56.dp), true); Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(s.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(s.artist, fontSize = 13.sp); Text("${s.language} • ${s.genre}", fontSize = 11.sp) }; IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorite") } } }
}

@Composable
private fun MiniPlayer(s: Song, playing: Boolean, position: Long, duration: Long, onPlay: (Song) -> Unit, onPause: () -> Unit, onSeek: (Long) -> Unit, onStop: () -> Unit) {
    Surface(shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Cover(s.coverUrl, Modifier.size(46.dp), true); Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(s.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(s.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }; IconButton(onClick = { if (playing) onPause() else onPlay(s) }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play") }; IconButton(onClick = onStop) { Icon(Icons.Default.Close, "Close") } }; Progress(position, duration, onSeek, true) } }
}

@Composable
private fun DetailScreen(s: Song, songId: Long?, playing: Boolean, position: Long, duration: Long, onBack: () -> Unit, onPlaySong: (Song) -> Unit, onSeek: (Long) -> Unit, onFavorite: () -> Unit, onShare: () -> Unit) {
    var original by remember(s.id) { mutableStateOf(s) }; var versions by remember(s.id) { mutableStateOf<List<Song>>(emptyList()) }; var loadingVersions by remember(s.id) { mutableStateOf(true) }; var versionsError by remember(s.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(s.id) { try { val hub = fetchSongHub(s.id); original = hub.first; versions = hub.second } catch (e: Exception) { versionsError = "Unable to load versions." } finally { loadingVersions = false } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Button(onClick = onBack) { Text("← Back") }; Cover(original.coverUrl, Modifier.fillMaxWidth().height(300.dp)); Text(original.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(original.artist); Text("${original.language} • ${original.genre} • ${original.mood}"); Progress(position, duration, onSeek); Row { Button(onClick = { onPlaySong(original) }) { Icon(if (songId == original.id && playing) Icons.Default.Pause else Icons.Default.PlayArrow, null); Text(if (songId == original.id && playing) " Pause" else " Play") }; IconButton(onClick = onFavorite) { Icon(Icons.Default.Favorite, "Favorite") }; IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Share") } }; if (original.description.isNotBlank()) Text(original.description) }
        item { Text("Versions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Original and additional versions", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
        if (loadingVersions) item { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        else if (versionsError != null) item { Text(versionsError!!, color = MaterialTheme.colorScheme.error) }
        else { item { VersionCard(original, songId, playing) { onPlaySong(original) } }; items(versions, key = { it.id }) { version -> VersionCard(version, songId, playing) { onPlaySong(version) } } }
        if (original.lyrics.isNotBlank()) item { Text("Lyrics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(original.lyrics) }
    }
}

@Composable
private fun VersionCard(version: Song, songId: Long?, playing: Boolean, onPlay: () -> Unit) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Cover(version.coverUrl, Modifier.size(58.dp), true); Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(version.versionName, fontWeight = FontWeight.Bold); Text(version.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }; val active = songId == version.id && playing; IconButton(onClick = onPlay) { Icon(if (active) Icons.Default.Pause else Icons.Default.PlayArrow, if (active) "Pause" else "Play") } } } }

private class FavoriteStore(context: Context) {
    private val prefs = context.getSharedPreferences("heartbeat_favorites", Context.MODE_PRIVATE)
    fun ids(): Set<Long> = prefs.getStringSet("ids", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    fun toggle(id: Long): Set<Long> { val next = ids().toMutableSet(); if (!next.add(id)) next.remove(id); prefs.edit().putStringSet("ids", next.map { it.toString() }.toSet()).apply(); return next }
}

private fun shareSong(context: Context, s: Song) {
    val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${s.title} — ${s.artist}\n$API_BASE/song.html?id=${s.id}") }
    context.startActivity(Intent.createChooser(intent, "Share song"))
}
