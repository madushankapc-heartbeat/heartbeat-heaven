val prepareFinalAppFixes = tasks.register("prepareFinalAppFixes") {
    doLast {
        fun patchFile(path: String, transform: (String) -> String) {
            val file = project.file(path)
            val text = file.readText()
            val updated = transform(text)
            if (updated != text) file.writeText(updated)
        }

        patchFile("src/main/java/com/heartbeatheaven/app/UsersAdminActivity.kt") { text ->
            var t = text
            if (!t.contains("import android.content.Intent")) t = t.replace("import android.os.Bundle", "import android.content.Intent\nimport android.os.Bundle")
            t.replace("Button(onClick = { showMessages = true }, modifier = Modifier.fillMaxWidth())", "Button(onClick = { context.startActivity(Intent(context, OwnerMessagesActivity::class.java)) }, modifier = Modifier.fillMaxWidth())")
        }

        patchFile("src/main/java/com/heartbeatheaven/app/FriendsScreen.kt") { text ->
            var t = text
            if (!t.contains("material3.pulltorefresh.PullToRefreshBox")) t = t.replace("import androidx.compose.material3.*", "import androidx.compose.material3.*\nimport androidx.compose.material3.pulltorefresh.PullToRefreshBox\nimport androidx.compose.material3.pulltorefresh.rememberPullToRefreshState")
            if (!t.contains("@OptIn(ExperimentalMaterial3Api::class)\n@Composable\ninternal fun FriendsScreen")) t = t.replace("@Composable\ninternal fun FriendsScreen", "@OptIn(ExperimentalMaterial3Api::class)\n@Composable\ninternal fun FriendsScreen")
            if (!t.contains("@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)")) t = "@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)\n" + t
            val marker = "    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {"
            if (marker in t && !t.contains("PullToRefreshBox(isRefreshing = busy")) {
                t = t.replace(marker, "    val refreshState = rememberPullToRefreshState()\n    PullToRefreshBox(isRefreshing = busy, onRefresh = { reload() }, state = refreshState, modifier = Modifier.fillMaxSize()) {\n" + marker)
                val end = "    }\n}"
                if (t.endsWith(end)) t = t.removeSuffix(end) + "    }\n    }\n}"
            }
            t
        }

        patchFile("src/main/java/com/heartbeatheaven/app/MainActivity.kt") { text ->
            var t = text
            if (!t.contains("import kotlinx.coroutines.launch")) t = t.replace("import kotlinx.coroutines.delay", "import kotlinx.coroutines.delay\nimport kotlinx.coroutines.launch")
            if (!t.contains("material3.pulltorefresh.PullToRefreshBox")) t = t.replace("import androidx.compose.material3.*", "import androidx.compose.material3.*\nimport androidx.compose.material3.pulltorefresh.PullToRefreshBox\nimport androidx.compose.material3.pulltorefresh.rememberPullToRefreshState")
            if (!t.contains("var refreshing by remember { mutableStateOf(false) }")) t = t.replace("val authSession = remember { AuthApi(context).currentSession() }", "val authSession = remember { AuthApi(context).currentSession() }\n    val refreshScope = rememberCoroutineScope()\n    var refreshing by remember { mutableStateOf(false) }\n    val refreshState = rememberPullToRefreshState()")
            val outer = "        Column(Modifier.fillMaxSize().padding(padding)) {"
            if (outer in t && !t.contains("PullToRefreshBox(isRefreshing = refreshing")) {
                val wrapped = "        PullToRefreshBox(\n            isRefreshing = refreshing,\n            onRefresh = {\n                if (!refreshing && (tab == 0 || tab == 3)) {\n                    refreshing = true\n                    refreshScope.launch {\n                        runCatching { fetchSongs() }\n                            .onSuccess { songs = it; error = null }\n                            .onFailure { error = \"Unable to refresh. Please check your connection.\" }\n                        refreshing = false\n                    }\n                }\n            },\n            state = refreshState,\n            modifier = Modifier.fillMaxSize()\n        ) {\n" + outer
                t = t.replace(outer, wrapped)
                val exactEnd = "        }\n    }\n}\n\n@Composable\nprivate fun SongCard"
                val idx = t.lastIndexOf(exactEnd)
                if (idx >= 0) t = t.substring(0, idx) + "        }\n        }\n    }\n}\n\n@Composable\nprivate fun SongCard" + t.substring(idx + exactEnd.length)
            }

            if (!t.contains("private var playbackQueue: List<Song> = emptyList()")) {
                t = t.replace(
                    "private var durationMs by mutableLongStateOf(0L)",
                    "private var durationMs by mutableLongStateOf(0L)\n    private var playbackQueue: List<Song> = emptyList()\n    private var playbackIndex: Int = -1"
                )
            }
            t = t.replace("if (state == Player.STATE_ENDED) stopPlayback()", "if (state == Player.STATE_ENDED) playNext()")
            t = t.replace(
                "HeartbeatApp(currentSong, currentSongId, isPlaying, positionMs, durationMs, ::playSong, ::pausePlayback, ::seekPlayback, ::stopPlayback)",
                "HeartbeatApp(currentSong, currentSongId, isPlaying, positionMs, durationMs, ::playSong, ::pausePlayback, ::seekPlayback, ::stopPlayback, ::previousPlayback, ::nextPlayback, ::setPlaybackQueue)"
            )
            if (!t.contains("private fun setPlaybackQueue(songs: List<Song>)")) {
                t = t.replace(
                    "    private fun playSong(song: Song) {",
                    "    private fun setPlaybackQueue(songs: List<Song>) {\n        playbackQueue = songs\n        playbackIndex = currentSongId?.let { id -> songs.indexOfFirst { it.id == id } } ?: -1\n    }\n\n    private fun playNext() {\n        val nextIndex = playbackIndex + 1\n        if (nextIndex in playbackQueue.indices) playSong(playbackQueue[nextIndex]) else stopPlayback()\n    }\n\n    private fun nextPlayback() { playNext() }\n\n    private fun previousPlayback() {\n        if (player?.currentPosition?.let { it > 3000L } == true) {\n            player?.seekTo(0L)\n            positionMs = 0L\n            return\n        }\n        val previousIndex = playbackIndex - 1\n        if (previousIndex in playbackQueue.indices) playSong(playbackQueue[previousIndex]) else player?.seekTo(0L)\n    }\n\n    private fun playSong(song: Song) {"
                )
            }
            t = t.replace(
                "val url = song.audioUrl.trim(); if (url.isBlank()) return\n        if (currentSongId == song.id && player != null)",
                "val url = song.audioUrl.trim(); if (url.isBlank()) return\n        playbackIndex = playbackQueue.indexOfFirst { it.id == song.id }\n        if (currentSongId == song.id && player != null)"
            )
            t = t.replace(
                "private fun HeartbeatApp(song: Song?, songId: Long?, playing: Boolean, position: Long, duration: Long, onPlay: (Song) -> Unit, onPause: () -> Unit, onSeek: (Long) -> Unit, onStop: () -> Unit)",
                "private fun HeartbeatApp(song: Song?, songId: Long?, playing: Boolean, position: Long, duration: Long, onPlay: (Song) -> Unit, onPause: () -> Unit, onSeek: (Long) -> Unit, onStop: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onQueueChanged: (List<Song>) -> Unit)"
            )
            t = t.replace("LaunchedEffect(Unit) { try { songs = fetchSongs() }", "LaunchedEffect(Unit) { try { songs = fetchSongs(); onQueueChanged(songs) }")
            t = t.replace(
                "MiniPlayer(song, playing, position, duration, onPlay, onPause, onSeek, onStop)",
                "MiniPlayer(song, playing, position, duration, onPlay, onPause, onSeek, onStop, onPrevious, onNext)"
            )
            t = t.replace(
                "private fun MiniPlayer(s: Song, playing: Boolean, position: Long, duration: Long, onPlay: (Song) -> Unit, onPause: () -> Unit, onSeek: (Long) -> Unit, onStop: () -> Unit)",
                "private fun MiniPlayer(s: Song, playing: Boolean, position: Long, duration: Long, onPlay: (Song) -> Unit, onPause: () -> Unit, onSeek: (Long) -> Unit, onStop: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit)"
            )
            t = t.replace(
                "Cover(s.coverUrl, Modifier.size(46.dp), true); Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(s.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(s.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }; IconButton(onClick = { if (playing) onPause() else onPlay(s) }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) \"Pause\" else \"Play\") }; IconButton(onClick = onStop) { Icon(Icons.Default.Close, \"Close\") }",
                "Cover(s.coverUrl, Modifier.size(46.dp), true); Column(Modifier.weight(1f).padding(horizontal = 8.dp)) { Text(s.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(s.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }; IconButton(onClick = onPrevious, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.SkipPrevious, \"Previous\") }; IconButton(onClick = { if (playing) onPause() else onPlay(s) }, modifier = Modifier.size(40.dp)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) \"Pause\" else \"Play\") }; IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.SkipNext, \"Next\") }; IconButton(onClick = onStop, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Close, \"Close\") }"
            )

            val errorUi = "else if (error != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!) }"
            val errorUiWithRetry = "else if (error != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {\n                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {\n                            Text(error!!, textAlign = androidx.compose.ui.text.style.TextAlign.Center)\n                            Button(onClick = {\n                                if (!refreshing) {\n                                    refreshing = true\n                                    refreshScope.launch {\n                                        runCatching { fetchSongs() }\n                                            .onSuccess { songs = it; error = null }\n                                            .onFailure { error = \"Unable to refresh. Please check your connection.\" }\n                                        refreshing = false\n                                    }\n                                }\n                            }) {\n                                Text(\"↻ Refresh\")\n                            }\n                        }\n                    }"
            t = t.replace(errorUi, errorUiWithRetry)
            val nullableMessageFields = listOf("delivered_at", "read_at", "edited_at", "reply_to_id", "deleted_at")
            nullableMessageFields.forEach { field ->
                t = t.replace(
                    "row.optString(\"$field\")",
                    "row.optString(\"$field\").takeUnless { it == \"null\" }.orEmpty()"
                )
                t = t.replace(
                    "objectValue.optString(\"$field\")",
                    "objectValue.optString(\"$field\").takeUnless { it == \"null\" }.orEmpty()"
                )
            }
            t
        }
    }
}

tasks.named("preBuild").configure { dependsOn(prepareFinalAppFixes) }
