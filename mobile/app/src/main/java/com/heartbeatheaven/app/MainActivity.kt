package com.heartbeatheaven.app

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray

private const val API_BASE = "https://heartbeat-heaven.onrender.com"
private const val YOUTUBE_URL = "https://www.youtube.com/@ViBORA-r1i"

private data class Song(val id: Long,val title: String,val artist: String,val genre: String,val language: String,val mood: String,val description: String,val lyrics: String,val coverUrl: String,val audioUrl: String,val releaseDate: String?)
private data class SongVersion(val id: Long,val versionName: String,val artist: String,val coverUrl: String,val audioUrl: String)

private suspend fun fetchSongs(): List<Song> = withContext(Dispatchers.IO) {
    val connection = java.net.URL("$API_BASE/api/songs").openConnection() as java.net.HttpURLConnection
    try { connection.requestMethod="GET"; connection.connectTimeout=15000; connection.readTimeout=20000; if(connection.responseCode !in 200..299) error("Server returned ${connection.responseCode}"); val array=JSONArray(connection.inputStream.bufferedReader().use{it.readText()}); buildList { for(i in 0 until array.length()){ val o=array.getJSONObject(i); add(Song(o.optLong("id"),o.optString("title"),o.optString("artist"),o.optString("genre"),o.optString("language"),o.optString("mood"),o.optString("description"),o.optString("lyrics"),o.optString("cover_url"),o.optString("audio_url"),o.optString("release_date").takeIf{it.isNotBlank()})) } } } finally { connection.disconnect() }
}

private suspend fun fetchVersions(song: Song): List<SongVersion> = withContext(Dispatchers.IO) {
    val connection=java.net.URL("$API_BASE/api/songs/${song.id}/hub").openConnection() as java.net.HttpURLConnection
    try { connection.requestMethod="GET"; connection.connectTimeout=10000; connection.readTimeout=15000; if(connection.responseCode !in 200..299) return@withContext listOf(SongVersion(song.id,"Original Version",song.artist,song.coverUrl,song.audioUrl)); val hub=org.json.JSONObject(connection.inputStream.bufferedReader().use{it.readText()}); val original=hub.optJSONObject("original"); val arr=hub.optJSONArray("versions")?:JSONArray(); val result=mutableListOf<SongVersion>(); result.add(SongVersion(song.id,original?.optString("version_name").orEmpty().ifBlank{"Original Version"},original?.optString("artist").orEmpty().ifBlank{song.artist},original?.optString("cover_url").orEmpty().ifBlank{song.coverUrl},original?.optString("audio_url").orEmpty().ifBlank{song.audioUrl})); for(i in 0 until arr.length()){ val item=arr.optJSONObject(i)?:continue; result.add(SongVersion(item.optLong("id",-(song.id*1000+i+1)),item.optString("version_name").ifBlank{"Version ${i+1}"},item.optString("artist").ifBlank{song.artist},item.optString("cover_url").ifBlank{song.coverUrl},item.optString("audio_url").ifBlank{song.audioUrl})) }; result.filter{it.audioUrl.isNotBlank()} } catch(_:Exception){ listOf(SongVersion(song.id,"Original Version",song.artist,song.coverUrl,song.audioUrl)) } finally { connection.disconnect() }
}

private fun thumbnailUrl(url:String):String { val clean=url.trim(); if(clean.isBlank()) return ""; return if(clean.contains("/storage/v1/object/public/")){ clean.replace("/storage/v1/object/public/","/storage/v1/render/image/public/")+if(clean.contains("?"))"&width=144&height=144&resize=cover&quality=55" else "?width=144&height=144&resize=cover&quality=55" } else clean }

class MainActivity:ComponentActivity(){
 private var player:ExoPlayer?=null; private var currentSongId by mutableStateOf<Long?>(null); private var currentSong by mutableStateOf<Song?>(null); private var isPlaying by mutableStateOf(false); private var positionMs by mutableLongStateOf(0L); private var durationMs by mutableLongStateOf(0L)
 private val playerListener=object:Player.Listener{ override fun onIsPlayingChanged(value:Boolean){isPlaying=value}; override fun onPlaybackStateChanged(state:Int){ if(state==Player.STATE_ENDED){ stop() }; if(state==Player.STATE_READY) durationMs=player?.duration?.coerceAtLeast(0L)?:0L }; override fun onPlayerError(error:androidx.media3.common.PlaybackException){isPlaying=false} }
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{HeartbeatTheme{LaunchedEffect(currentSongId,isPlaying){while(currentSongId!=null){positionMs=player?.currentPosition?.coerceAtLeast(0L)?:positionMs;durationMs=player?.duration?.takeIf{it>0}?:durationMs;delay(if(isPlaying)250L else 500L)}};HeartbeatApp(currentSongId,currentSong,isPlaying,positionMs,durationMs,::play,::pause,::seekTo,::stop)}}}
 private fun play(song:Song){val url=song.audioUrl.trim();if(url.isBlank())return;if(currentSongId==song.id&&player!=null){player?.play();return};player?.removeListener(playerListener);player?.release();positionMs=0L;durationMs=0L;currentSong=song;currentSongId=song.id;player=ExoPlayer.Builder(this).build().also{it.addListener(playerListener);it.setMediaItem(MediaItem.fromUri(url));it.prepare();it.playWhenReady=true}}
 private fun pause(){player?.pause();isPlaying=false};private fun seekTo(value:Long){player?.seekTo(value);positionMs=value.coerceAtLeast(0L)};private fun stop(){player?.removeListener(playerListener);player?.stop();player?.release();player=null;currentSong=null;currentSongId=null;positionMs=0L;durationMs=0L;isPlaying=false};override fun onDestroy(){player?.removeListener(playerListener);player?.release();player=null;super.onDestroy()}
}

@Composable private fun HeartbeatTheme(content:@Composable()->Unit){MaterialTheme(content=content)}
@Composable private fun CoverImage(url:String,contentDescription:String?,modifier:Modifier,thumbnail:Boolean=false){val context=LocalContext.current;val imageUrl=if(thumbnail)thumbnailUrl(url)else url.trim();val request=remember(imageUrl,thumbnail){ImageRequest.Builder(context).data(imageUrl.ifBlank{null}).size(if(thumbnail)144 else 900).crossfade(true).build()};AsyncImage(model=request,contentDescription=contentDescription,modifier=modifier,contentScale=ContentScale.Crop,placeholder=painterResource(android.R.drawable.ic_menu_gallery),error=painterResource(android.R.drawable.ic_menu_gallery),fallback=painterResource(android.R.drawable.ic_menu_gallery))}
private fun versionAsSong(parent:Song,version:SongVersion)=Song(version.id,parent.title,version.artist,parent.genre,parent.language,parent.mood,parent.description,parent.lyrics,version.coverUrl,version.audioUrl,parent.releaseDate)
private fun formatTime(ms:Long):String{val s=(ms.coerceAtLeast(0L)/1000L).toInt();return "%d:%02d".format(s/60,s%60)}
@Composable private fun PlayerProgress(positionMs:Long,durationMs:Long,onSeek:(Long)->Unit,compact:Boolean=false){val d=durationMs.coerceAtLeast(0L);val p=positionMs.coerceIn(0L,if(d>0)d else Long.MAX_VALUE);Column(Modifier.fillMaxWidth()){Slider(value=if(d>0)p.toFloat()else 0f,onValueChange={if(d>0)onSeek(it.toLong())},valueRange=0f..d.toFloat().coerceAtLeast(1f),enabled=d>0,modifier=Modifier.fillMaxWidth());Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(formatTime(p),fontSize=if(compact)10.sp else 12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(formatTime(d),fontSize=if(compact)10.sp else 12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}

@OptIn(ExperimentalMaterial3Api::class) @Composable private fun HeartbeatApp(currentSongId:Long?,currentSong:Song?,isPlaying:Boolean,positionMs:Long,durationMs:Long,onPlay:(Song)->Unit,onPause:()->Unit,onSeek:(Long)->Unit,onClose:()->Unit){val context=LocalContext.current;var songs by remember{mutableStateOf<List<Song>>(emptyList())};var loading by remember{mutableStateOf(true)};var error by remember{mutableStateOf<String?>(null)};var retryKey by remember{mutableStateOf(0)};var tab by remember{mutableStateOf(0)};var selected by remember{mutableStateOf<Song?>(null)};var versions by remember{mutableStateOf<List<SongVersion>>(emptyList())};var versionsLoading by remember{mutableStateOf(false)};var visibleVersionCount by remember{mutableStateOf(3)};var query by remember{mutableStateOf("")};val favorites=remember{FavoriteStore(context)};var favoriteIds by remember{mutableStateOf(favorites.ids())};val versionListState=rememberLazyListState()
 LaunchedEffect(retryKey){loading=true;try{songs=fetchSongs();error=null}catch(_:Exception){error="Unable to load songs. Please check your connection."}finally{loading=false}}
 LaunchedEffect(selected?.id){val song=selected?:return@LaunchedEffect;versionsLoading=true;visibleVersionCount=3;versions=fetchVersions(song);versionsLoading=false}
 LaunchedEffect(versionListState,versions.size,visibleVersionCount){snapshotFlow{versionListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?:0}.collect{lastVisible->if(versions.size>visibleVersionCount&&lastVisible>=visibleVersionCount)visibleVersionCount=minOf(visibleVersionCount+3,versions.size)}}
 val visibleSongs=songs.filter{song->{val q=query.trim().lowercase();q.isBlank()||listOf(song.title,song.artist,song.genre,song.language,song.mood).joinToString(" ").lowercase().contains(q)}}.let{list->if(tab==2)list.filter{favoriteIds.contains(it.id)}else list}
 Scaffold(topBar={TopAppBar(title={Column{Text("HEARTBEAT HEAVEN",fontWeight=FontWeight.Bold);Text("Original Music by Madushanka",fontSize=11.sp)}})},bottomBar={NavigationBar(modifier=Modifier.navigationBarsPadding()){NavigationBarItem(tab==0,{tab=0},icon={Icon(Icons.Default.Home,null)},label={Text("Home")});NavigationBarItem(tab==1,{tab=1},icon={Icon(Icons.Default.Search,null)},label={Text("Search")});NavigationBarItem(tab==2,{tab=2},icon={Icon(Icons.Default.Favorite,null)},label={Text("Favorites")});NavigationBarItem(tab==3,{tab=3},icon={Icon(Icons.Default.Person,null)},label={Text("Profile")})}}){padding->Column(Modifier.fillMaxSize().padding(padding)){if(tab==1)OutlinedTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth().padding(16.dp),leadingIcon={Icon(Icons.Default.Search,null)},label={Text("Search songs, artists...")},singleLine=true);if(loading){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}}else if(error!=null){Box(Modifier.fillMaxSize().padding(24.dp),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(error!!);Spacer(Modifier.height(12.dp));Button(onClick={retryKey++}){Text("Retry")}}}}else if(tab==3){AccountScreen()}else if(selected!=null){SongDetails(selected!!,versions.take(visibleVersionCount),versions.size,versionsLoading,versionListState,favoriteIds.contains(selected!!.id),currentSongId,isPlaying,positionMs,durationMs,{selected=null},{if(currentSongId==selected!!.id&&isPlaying)onPause()else onPlay(selected!!)}, {version->onPlay(versionAsSong(selected!!,version))},onSeek,{favoriteIds=favorites.toggle(selected!!.id)},{shareSong(context,selected!!)})}else{if(tab==0)Column(Modifier.padding(horizontal=16.dp,vertical=10.dp)){Text("Your music, your moments.",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("Fast, lightweight music listening",color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(12.dp));Button(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(YOUTUBE_URL)))},modifier=Modifier.fillMaxWidth()){Text("▶  Watch ViBORA on YouTube")}};LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){items(visibleSongs,key={it.id}){song->SongCard(song,favoriteIds.contains(song.id),currentSongId==song.id&&isPlaying,{selected=song},{if(currentSongId==song.id&&isPlaying)onPause()else onPlay(song)},{favoriteIds=favorites.toggle(song.id)})}}}}};if(currentSong!=null&&selected==null)MiniPlayer(currentSong,isPlaying,positionMs,durationMs,onPlay,onPause,onSeek,onClose)} }

@Composable private fun SongCard(song:Song,isFavorite:Boolean,isPlaying:Boolean,onClick:()->Unit,onPlay:()->Unit,onFavorite:()->Unit){Card(Modifier.fillMaxWidth().clickable(onClick=onClick),shape=RoundedCornerShape(16.dp),elevation=CardDefaults.cardElevation(1.dp)){Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically){CoverImage(song.coverUrl,song.title,Modifier.size(56.dp),true);Column(Modifier.weight(1f).padding(horizontal=10.dp)){Text(song.title,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(song.artist,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${song.language} • ${song.genre}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};IconButton(onClick=onFavorite){Icon(if(isFavorite)Icons.Default.Favorite else Icons.Default.FavoriteBorder,"Favorite")};IconButton(onClick=onPlay){Icon(if(isPlaying)Icons.Default.Pause else Icons.Default.PlayArrow,if(isPlaying)"Pause"else"Play")}}}}

@Composable private fun SongDetails(song:Song,versions:List<SongVersion>,totalVersions:Int,versionsLoading:Boolean,listState:LazyListState,isFavorite:Boolean,currentSongId:Long?,isPlaying:Boolean,positionMs:Long,durationMs:Long,onBack:()->Unit,onPlay:()->Unit,onPlayVersion:(SongVersion)->Unit,onSeek:(Long)->Unit,onFavorite:()->Unit,onShare:()->Unit){LazyColumn(state=listState,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Button(onClick=onBack){Text("← Back")};Spacer(Modifier.height(4.dp));CoverImage(song.coverUrl,song.title,Modifier.fillMaxWidth().height(300.dp));Spacer(Modifier.height(8.dp));Text(song.title,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text(song.artist,style=MaterialTheme.typography.titleMedium);Text("${song.language} • ${song.genre} • ${song.mood}",color=MaterialTheme.colorScheme.onSurfaceVariant);PlayerProgress(positionMs,durationMs,onSeek);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick=onPlay){Icon(if(currentSongId==song.id&&isPlaying)Icons.Default.Pause else Icons.Default.PlayArrow,null);Text(if(currentSongId==song.id&&isPlaying)" Pause" else " Play")};IconButton(onClick=onFavorite){Icon(if(isFavorite)Icons.Default.Favorite else Icons.Default.FavoriteBorder,"Favorite")};IconButton(onClick=onShare){Icon(Icons.Default.Share,"Share")}};if(song.description.isNotBlank()){Text("About",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(song.description)}};item{Text("Original & Versions",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("More versions load as you scroll.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);if(versionsLoading)CircularProgressIndicator(modifier=Modifier.padding(vertical=8.dp))else if(totalVersions==0)Text("No extra versions available.",color=MaterialTheme.colorScheme.onSurfaceVariant)};items(versions,key={it.id}){version->Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp)){Row(Modifier.padding(9.dp),verticalAlignment=Alignment.CenterVertically){CoverImage(version.coverUrl,version.versionName,Modifier.size(52.dp),true);Column(Modifier.weight(1f).padding(horizontal=10.dp)){Text(version.versionName,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(version.artist,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)};IconButton(onClick={onPlayVersion(version)}){Icon(Icons.Default.PlayArrow,"Play ${version.versionName}")}}}};if(song.lyrics.isNotBlank())item{Text("Lyrics",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(song.lyrics)}}}

@Composable private fun MiniPlayer(song:Song,isPlaying:Boolean,positionMs:Long,durationMs:Long,onPlay:(Song)->Unit,onPause:()->Unit,onSeek:(Long)->Unit,onClose:()->Unit){Surface(shadowElevation=8.dp,modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(horizontal=10.dp,vertical=5.dp)){Row(verticalAlignment=Alignment.CenterVertically){CoverImage(song.coverUrl,null,Modifier.size(44.dp),true);Column(Modifier.weight(1f).padding(horizontal=10.dp)){Text(song.title,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(song.artist,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)};IconButton(onClick={if(isPlaying)onPause()else onPlay(song)}){Icon(if(isPlaying)Icons.Default.Pause else Icons.Default.PlayArrow,if(isPlaying)"Pause"else"Play")};IconButton(onClick=onClose){Icon(Icons.Default.Close,"Close")}};PlayerProgress(positionMs,durationMs,onSeek,true)}}}
private class FavoriteStore(context:Context){private val prefs=context.getSharedPreferences("heartbeat_favorites",Context.MODE_PRIVATE);fun ids():Set<Long>=prefs.getStringSet("ids",emptySet())!!.mapNotNull{it.toLongOrNull()}.toSet();fun toggle(id:Long):Set<Long>{val next=ids().toMutableSet();if(!next.add(id))next.remove(id);prefs.edit().putStringSet("ids",next.map{it.toString()}.toSet()).apply();return next}}
private fun shareSong(context:Context,song:Song){val intent=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,"${song.title} — ${song.artist}\n$API_BASE/song.html?id=${song.id}")};context.startActivity(Intent.createChooser(intent,"Share song"))}
