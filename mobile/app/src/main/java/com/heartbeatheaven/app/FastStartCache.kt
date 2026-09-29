package com.heartbeatheaven.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object FastStartCache {
    private const val PREFS = "fast_start_cache_v1"
    private const val SONGS = "songs_json"
    private const val CHATS_PREFIX = "chats_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadSongs(context: Context): List<Song> = runCatching {
        val raw = prefs(context).getString(SONGS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val a = JSONArray(raw)
        buildList {
            for (i in 0 until a.length()) add(songFromJson(a.getJSONObject(i)))
        }
    }.getOrDefault(emptyList())

    fun saveSongs(context: Context, songs: List<Song>) {
        runCatching {
            val a = JSONArray()
            songs.forEach { s ->
                a.put(JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("artist", s.artist)
                    .put("genre", s.genre)
                    .put("language", s.language)
                    .put("mood", s.mood)
                    .put("description", s.description)
                    .put("lyrics", s.lyrics)
                    .put("cover_url", s.coverUrl)
                    .put("audio_url", s.audioUrl)
                    .put("release_date", s.releaseDate ?: JSONObject.NULL)
                    .put("version_name", s.versionName))
            }
            prefs(context).edit().putString(SONGS, a.toString()).apply()
        }
    }

    fun loadChats(context: Context, userId: String): List<ChatSummary> = runCatching {
        val raw = prefs(context).getString(CHATS_PREFIX + userId, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val a = JSONArray(raw)
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val u = o.getJSONObject("user")
                add(ChatSummary(
                    user = FriendUser(
                        id = u.optString("id"),
                        username = u.optString("username"),
                        gender = u.optString("gender"),
                        avatarUrl = u.optString("avatarUrl"),
                        lastSeenAt = u.optString("lastSeenAt"),
                        lastSeenVisibility = u.optString("lastSeenVisibility", "everyone")
                    ),
                    lastMessage = o.optString("lastMessage"),
                    lastMessageAt = o.optString("lastMessageAt"),
                    unreadCount = o.optInt("unreadCount"),
                    pinned = o.optBoolean("pinned"),
                    muted = o.optBoolean("muted")
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun saveChats(context: Context, userId: String, chats: List<ChatSummary>) {
        runCatching {
            val a = JSONArray()
            chats.forEach { c ->
                val u = JSONObject()
                    .put("id", c.user.id)
                    .put("username", c.user.username)
                    .put("gender", c.user.gender)
                    .put("avatarUrl", c.user.avatarUrl)
                    .put("lastSeenAt", c.user.lastSeenAt)
                    .put("lastSeenVisibility", c.user.lastSeenVisibility)
                a.put(JSONObject()
                    .put("user", u)
                    .put("lastMessage", c.lastMessage)
                    .put("lastMessageAt", c.lastMessageAt)
                    .put("unreadCount", c.unreadCount)
                    .put("pinned", c.pinned)
                    .put("muted", c.muted))
            }
            prefs(context).edit().putString(CHATS_PREFIX + userId, a.toString()).apply()
        }
    }
}
