package com.heartbeatheaven.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object FastStartCache {
    private const val PREFS = "fast_start_cache_v1"
    private const val SONGS = "songs_json"
    private const val FRIENDS_PREFIX = "friends_"
    private const val CHATS_PREFIX = "chats_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadFriends(context: Context, userId: String): List<FriendUser> = runCatching {
        val raw = prefs(context).getString(FRIENDS_PREFIX + userId, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val a = JSONArray(raw)
        buildList {
            for (i in 0 until a.length()) {
                val u = a.getJSONObject(i)
                add(FriendUser(u.optString("id"), u.optString("username"), u.optString("gender"), u.optString("avatarUrl"), u.optString("lastSeenAt"), u.optString("lastSeenVisibility", "everyone")))
            }
        }
    }.getOrDefault(emptyList())

    fun saveFriends(context: Context, userId: String, friends: List<FriendUser>) {
        runCatching {
            val a = JSONArray()
            friends.forEach { u ->
                a.put(JSONObject().put("id", u.id).put("username", u.username).put("gender", u.gender)
                    .put("avatarUrl", u.avatarUrl).put("lastSeenAt", u.lastSeenAt).put("lastSeenVisibility", u.lastSeenVisibility))
            }
            prefs(context).edit().putString(FRIENDS_PREFIX + userId, a.toString()).apply()
        }
    }

    fun loadChats(context: Context, userId: String): List<ChatSummary> = runCatching {
        val raw = prefs(context).getString(CHATS_PREFIX + userId, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val a = JSONArray(raw)
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val u = o.getJSONObject("user")
                add(ChatSummary(
                    user = FriendUser(u.optString("id"), u.optString("username"), u.optString("gender"), u.optString("avatarUrl"), u.optString("lastSeenAt"), u.optString("lastSeenVisibility", "everyone")),
                    lastMessage = o.optString("lastMessage"), lastMessageAt = o.optString("lastMessageAt"), unreadCount = o.optInt("unreadCount"), pinned = o.optBoolean("pinned"), muted = o.optBoolean("muted")
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun saveChats(context: Context, userId: String, chats: List<ChatSummary>) {
        runCatching {
            val a = JSONArray()
            chats.forEach { c ->
                val u = JSONObject().put("id", c.user.id).put("username", c.user.username).put("gender", c.user.gender)
                    .put("avatarUrl", c.user.avatarUrl).put("lastSeenAt", c.user.lastSeenAt).put("lastSeenVisibility", c.user.lastSeenVisibility)
                a.put(JSONObject().put("user", u).put("lastMessage", c.lastMessage).put("lastMessageAt", c.lastMessageAt).put("unreadCount", c.unreadCount).put("pinned", c.pinned).put("muted", c.muted))
            }
            prefs(context).edit().putString(CHATS_PREFIX + userId, a.toString()).apply()
        }
    }
}
