package com.heartbeatheaven.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val ADMIN_OWNER_URL = "https://fafvhyeesenpimxncupp.supabase.co/functions/v1/owner-chat"
private const val ADMIN_OWNER_KEY = "sb_" + "publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"

private data class OwnerConversationRow(val id:String,val label:String,val category:String,val status:String,val updatedAt:String)
private data class OwnerAdminMessage(val id:String,val body:String,val senderType:String,val createdAt:String)

private fun adminOwnerRequest(context: Context, payload: JSONObject): JSONObject = runCatching {
    val session = AuthApi(context).currentSession() ?: error("Please log in again.")
    if (!session.profile.isAdmin) error("Admin access required.")
    val c = URL(ADMIN_OWNER_URL).openConnection() as HttpURLConnection
    try {
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 20000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("apikey", ADMIN_OWNER_KEY)
        c.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = if (text.isBlank()) JSONObject() else JSONObject(text)
        if (c.responseCode !in 200..299) error(json.optString("error").ifBlank { "Owner messages request failed (${c.responseCode})." })
        json
    } finally { c.disconnect() }
}.getOrElse { throw it }

class OwnerMessagesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { OwnerMessagesScreen { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnerMessagesScreen(onBack:()->Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var conversations by remember { mutableStateOf<List<OwnerConversationRow>>(emptyList()) }
    var selected by remember { mutableStateOf<OwnerConversationRow?>(null) }
    var messages by remember { mutableStateOf<List<OwnerAdminMessage>>(emptyList()) }
    var reply by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<OwnerConversationRow?>(null) }

    fun loadInbox() {
        scope.launch {
            loading = true; error = null
            runCatching { withContext(Dispatchers.IO) { adminOwnerRequest(context, JSONObject().put("action","list")) } }
                .onSuccess { root ->
                    val a = root.optJSONArray("conversations") ?: JSONArray()
                    conversations = buildList { for (i in 0 until a.length()) { val o=a.optJSONObject(i)?:continue; add(OwnerConversationRow(o.optString("id"),o.optString("label","Guest User"),o.optString("category","General"),o.optString("status","open"),o.optString("updated_at"))) } }
                }.onFailure { error = it.message ?: "Could not load owner messages." }
            loading = false
        }
    }

    fun openConversation(c: OwnerConversationRow) {
        selected = c; messages = emptyList(); error = null; busy = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { adminOwnerRequest(context, JSONObject().put("action","list").put("conversation_id",c.id)) } }
                .onSuccess { root ->
                    val a=root.optJSONArray("messages")?:JSONArray(); messages=buildList { for(i in 0 until a.length()){val o=a.optJSONObject(i)?:continue;add(OwnerAdminMessage(o.optString("id"),o.optString("body"),o.optString("sender_type"),o.optString("created_at"))) } }
                    scope.launch(Dispatchers.IO) { runCatching { adminOwnerRequest(context,JSONObject().put("action","read").put("conversation_id",c.id)) } }
                }.onFailure { error=it.message ?: "Could not load conversation." }
            busy=false
        }
    }

    fun sendReply() {
        val c=selected?:return; val body=reply.trim(); if(body.isEmpty()||busy)return
        reply=""; busy=true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { adminOwnerRequest(context,JSONObject().put("action","reply").put("conversation_id",c.id).put("body",body)); adminOwnerRequest(context,JSONObject().put("action","list").put("conversation_id",c.id)) } }
                .onSuccess { root -> val a=root.optJSONArray("messages")?:JSONArray(); messages=buildList{for(i in 0 until a.length()){val o=a.optJSONObject(i)?:continue;add(OwnerAdminMessage(o.optString("id"),o.optString("body"),o.optString("sender_type"),o.optString("created_at"))) };loadInbox() }
                }.onFailure { error=it.message ?: "Could not send reply." }
            busy=false
        }
    }

    fun deleteConversation(c:OwnerConversationRow) {
        busy=true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { adminOwnerRequest(context,JSONObject().put("action","delete").put("conversation_id",c.id)) } }
                .onSuccess { selected=null; messages=emptyList(); deleteTarget=null; loadInbox() }
                .onFailure { error=it.message ?: "Could not delete conversation." }
            busy=false
        }
    }

    LaunchedEffect(Unit) { loadInbox() }

    deleteTarget?.let { c ->
        AlertDialog(onDismissRequest={if(!busy)deleteTarget=null},title={Text("Delete report?")},text={Text("Delete this conversation and all of its messages? This cannot be undone.")},confirmButton={TextButton(enabled=!busy,onClick={deleteConversation(c)}){Text("Delete",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(enabled=!busy,onClick={deleteTarget=null}){Text("Cancel")}})
    }

    if(selected==null){
        Scaffold(topBar={TopAppBar(title={Text("Owner Messages")},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Back")}})}){pad->
            Column(Modifier.fillMaxSize().padding(pad)){
                Text("Messages from users and guests",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=16.dp,vertical=8.dp))
                error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(horizontal=16.dp))}
                if(loading)Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
                else if(conversations.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("No owner messages yet.")}
                else LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){items(conversations,key={it.id}){c->Card(onClick={openConversation(c)},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Row(Modifier.fillMaxWidth()){Text(c.label,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));Text(c.status.replaceFirstChar{it.uppercase()})};Text(c.category,color=MaterialTheme.colorScheme.primary);if(c.updatedAt.isNotBlank())Text(c.updatedAt,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}
            }
        }
    } else {
        val c=selected!!
        Scaffold(topBar={TopAppBar(title={Column{Text(c.label);Text(c.category,style=MaterialTheme.typography.labelSmall)}},navigationIcon={IconButton(onClick={selected=null;messages=emptyList();loadInbox()}){Icon(Icons.Default.ArrowBack,"Back")}},actions={IconButton(enabled=!busy,onClick={deleteTarget=c}){Icon(Icons.Default.Delete,"Delete report")}})}){pad->
            Column(Modifier.fillMaxSize().padding(pad)){
                if(busy&&messages.isEmpty())Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
                else LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(messages,key={it.id}){m->Row(Modifier.fillMaxWidth(),horizontalArrangement=if(m.senderType=="admin")Arrangement.End else Arrangement.Start){Surface(color=if(m.senderType=="admin")MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium,modifier=Modifier.widthIn(max=320.dp)){Column(Modifier.padding(12.dp)){Text(m.body);Text(if(m.senderType=="admin")"Owner" else c.label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}};error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}}
                Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.Bottom){OutlinedTextField(reply,{if(it.length<=2000)reply=it},Modifier.weight(1f),placeholder={Text("Reply to user…")},maxLines=4,enabled=!busy);Spacer(Modifier.width(8.dp));IconButton(enabled=reply.trim().isNotEmpty()&&!busy,onClick={sendReply()}){Icon(Icons.Default.Send,"Send reply")}}
            }
        }
    }
}
