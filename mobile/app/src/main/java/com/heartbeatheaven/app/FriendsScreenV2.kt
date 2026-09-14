package com.heartbeatheaven.app

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CHAT_URL="https://fafvhyeesenpimxncupp.supabase.co"
private const val CHAT_KEY="sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"
private data class CUser(val id:String,val username:String,val lastSeen:String?)
private data class CMsg(val id:String,val sender:String,val receiver:String,val body:String,val created:String,val readAt:String?){
    val json:JSONObject? get()=runCatching{JSONObject(body)}.getOrNull()?.takeIf{it.optString("type")=="message"}
    val text:String get()=json?.optString("text").orEmpty().ifBlank{body}
    val edited:Boolean get()=json?.optBoolean("edited",false)==true
    val deleted:Boolean get()=json?.optBoolean("deleted",false)==true
    val reply:String? get()=json?.optString("reply_text").takeIf{!it.isNullOrBlank()}
    val reaction:String? get()=json?.optString("reaction").takeIf{!it.isNullOrBlank()}
}
private data class CRow(val user:CUser,val last:CMsg?,val unread:Int)

private class CApi(private val auth:AuthApi,initial:AuthSession){
    private var session=initial
    fun uid()=session.profile.id
    fun token()=session.accessToken
    private fun req(path:String,method:String,body:String?=null,prefer:String?=null):String{
        fun once():Pair<Int,String>{
            val c=URL(CHAT_URL+path).openConnection() as HttpURLConnection
            try{c.requestMethod=method;c.connectTimeout=12000;c.readTimeout=18000;c.setRequestProperty("apikey",CHAT_KEY);c.setRequestProperty("Authorization","Bearer ${session.accessToken}");c.setRequestProperty("Accept","application/json");if(prefer!=null)c.setRequestProperty("Prefer",prefer);if(body!=null){c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))}};val s=if(c.responseCode in 200..299)c.inputStream else c.errorStream;return c.responseCode to (s?.bufferedReader()?.use{it.readText()}.orEmpty())}finally{c.disconnect()}
        }
        auth.currentSession()?.let{session=it};var r=once();if(r.first==401){session=auth.currentSession()?:error("Session expired. Please log in again.");r=once()};if(r.first !in 200..299)error("Request failed (${r.first})");return r.second
    }
    private fun JSONObject.msg()=CMsg(optString("id"),optString("sender_id"),optString("receiver_id"),optString("body"),optString("created_at"),optString("read_at").ifBlank{null})
    private fun users(raw:String):List<CUser>{val a=JSONArray(raw);return buildList{for(i in 0 until a.length()){val o=a.getJSONObject(i);add(CUser(o.optString("id"),o.optString("username","User"),o.optString("last_seen_at").ifBlank{null}))}}}
    suspend fun friends():List<CUser>=withContext(Dispatchers.IO){val me=uid();val a=JSONArray(req("/rest/v1/friendships?or=(requester_id.eq.$me,addressee_id.eq.$me)&status=eq.accepted&select=requester_id,addressee_id","GET"));buildList{for(i in 0 until a.length()){val o=a.getJSONObject(i);val id=if(o.optString("requester_id")==me)o.optString("addressee_id")else o.optString("requester_id");users(req("/rest/v1/profiles?id=eq.$id&select=id,username,last_seen_at","GET")).firstOrNull()?.let{add(it)}}}}
    suspend fun online():List<CUser>=withContext(Dispatchers.IO){val cut=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.US).format(Date(System.currentTimeMillis()-120000));users(req("/rest/v1/profiles?last_seen_at=gte.${URLEncoder.encode(cut,"UTF-8")}&select=id,username,last_seen_at&limit=100","GET")).filter{it.id!=uid()}}
    suspend fun rows():List<CRow>=withContext(Dispatchers.IO){val me=uid();val fs=friends();val a=JSONArray(req("/rest/v1/messages?or=(sender_id.eq.$me,receiver_id.eq.$me)&select=id,sender_id,receiver_id,body,created_at,read_at&order=created_at.desc&limit=500","GET"));val all=buildList{for(i in 0 until a.length())add(a.getJSONObject(i).msg())};fs.map{u->CRow(u,all.firstOrNull{x->x.sender==u.id||x.receiver==u.id},all.count{x->x.sender==u.id&&x.receiver==me&&x.readAt==null})}.sortedByDescending{it.last?.created.orEmpty()}}
    suspend fun messages(other:String,limit:Int=60,before:String?=null):List<CMsg>=withContext(Dispatchers.IO){val me=uid();val b=before?.let{"&created_at=lt.${URLEncoder.encode(it,"UTF-8")}"}.orEmpty();val a=JSONArray(req("/rest/v1/messages?or=(and(sender_id.eq.$me,receiver_id.eq.$other),and(sender_id.eq.$other,receiver_id.eq.$me))&select=id,sender_id,receiver_id,body,created_at,read_at&order=created_at.desc&limit=$limit$b","GET"));buildList{for(i in 0 until a.length())add(a.getJSONObject(i).msg())}.asReversed()}
    suspend fun send(other:String,text:String,reply:CMsg?):CMsg=withContext(Dispatchers.IO){val p=JSONObject().put("type","message").put("text",text.trim());if(reply!=null)p.put("reply_to",reply.id).put("reply_text",reply.text);val a=JSONArray(req("/rest/v1/messages","POST",JSONObject().put("sender_id",uid()).put("receiver_id",other).put("body",p.toString()).toString(),"return=representation"));a.getJSONObject(0).msg()}
    suspend fun read(other:String)=withContext(Dispatchers.IO){val now=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.US).format(Date());req("/rest/v1/messages?sender_id=eq.$other&receiver_id=eq.${uid()}&read_at=is.null","PATCH",JSONObject().put("read_at",now).toString())}
    suspend fun edit(m:CMsg,text:String)=withContext(Dispatchers.IO){val p=m.json?:JSONObject().put("type","message").put("text",m.text);p.put("text",text.trim()).put("edited",true);req("/rest/v1/messages?id=eq.${m.id}&sender_id=eq.${uid()}","PATCH",JSONObject().put("body",p.toString()).toString())}
    suspend fun delete(m:CMsg)=withContext(Dispatchers.IO){val p=m.json?:JSONObject().put("type","message").put("text",m.text);p.put("deleted",true).put("text","Message deleted");req("/rest/v1/messages?id=eq.${m.id}&sender_id=eq.${uid()}","PATCH",JSONObject().put("body",p.toString()).toString())}
    suspend fun react(m:CMsg)=withContext(Dispatchers.IO){val p=m.json?:JSONObject().put("type","message").put("text",m.text);if(p.optString("reaction")=="❤️")p.remove("reaction")else p.put("reaction","❤️");req("/rest/v1/messages?id=eq.${m.id}","PATCH",JSONObject().put("body",p.toString()).toString())}
}

@Composable
internal fun FriendsScreenV2(){
    val context=LocalContext.current;val scope=rememberCoroutineScope();val auth=remember{AuthApi(context)}
    var session by remember{mutableStateOf<AuthSession?>(null)};var loading by remember{mutableStateOf(true)};var rows by remember{mutableStateOf<List<CRow>>(emptyList())};var online by remember{mutableStateOf<List<CUser>>(emptyList())};var selected by remember{mutableStateOf<CUser?>(null)};var messages by remember{mutableStateOf<List<CMsg>>(emptyList())};var input by remember{mutableStateOf("")};var reply by remember{mutableStateOf<CMsg?>(null)};var editing by remember{mutableStateOf<CMsg?>(null)};var search by remember{mutableStateOf("")};var error by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(Unit){session=withContext(Dispatchers.IO){auth.currentSession()};loading=false}
    val api=session?.let{remember(it.accessToken){CApi(auth,it)}}
    fun reload(){val a=api?:return;scope.launch{runCatching{rows=a.rows();online=a.online()}.onFailure{error=it.message}}}
    LaunchedEffect(api){if(api!=null){reload();while(true){delay(5000);runCatching{rows=api.rows();online=api.online()}}}}
    LaunchedEffect(selected?.id){val u=selected?:return@LaunchedEffect;val a=api?:return@LaunchedEffect;messages=runCatching{a.messages(u.id)}.getOrDefault(emptyList());runCatching{a.read(u.id)}}
    DisposableEffect(api,selected?.id){val sid=selected?.id;val rt=api?.let{a->RealtimeMessagesClient({a.token()},a.uid(),CHAT_KEY){id,sender,body,created->scope.launch(Dispatchers.Main){if(sender==sid&&messages.none{it.id==id}){messages=messages+CMsg(id,sender,a.uid(),body,created,null);runCatching{a.read(sender)}}}}};rt?.start();onDispose{rt?.stop()}}
    if(loading){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()};return};if(api==null){Column(Modifier.fillMaxSize().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("Login required",style=MaterialTheme.typography.headlineSmall);Text("Please log in from Profile first.")};return}
    if(selected!=null){val u=selected!!;val state=rememberLazyListState();val shown=messages.filter{search.isBlank()||it.text.contains(search,true)};LaunchedEffect(messages.size){if(messages.isNotEmpty())state.animateScrollToItem(messages.lastIndex)};Column(Modifier.fillMaxSize()){
        Surface(shadowElevation=3.dp){Row(Modifier.fillMaxWidth().padding(6.dp),verticalAlignment=Alignment.CenterVertically){IconButton({selected=null;reply=null;editing=null;search=""}){Icon(Icons.Default.ArrowBack,"Back")};Column(Modifier.weight(1f)){Text(u.username,fontWeight=FontWeight.SemiBold);Text(if(online.any{it.id==u.id})"Online" else "Last seen ${u.lastSeen?.replace('T',' ')?.take(16)?:"recently"}",style=MaterialTheme.typography.bodySmall)};IconButton({search=if(search.isBlank())" " else ""}){Icon(Icons.Default.Search,"Search")}}}
        if(search.isNotBlank())OutlinedTextField(search,{search=it},Modifier.fillMaxWidth().padding(8.dp),singleLine=true,placeholder={Text("Search in chat")})
        LazyColumn(state=state,modifier=Modifier.weight(1f).padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){items(shown,key={it.id}){m->val mine=m.sender==api.uid();Row(Modifier.fillMaxWidth(),horizontalArrangement=if(mine)Arrangement.End else Arrangement.Start){Column(horizontalAlignment=if(mine)Alignment.End else Alignment.Start){m.reply?.let{Text("↪ $it",style=MaterialTheme.typography.labelSmall)};Surface(color=if(mine)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(18.dp),modifier=Modifier.clickable{reply=m}){Column(Modifier.padding(12.dp)){Text(if(m.deleted)"Message deleted" else m.text);Row{Text(m.created.takeLast(8).take(5),style=MaterialTheme.typography.labelSmall);if(m.edited)Text(" edited",style=MaterialTheme.typography.labelSmall);if(mine)Text(if(m.readAt!=null)"  ✓✓" else "  ✓");m.reaction?.let{Text("  $it")}}}};if(mine&&!m.deleted){TextButton({editing=m;input=m.text}){Text("Edit")};TextButton({scope.launch{runCatching{api.delete(m);messages=api.messages(u.id);rows=api.rows()}}}){Text("Delete")}};TextButton({scope.launch{runCatching{api.react(m);messages=api.messages(u.id)}}}){Text("❤️")}}}}}
        reply?.let{Surface(tonalElevation=2.dp){Row(Modifier.fillMaxWidth().padding(6.dp)){Text("Reply: ${it.text}",Modifier.weight(1f),maxLines=1);IconButton({reply=null}){Icon(Icons.Default.Close,"Cancel")}}}};editing?.let{Surface(tonalElevation=2.dp){Row(Modifier.fillMaxWidth().padding(6.dp)){Text("Editing",Modifier.weight(1f));IconButton({editing=null;input=""}){Icon(Icons.Default.Close,"Cancel")}}}}
        Row(Modifier.fillMaxWidth().padding(7.dp),verticalAlignment=Alignment.Bottom){IconButton({Toast.makeText(context,"Media upload will be connected to secure chat Storage",Toast.LENGTH_LONG).show()}){Icon(Icons.Default.AttachFile,"Attach")};OutlinedTextField(input,{input=it},Modifier.weight(1f),placeholder={Text("Message")},maxLines=4);IconButton(enabled=input.isNotBlank(),onClick={val t=input.trim();val e=editing;val r=reply;input="";scope.launch{runCatching{if(e!=null)api.edit(e,t)else api.send(u.id,t,r);messages=api.messages(u.id);rows=api.rows();editing=null;reply=null}.onFailure{error=it.message}}}){Icon(Icons.Default.Send,"Send")}};error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(horizontal=10.dp))}}
        return}
    Column(Modifier.fillMaxSize().padding(16.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text("Friends",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.weight(1f));IconButton({reload()}){Icon(Icons.Default.Refresh,"Refresh")}};Text("Accepted friends • online status • unread messages",color=MaterialTheme.colorScheme.onSurfaceVariant);if(online.isNotEmpty()){Text("Online now",fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=10.dp));online.forEach{Text(it.username,modifier=Modifier.padding(5.dp))}};Text("Chats",fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=10.dp));LazyColumn(Modifier.weight(1f)){items(rows,key={it.user.id}){r->ListItem(modifier=Modifier.clickable{selected=r.user},headlineContent={Row(verticalAlignment=Alignment.CenterVertically){Text(r.user.username,fontWeight=if(r.unread>0)FontWeight.Bold else FontWeight.Normal);if(r.unread>0){Spacer(Modifier.width(8.dp));Badge{Text(r.unread.toString())}}}},supportingContent={Text(r.last?.text?:"No messages yet",maxLines=1)},trailingContent={Text(if(online.any{it.id==r.user.id})"Online" else r.last?.created?.takeLast(8)?.take(5).orEmpty())})}}};error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}
}
