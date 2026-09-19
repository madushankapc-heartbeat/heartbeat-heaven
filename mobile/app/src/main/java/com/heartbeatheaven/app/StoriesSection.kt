package com.heartbeatheaven.app

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

private const val STORIES_URL = "https://fafvhyeesenpimxncupp.supabase.co"
private const val STORIES_KEY = "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"
private const val STORY_BUCKET = "stories"
private const val STORY_MAX_BYTES = 20L * 1024L * 1024L

internal data class StoryItem(
    val id: String,
    val userId: String,
    val username: String,
    val avatarUrl: String,
    val mediaType: String,
    val mediaUrl: String,
    val storagePath: String,
    val caption: String,
    val createdAt: String,
    val liked: Boolean
)

private class StoriesApi(private val auth: AuthApi, initial: AuthSession) {
    private var session = initial
    fun userId() = session.profile.id

    private fun request(path: String, method: String, body: String? = null): String {
        auth.currentSession()?.let { session = it }
        val c = URL(STORIES_URL + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("apikey", STORIES_KEY)
            c.setRequestProperty("Authorization", "Bearer " + session.accessToken)
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (c.responseCode !in 200..299) throw IllegalStateException(JSONObject(text).optString("message").ifBlank { "Request failed (" + c.responseCode + ")" })
            return text
        } finally { c.disconnect() }
    }

    suspend fun load(): List<StoryItem> = withContext(Dispatchers.IO) {
        val now = URLEncoder.encode(Instant.now().toString(), "UTF-8")
        val a = JSONArray(request("/rest/v1/stories?expires_at=gt." + now + "&select=id,user_id,media_type,storage_path,caption,created_at&order=created_at.asc&limit=500", "GET"))
        if (a.length() == 0) return@withContext emptyList()
        val ids = buildSet { for (i in 0 until a.length()) add(a.getJSONObject(i).optString("user_id")) }
        val p = JSONArray(request("/rest/v1/public_profiles?id=in.(" + ids.joinToString(",") + ")&select=id,username,avatar_url&limit=500", "GET"))
        val profiles = buildMap {
            for (i in 0 until p.length()) {
                val o = p.getJSONObject(i)
                put(o.optString("id"), o.optString("username") to o.optString("avatar_url").takeUnless { it == "null" }.orEmpty())
            }
        }
        val mine = userId()
        val l = runCatching { JSONArray(request("/rest/v1/story_likes?user_id=eq." + mine + "&select=story_id&limit=500", "GET")) }.getOrDefault(JSONArray())
        val liked = buildSet { for (i in 0 until l.length()) add(l.getJSONObject(i).optString("story_id")) }
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val uid = o.optString("user_id")
                val prof = profiles[uid] ?: continue
                val path = o.optString("storage_path").takeUnless { it == "null" }.orEmpty()
                val url = if (path.isBlank()) "" else STORIES_URL + "/storage/v1/object/public/" + STORY_BUCKET + "/" + path
                add(StoryItem(
                    id = o.optString("id"),
                    userId = uid,
                    username = prof.first,
                    avatarUrl = prof.second,
                    mediaType = o.optString("media_type"),
                    mediaUrl = url,
                    storagePath = path,
                    caption = o.optString("caption"),
                    createdAt = o.optString("created_at"),
                    liked = o.optString("id") in liked
                ))
            }
        }.sortedWith(compareBy<StoryItem> { it.userId != mine }.thenBy { it.createdAt })
    }

    suspend fun upload(context: Context, uri: Uri, mime: String): String = withContext(Dispatchers.IO) {
        val size = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        } ?: -1L
        if (size > STORY_MAX_BYTES) throw IllegalStateException("Story media must be 20 MB or smaller.")
        val ext = mime.substringAfter('/').substringBefore(';').replace("jpeg", "jpg")
        val path = userId() + "/" + UUID.randomUUID() + "." + ext
        val c = URL(STORIES_URL + "/storage/v1/object/" + STORY_BUCKET + "/" + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 15000; c.readTimeout = 60000
            c.setRequestProperty("apikey", STORIES_KEY)
            c.setRequestProperty("Authorization", "Bearer " + session.accessToken)
            c.setRequestProperty("Content-Type", mime)
            c.outputStream.use { out -> context.contentResolver.openInputStream(uri)?.use { it.copyTo(out) } ?: error("Could not read media.") }
            if (c.responseCode !in 200..299) error("Story upload failed (" + c.responseCode + ").")
            path
        } finally { c.disconnect() }
    }

    suspend fun create(type: String, path: String?, caption: String) = withContext(Dispatchers.IO) {
        val p = JSONObject().put("user_id", userId()).put("media_type", type).put("caption", caption.trim().take(1000))
        if (path != null) p.put("storage_path", path)
        request("/rest/v1/stories", "POST", p.toString())
    }

    suspend fun updateCaption(story: StoryItem, caption: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/stories?id=eq." + story.id + "&user_id=eq." + userId(), "PATCH", JSONObject().put("caption", caption.trim().take(1000)).toString())
    }

    suspend fun delete(story: StoryItem) = withContext(Dispatchers.IO) {
        request("/rest/v1/stories?id=eq." + story.id + "&user_id=eq." + userId(), "DELETE")
        if (story.storagePath.isNotBlank()) {
            val c = URL(STORIES_URL + "/storage/v1/object/" + STORY_BUCKET).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "DELETE"; c.doOutput = true
                c.setRequestProperty("apikey", STORIES_KEY)
                c.setRequestProperty("Authorization", "Bearer " + session.accessToken)
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("prefixes", JSONArray().put(story.storagePath)).toString().toByteArray()) }
                c.inputStream.close()
            } catch (_: Throwable) {} finally { c.disconnect() }
        }
    }

    suspend fun view(id: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/story_views?on_conflict=story_id,viewer_id", "POST", JSONObject().put("story_id", id).put("viewer_id", userId()).toString())
    }

    suspend fun toggleLike(story: StoryItem, liked: Boolean) = withContext(Dispatchers.IO) {
        if (liked) request("/rest/v1/story_likes?story_id=eq." + story.id + "&user_id=eq." + userId(), "DELETE")
        else request("/rest/v1/story_likes", "POST", JSONObject().put("story_id", story.id).put("user_id", userId()).toString())
    }

    suspend fun reply(story: StoryItem, text: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/messages", "POST", JSONObject().put("sender_id", userId()).put("receiver_id", story.userId).put("body", "↩️ Replied to your story: " + text.trim().take(1500)).toString())
    }
}

@Composable
internal fun StoriesSection(session: AuthSession, onStatus: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(session.accessToken) { StoriesApi(AuthApi(context), session) }
    var stories by remember { mutableStateOf<List<StoryItem>>(emptyList()) }
    var createOpen by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<List<StoryItem>>(emptyList()) }
    var viewerIndex by remember { mutableIntStateOf(0) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedMime by remember { mutableStateOf("") }
    var newCaption by remember { mutableStateOf("") }
    var editingStory by remember { mutableStateOf<StoryItem?>(null) }
    var editCaption by remember { mutableStateOf("") }

    fun reload() { scope.launch { runCatching { stories = api.load() }.onFailure { onStatus(it.message ?: "Could not load stories.") } } }

    LaunchedEffect(session.accessToken) { reload() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) onStatus("Please choose an image or video.")
        else { pickedUri = uri; pickedMime = mime; newCaption = ""; createOpen = true }
    }

    val mine = stories.filter { it.userId == api.userId() }
    val groups = stories.groupBy { it.userId }
    val others = groups.keys.filter { it != api.userId() }.mapNotNull { groups[it]?.firstOrNull() }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Stories", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text("48h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp).clickable {
                    if (mine.isEmpty()) createOpen = true else { viewer = mine; viewerIndex = 0 }
                }) {
                    Box {
                        if (mine.firstOrNull()?.avatarUrl?.isNotBlank() == true) AsyncImage(mine.first().avatarUrl, "Your story", Modifier.size(58.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        else Surface(Modifier.size(58.dp), CircleShape, tonalElevation = 2.dp) { Box(contentAlignment = Alignment.Center) { Text("+") } }
                        if (mine.isEmpty()) Surface(Modifier.size(22.dp).align(Alignment.BottomEnd), CircleShape, color = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Add, null, Modifier.padding(3.dp), tint = Color.White) }
                    }
                    Text("You", maxLines = 1)
                }
            }
            items(others, key = { it.userId }) { s ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(68.dp).clickable {
                    viewer = groups[s.userId].orEmpty(); viewerIndex = 0
                }) {
                    if (s.avatarUrl.isNotBlank()) AsyncImage(s.avatarUrl, s.username, Modifier.size(58.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    else Surface(Modifier.size(58.dp), CircleShape) { Box(contentAlignment = Alignment.Center) { Text(s.username.take(1).uppercase()) } }
                    Text(s.username, maxLines = 1)
                }
            }
        }
        TextButton(onClick = { pickedUri = null; pickedMime = ""; newCaption = ""; createOpen = true }) { Text("Add Story") }
    }

    if (createOpen) {
        val mediaUri = pickedUri
        val canPost = mediaUri != null || newCaption.isNotBlank()
        AlertDialog(
            onDismissRequest = { createOpen = false },
            title = { Text("Add Story") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (mediaUri != null) {
                        if (pickedMime.startsWith("image/")) {
                            AsyncImage(mediaUri, "Story preview", Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
                        } else {
                            AndroidView(
                                factory = { ctx -> VideoView(ctx).apply {
                                    setVideoURI(mediaUri)
                                    setOnPreparedListener { mp -> mp.isLooping = true; start() }
                                }},
                                modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp))
                            )
                        }
                        Text("Preview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("Create a text story", style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(onClick = { picker.launch("*/*") }) {
                        Text(if (mediaUri == null) "Choose photo / video" else "Change media")
                    }
                    OutlinedTextField(
                        value = newCaption,
                        onValueChange = { if (it.length <= 1000) newCaption = it },
                        label = { Text(if (mediaUri == null) "Story text" else "Caption") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = canPost, onClick = {
                    scope.launch {
                        runCatching {
                            val path = mediaUri?.let { api.upload(context, it, pickedMime) }
                            api.create(if (path == null) "text" else if (pickedMime.startsWith("video/")) "video" else "image", path, newCaption)
                            createOpen = false; pickedUri = null; pickedMime = ""; newCaption = ""; reload()
                            onStatus("Story posted.")
                        }.onFailure { onStatus(it.message ?: "Could not create story.") }
                    }
                }) { Text("Post Story") }
            },
            dismissButton = { TextButton(onClick = { createOpen = false }) { Text("Cancel") } }
        )
    }

    if (editingStory != null) {
        val story = editingStory!!
        AlertDialog(
            onDismissRequest = { editingStory = null },
            title = { Text("Edit Story") },
            text = {
                OutlinedTextField(editCaption, { if (it.length <= 1000) editCaption = it }, label = { Text("Caption") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(enabled = editCaption.isNotBlank() || story.mediaType != "text", onClick = {
                    scope.launch {
                        runCatching {
                            api.updateCaption(story, editCaption)
                            editingStory = null
                            reload()
                            onStatus("Story updated.")
                        }.onFailure { onStatus(it.message ?: "Could not update story.") }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingStory = null }) { Text("Cancel") } }
        )
    }

    if (viewer.isNotEmpty()) StoryViewer(api, viewer, viewerIndex, { viewerIndex = it }, { viewer = emptyList() }, { reload() }, { story -> editCaption = story.caption; editingStory = story }, onStatus)
}

@Composable
private fun StoryViewer(api: StoriesApi, stories: List<StoryItem>, index: Int, setIndex: (Int) -> Unit, close: () -> Unit, reload: () -> Unit, onEdit: (StoryItem) -> Unit, onStatus: (String) -> Unit) {
    if (index !in stories.indices) { close(); return }
    val story = stories[index]
    val scope = rememberCoroutineScope()
    var liked by remember(story.id) { mutableStateOf(story.liked) }
    var reply by remember(story.id) { mutableStateOf("") }
    var videoPlaying by remember(story.id) { mutableStateOf(false) }
    val mine = story.userId == api.userId()
    fun next() { if (index + 1 < stories.size) setIndex(index + 1) else close() }
    fun prev() { if (index > 0) setIndex(index - 1) }

    LaunchedEffect(story.id) {
        runCatching { api.view(story.id) }
        if (story.mediaType != "video") { delay(5000); next() }
    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).pointerInput(story.id) {
                var total = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, amount -> total += amount },
                    onDragEnd = { if (total < -80) next() else if (total > 80) prev(); total = 0f }
                )
            }
        ) {
            if (story.mediaType == "image") AsyncImage(story.mediaUrl, story.username, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else if (story.mediaType == "video") {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(Uri.parse(story.mediaUrl))
                            setOnPreparedListener { videoPlaying = true; start() }
                            setOnCompletionListener { next() }
                            layoutParams = ViewGroup.LayoutParams(-1, -1)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    Text(story.caption, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                }
            }
            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    stories.forEachIndexed { i, _ -> LinearProgressIndicator(if (i < index) 1f else 0f, Modifier.weight(1f)) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (story.avatarUrl.isNotBlank()) AsyncImage(story.avatarUrl, story.username, Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(8.dp)); Text(story.username, color = Color.White)
                    Spacer(Modifier.weight(1f)); IconButton(onClick = close) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                }
            }
            Row(Modifier.align(Alignment.Center).fillMaxWidth()) {
                Box(Modifier.weight(1f).height(280.dp).clickable { prev() })
                Box(Modifier.weight(1f).height(280.dp).clickable { next() })
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
                if (story.caption.isNotBlank() && story.mediaType != "text") Text(story.caption, color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (mine) {
                        IconButton(onClick = { onEdit(story) }) { Icon(Icons.Default.Edit, "Edit story", tint = Color.White) }
                        IconButton(onClick = { scope.launch { runCatching { api.delete(story); reload(); next() }.onFailure { onStatus(it.message ?: "Could not delete story.") } } }) { Icon(Icons.Default.Delete, "Delete", tint = Color.White) }
                    }
                    else {
                        IconButton(onClick = { scope.launch { runCatching { api.toggleLike(story, liked); liked = !liked; reload() }.onFailure { onStatus(it.message ?: "Could not like story.") } } }) { Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Like", tint = Color.White) }
                        OutlinedTextField(reply, { reply = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Reply to story") })
                        IconButton(enabled = reply.isNotBlank(), onClick = { scope.launch { runCatching { api.reply(story, reply); reply = ""; onStatus("Story reply sent.") }.onFailure { onStatus(it.message ?: "Could not send reply.") } } }) { Icon(Icons.Default.Send, "Send", tint = Color.White) }
                    }
                }
            }
        }
    }
}
