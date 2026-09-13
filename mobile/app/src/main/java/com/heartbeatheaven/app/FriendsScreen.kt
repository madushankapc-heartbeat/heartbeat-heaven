package com.heartbeatheaven.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val FRIENDS_SUPABASE_URL = "https://fafvhyeesenpimxncupp.supabase.co"
private const val FRIENDS_KEY = "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"

private data class FriendUser(val id: String, val username: String, val gender: String)
private data class FriendRequest(val id: String, val user: FriendUser, val incoming: Boolean)
private data class ChatMessage(val id: String, val senderId: String, val body: String, val createdAt: String)

private class FriendsApi(context: Context) {
    private val prefs = context.getSharedPreferences("heartbeat_auth", Context.MODE_PRIVATE)
    private fun token() = prefs.getString("access_token", null) ?: error("Please log in first")
    private fun me() = prefs.getString("user_id", null) ?: error("Please log in first")

    private fun request(path: String, method: String, body: String? = null): String {
        val c = (URL(FRIENDS_SUPABASE_URL + path).openConnection() as HttpURLConnection)
        c.requestMethod = method
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("apikey", FRIENDS_KEY)
        c.setRequestProperty("Authorization", "Bearer ${token()}")
        c.setRequestProperty("Accept", "application/json")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (c.responseCode !in 200..299) throw IllegalStateException(runCatching { JSONObject(text).optString("message").ifBlank { JSONObject(text).optString("msg") } }.getOrDefault("Request failed (${c.responseCode})"))
        c.disconnect(); return text
    }

    suspend fun search(username: String): List<FriendUser> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(username.trim(), "UTF-8")
        val a = JSONArray(request("/rest/v1/profiles?username=ilike.*$q*&select=id,username,gender&limit=20", "GET"))
        buildList { for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("id") != me()) add(FriendUser(o.optString("id"), o.optString("username"), o.optString("gender"))) } }
    }

    suspend fun requests(): List<FriendRequest> = withContext(Dispatchers.IO) {
        val mine = me(); val a = JSONArray(request("/rest/v1/friendships?or=(requester_id.eq.$mine,addressee_id.eq.$mine)&status=eq.pending&select=id,requester_id,addressee_id", "GET"))
        buildList {
            for (i in 0 until a.length()) {
                val o=a.getJSONObject(i); val incoming=o.optString("addressee_id")==mine; val uid=if(incoming)o.optString("requester_id") else o.optString("addressee_id")
                val p=JSONArray(request("/rest/v1/profiles?id=eq.$uid&select=id,username,gender", "GET")); if(p.length()>0){val u=p.getJSONObject(0); add(FriendRequest(o.optString("id"), FriendUser(uid,u.optString("username"),u.optString("gender")), incoming))}
            }
        }
    }

    suspend fun send(userId: String) = withContext(Dispatchers.IO) { request("/rest/v1/friendships", "POST", JSONObject().put("requester_id", me()).put("addressee_id", userId).toString()) }
    suspend fun accept(id: String) = withContext(Dispatchers.IO) { request("/rest/v1/friendships?id=eq.$id", "PATCH", JSONObject().put("status", "accepted").toString()) }
    suspend fun friends(): List<FriendUser> = withContext(Dispatchers.IO) {
        val mine=me(); val a=JSONArray(request("/rest/v1/friendships?or=(requester_id.eq.$mine,addressee_id.eq.$mine)&status=eq.accepted&select=requester_id,addressee_id", "GET")); buildList {
            for(i in 0 until a.length()){val o=a.getJSONObject(i); val uid=if(o.optString("requester_id")==mine)o.optString("addressee_id") else o.optString("requester_id"); val p=JSONArray(request("/rest/v1/profiles?id=eq.$uid&select=id,username,gender", "GET")); if(p.length()>0){val u=p.getJSONObject(0); add(FriendUser(uid,u.optString("username"),u.optString("gender")))}}
        }
    }
    suspend fun messages(other: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val mine=me(); val a=JSONArray(request("/rest/v1/messages?or=(and(sender_id.eq.$mine,receiver_id.eq.$other),and(sender_id.eq.$other,receiver_id.eq.$mine))&order=created_at.asc&limit=100", "GET")); buildList { for(i in 0 until a.length()){val o=a.getJSONObject(i); add(ChatMessage(o.optString("id"),o.optString("sender_id"),o.optString("body"),o.optString("created_at"))) } }
    }
    suspend fun sendMessage(other:String, body:String) = withContext(Dispatchers.IO) { request("/rest/v1/messages", "POST", JSONObject().put("sender_id",me()).put("receiver_id",other).put("body",body.trim()).toString()) }
}

@Composable
internal fun FriendsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember { FriendsApi(context) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FriendUser>>(emptyList()) }
    var friends by remember { mutableStateOf<List<FriendUser>>(emptyList()) }
    var requests by remember { mutableStateOf<List<FriendRequest>>(emptyList()) }
    var selected by remember { mutableStateOf<FriendUser?>(null) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        busy=true
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            runCatching { withContext(Dispatchers.IO) { Triple(api.friends(), api.requests(), if(selected!=null) api.messages(selected!!.id) else emptyList()) } }.onSuccess { (f,r,m)-> friends=f; requests=r; messages=m }.onFailure { message=it.message }
            busy=false
        }
    }
    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(selected?.id) { selected?.let { messages=runCatching { api.messages(it.id) }.getOrElse { emptyList() } } }

    if (selected != null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) { Text(selected!!.username, style=MaterialTheme.typography.headlineSmall); TextButton(onClick={selected=null}){Text("Back")} }
            LazyColumn(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(8.dp)) { items(messages){ m -> Card(Modifier.fillMaxWidth()){Text(m.body, Modifier.padding(12.dp))} } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text,{text=it},Modifier.weight(1f),label={Text("Message")},singleLine=true)
                IconButton(enabled=text.isNotBlank(),onClick={val t=text;text="";kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main){runCatching{api.sendMessage(selected!!.id,t);messages=api.messages(selected!!.id)}.onFailure{message=it.message}}}){Icon(Icons.Default.Send,"Send")}
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Friends", style=MaterialTheme.typography.headlineMedium)
        Text("Find friends by username and chat privately.", color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(query,{query=it},Modifier.weight(1f),label={Text("Search username")},singleLine=true);Button(enabled=query.isNotBlank(),onClick={kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main){results=runCatching{api.search(query)}.getOrElse{message=it.message;emptyList()}}}){Text("Search")}}
        if(results.isNotEmpty()) { Text("People",style=MaterialTheme.typography.titleMedium); results.forEach{u->ListItem(headlineContent={Text(u.username)},supportingContent={Text(u.gender.replaceFirstChar{it.uppercase()})},trailingContent={Button(onClick={kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main){runCatching{api.send(u.id);message="Friend request sent."}.onFailure{message=it.message}}}){Text("Add")}})} }
        if(requests.isNotEmpty()){Text("Friend requests",style=MaterialTheme.typography.titleMedium);requests.forEach{r->ListItem(headlineContent={Text(r.user.username)},trailingContent={if(r.incoming)Button(onClick={kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main){runCatching{api.accept(r.id);reload()}.onFailure{message=it.message}}}){Text("Accept")}else Text("Pending")})}}
        Text("Friends",style=MaterialTheme.typography.titleMedium)
        if(friends.isEmpty()) Text("No friends yet. Search for a username above.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        friends.forEach{u->ListItem(headlineContent={Text(u.username)},leadingContent={Icon(Icons.Default.Person,"Friend")},trailingContent={TextButton(onClick={selected=u}){Text("Chat")}})}
        message?.let{Text(it,color=MaterialTheme.colorScheme.primary)}
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
