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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray

private const val API_BASE = "https://heartbeat-heaven.onrender.com"
private const val YOUTUBE_URL = "https://www.youtube.com/@ViBORA-r1i"

private data class Song(val id: Long,val title: String,val artist: String,val genre: String,val language: String,val mood: String,val description: String,val lyrics: String,val coverUrl: String,val audioUrl: String,val releaseDate: String?)

private suspend fun fetchSongs(): List<Song> = withContext(Dispatchers.IO) {
    val c = java.net.URL("$API_BASE/api/songs").openConnection() as java.net.HttpURLConnection
    try {
        c.requestMethod = "GET"; c.connectTimeout = 15000; c.readTimeout = 20000
        if (c.responseCode !in 200..299) error("Server returned ${c.responseCode}")
        val a = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        buildList { for (i in 0 until a.length()) { val o=a.getJSONObject(i); add(Song(o.optLong("id"),o.optString("title"),o.optString("artist"),o.optString("genre"),o.optString("language"),o.optString("mood"),o.optString("description"),o.optString("lyrics"),o.optString("cover_url"),o.optString("audio_url"),o.optString("release_date").takeIf{it.isNotBlank()})) } }
    } finally { c.disconnect() }
}

private fun thumbnailUrl(url: String): String {
    val clean=url.trim(); if(clean.isBlank()) return ""
    return if(clean.contains("/storage/v1/object/public/")) clean.replace("/storage/v1/object/public/","/storage/v1/render/image/public/")+"?width=144&height=144&resize=cover&quality=55" else clean
}

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var currentSong by mutableStateOf<Song?>(null)
    private var currentSongId by mutableStateOf<Long?>(null)
    private var isPlaying by mutableStateOf(false)
    private var positionMs by mutableLongStateOf(0L)
    private var durationMs by mutableLongStateOf(0L)
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(value: Boolean) { isPlaying=value }
        override fun onPlaybackStateChanged(state: Int) {
            if(state==Player.STATE_READY) durationMs=player?.duration?.coerceAtLeast(0L) ?: 0L
            if(state==Player.STATE_ENDED) stopPlayback()
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) { isPlaying=false }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LaunchedEffect(currentSongId,isPlaying) {
                    while(currentSongId!=null) { positionMs=player?.currentPosition?.coerceAtLeast(0L) ?: 0L; durationMs=player?.duration?.takeIf{it>0} ?: durationMs; delay(if(isPlaying)250L else 500L) }
                }
                HeartbeatApp(currentSong,currentSongId,isPlaying,positionMs,durationMs,::playSong,::pausePlayback,::seekPlayback,::stopPlayback)
            }
        }
    }
    private fun playSong(song: Song) {
        val url=song.audioUrl.trim(); if(url.isBlank()) return
        if(currentSongId==song.id && player!=null) { player?.play(); return }
        player?.removeListener(listener); player?.release()
        currentSong=song; currentSongId=song.id; positionMs=0L; durationMs=0L
        player=ExoPlayer.Builder(this).build().also { p -> p.addListener(listener); p.setMediaItem(MediaItem.fromUri(url)); p.prepare(); p.playWhenReady=true }
    }
    private fun pausePlayback() { player?.pause(); isPlaying=false }
    private fun seekPlayback(ms: Long) { player?.seekTo(ms.coerceAtLeast(0L)); positionMs=ms.coerceAtLeast(0L) }
    private fun stopPlayback() { player?.removeListener(listener); player?.stop(); player?.release(); player=null; currentSong=null; currentSongId=null; isPlaying=false; positionMs=0L; durationMs=0L }
    override fun onDestroy() { player?.removeListener(listener); player?.release(); player=null; super.onDestroy() }
}

@Composable
private fun Cover(url:String, modifier:Modifier, thumb:Boolean=false) {
    val context=LocalContext.current; val image=if(thumb) thumbnailUrl(url) else url.trim()
    val request=remember(image,thumb){ImageRequest.Builder(context).data(image.ifBlank{null}).size(if(thumb)144 else 900).crossfade(true).build()}
    AsyncImage(model=request,contentDescription=null,modifier=modifier,contentScale=ContentScale.Crop,placeholder=painterResource(android.R.drawable.ic_menu_gallery),error=painterResource(android.R.drawable.ic_menu_gallery))
}

private fun timeText(ms:Long):String { val s=(ms.coerceAtLeast(0L)/1000).toInt(); return "%d:%02d".format(s/60,s%60) }

@Composable
private fun Progress(position:Long,duration:Long,onSeek:(Long)->Unit,small:Boolean=false) {
    val d=duration.coerceAtLeast(0L); val p=position.coerceIn(0L,if(d>0)d else 0L)
    Column { Slider(value=if(d>0)p.toFloat() else 0f,onValueChange={if(d>0)onSeek(it.toLong())},valueRange=0f..d.toFloat().coerceAtLeast(1f),enabled=d>0); Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(timeText(p),fontSize=if(small)10.sp else 12.sp);Text(timeText(d),fontSize=if(small)10.sp else 12.sp)} }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeartbeatApp(song:Song?,songId:Long?,playing:Boolean,position:Long,duration:Long,onPlay:(Song)->Unit,onPause:()->Unit,onSeek:(Long)->Unit,onStop:()->Unit) {
    val context=LocalContext.current
    var songs by remember{mutableStateOf<List<Song>>(emptyList())}; var loading by remember{mutableStateOf(true)}; var error by remember{mutableStateOf<String?>(null)}
    var tab by remember{mutableStateOf(0)}; var search by remember{mutableStateOf("")}; var selected by remember{mutableStateOf<Song?>(null)}
    val favorites=remember{FavoriteStore(context)}; var favs by remember{mutableStateOf(favorites.ids())}
    LaunchedEffect(Unit){try{songs=fetchSongs()}catch(e:Exception){error="Unable to load songs. Please check your connection."}finally{loading=false}}
    val list=songs.filter{val q=search.trim().lowercase();q.isBlank()||listOf(it.title,it.artist,it.genre,it.language,it.mood).joinToString(" ").lowercase().contains(q)}.let{if(tab==2)it.filter{favs.contains(it.id)}else it}
    Scaffold(topBar={TopAppBar(title={Column{Text("HEARTBEAT HEAVEN",fontWeight=FontWeight.Bold);Text("Original Music by Madushanka",fontSize=11.sp)}})},bottomBar={Column{if(song!=null && selected==null) MiniPlayer(song,playing,position,duration,onPlay,onPause,onSeek,onStop);NavigationBar{NavigationBarItem(selected=tab==0,onClick={tab=0},icon={Icon(Icons.Default.Home,null)},label={Text("Home")});NavigationBarItem(selected=tab==1,onClick={tab=1},icon={Icon(Icons.Default.Search,null)},label={Text("Search")});NavigationBarItem(selected=tab==2,onClick={tab=2},icon={Icon(Icons.Default.Favorite,null)},label={Text("Favorites")});NavigationBarItem(selected=tab==3,onClick={tab=3},icon={Icon(Icons.Default.Person,null)},label={Text("Profile")})}}}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if(tab==3) AccountScreen()
            else if(selected!=null) DetailScreen(selected!!,songId,playing,position,duration,{selected=null},{if(songId==selected!!.id&&playing)onPause()else onPlay(selected!!)},onSeek,{favs=favorites.toggle(selected!!.id)},{shareSong(context,selected!!)})
            else { if(tab==1) OutlinedTextField(search,{search=it},Modifier.fillMaxWidth().padding(16.dp),label={Text("Search songs, artists...")},leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true)
                if(loading) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()} else if(error!=null) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(error!!)} else { if(tab==0) Column(Modifier.padding(16.dp)){Text("Your music, your moments.",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("Fast, lightweight music listening",color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(12.dp));Button(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(YOUTUBE_URL)))},Modifier.fillMaxWidth()){Text("▶ Watch ViBORA on YouTube")}};LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){items(list,key={it.id}){s->SongCard(s,favs.contains(s.id),songId==s.id&&playing,{selected=s},{if(songId==s.id&&playing)onPause()else onPlay(s)},{favs=favorites.toggle(s.id)})}}}}
            }
        }
    }
}

@Composable private fun SongCard(s:Song,favorite:Boolean,playing:Boolean,onOpen:()->Unit,onPlay:()->Unit,onFavorite:()->Unit)=Card(Modifier.fillMaxWidth().clickable(onClick=onOpen),shape=RoundedCornerShape(16.dp)){Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically){Cover(s.coverUrl,Modifier.size(56.dp),true);Column(Modifier.weight(1f).padding(horizontal=10.dp)){Text(s.title,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(s.artist,fontSize=13.sp);Text("${s.language} • ${s.genre}",fontSize=11.sp)};IconButton(onFavorite){Icon(if(favorite)Icons.Default.Favorite else Icons.Default.FavoriteBorder,"Favorite")};IconButton(onPlay){Icon(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,"Play")}}}

@Composable private fun MiniPlayer(s:Song,playing:Boolean,position:Long,duration:Long,onPlay:(Song)->Unit,onPause:()->Unit,onSeek:(Long)->Unit,onStop:()->Unit)=Surface(shadowElevation=8.dp,modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(horizontal=10.dp,vertical=4.dp)){Row(verticalAlignment=Alignment.CenterVertically){Cover(s.coverUrl,Modifier.size(46.dp),true);Column(Modifier.weight(1f).padding(horizontal=10.dp)){Text(s.title,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(s.artist,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)};IconButton(onClick={if(playing)onPause()else onPlay(s)}){Icon(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,if(playing)"Pause"else"Play")};IconButton(onClick=onStop){Icon(Icons.Default.Close,"Close")}};Progress(position,duration,onSeek,true)}}

@Composable private fun DetailScreen(s:Song,songId:Long?,playing:Boolean,position:Long,duration:Long,onBack:()->Unit,onPlay:()->Unit,onSeek:(Long)->Unit,onFavorite:()->Unit,onShare:()->Unit)=LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Button(onBack){Text("← Back")};Cover(s.coverUrl,Modifier.fillMaxWidth().height(300.dp));Text(s.title,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text(s.artist);Text("${s.language} • ${s.genre} • ${s.mood}");Progress(position,duration,onSeek);Row{Button(onPlay){Icon(if(songId==s.id&&playing)Icons.Default.Pause else Icons.Default.PlayArrow,null);Text(if(songId==s.id&&playing)" Pause" else " Play")};IconButton(onFavorite){Icon(Icons.Default.Favorite,"Favorite")};IconButton(onShare){Icon(Icons.Default.Share,"Share")}};if(s.description.isNotBlank())Text(s.description)};if(s.lyrics.isNotBlank())item{Text("Lyrics",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(s.lyrics)}}

private class FavoriteStore(context:Context) {
    private val p=context.getSharedPreferences("heartbeat_favorites",Context.MODE_PRIVATE)
    fun ids(): Set<Long> = p.getStringSet("ids",emptySet())?.mapNotNull{it.toLongOrNull()}?.toSet() ?: emptySet()
    fun toggle(id:Long): Set<Long> { val n=ids().toMutableSet(); if(!n.add(id))n.remove(id); p.edit().putStringSet("ids",n.map{it.toString()}.toSet()).apply(); return n }
}

private fun shareSong(context:Context,s:Song){val i=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,"${s.title} — ${s.artist}\n$API_BASE/song.html?id=${s.id}")};context.startActivity(Intent.createChooser(i,"Share song"))}
