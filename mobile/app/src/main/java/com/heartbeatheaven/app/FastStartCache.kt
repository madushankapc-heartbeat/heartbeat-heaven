package com.heartbeatheaven.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object FastStartCache {
    private const val PREFS = "fast_start_cache_v1"
    private const val SONGS = "songs_json"
    private const val FRIENDS_PREFIX = "friends_"
    private const val CHATS_PREFIX = "chats_"
    private const val CHAT_MESSAGES_PREFIX = "chat_messages_"
    private const val GROUPS_PREFIX = "groups_"

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
    fun loadChatMessages(context: Context, userId: String, otherUserId: String): List<ChatMessage> = runCatching {
        val raw = prefs(context).getString(CHAT_MESSAGES_PREFIX + userId + "_" + otherUserId, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val a = JSONArray(raw)
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(ChatMessage(
                    id = o.optString("id"), senderId = o.optString("sender_id"), body = o.optString("body"),
                    createdAt = o.optString("created_at"), deliveredAt = o.optString("delivered_at"), readAt = o.optString("read_at"),
                    editedAt = o.optString("edited_at"), deletedAt = o.optString("deleted_at"), replyToId = o.optString("reply_to_id"),
                    messageType = o.optString("message_type", "text"), mediaUrl = o.optString("media_url"),
                    mediaPath = o.optString("media_path"), mediaName = o.optString("media_name"), mediaSize = o.optLong("media_size", 0L)
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun saveChatMessages(context: Context, userId: String, otherUserId: String, messages: List<ChatMessage>) {
        runCatching {
            val a = JSONArray()
            messages.forEach { m ->
                a.put(JSONObject()
                    .put("id", m.id).put("sender_id", m.senderId).put("body", m.body).put("created_at", m.createdAt)
                    .put("delivered_at", m.deliveredAt).put("read_at", m.readAt).put("edited_at", m.editedAt).put("deleted_at", m.deletedAt)
                    .put("reply_to_id", m.replyToId).put("message_type", m.messageType).put("media_url", m.mediaUrl)
                    .put("media_path", m.mediaPath).put("media_name", m.mediaName).put("media_size", m.mediaSize))
            }
            prefs(context).edit().putString(CHAT_MESSAGES_PREFIX + userId + "_" + otherUserId, a.toString()).apply()
        }
    }

    fun loadGroups(context: Context, userId: String): List<GroupSummary> = runCatching {
        val raw = prefs(context).getString(GROUPS_PREFIX + userId, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val a = JSONArray(raw)
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(GroupSummary(o.optString("id"), o.optString("name"), o.optString("description"), o.optString("group_type"),
                    o.optInt("auto_delete_days", 7), o.optString("owner_id"), o.optString("photo_path"), ""))
            }
        }
    }.getOrDefault(emptyList())

    fun saveGroups(context: Context, userId: String, groups: List<GroupSummary>) {
        runCatching {
            val a = JSONArray()
            groups.forEach { g ->
                a.put(JSONObject().put("id", g.id).put("name", g.name).put("description", g.description)
                    .put("group_type", g.groupType).put("auto_delete_days", g.autoDeleteDays).put("owner_id", g.ownerId).put("photo_path", g.photoPath))
            }
            prefs(context).edit().putString(GROUPS_PREFIX + userId, a.toString()).apply()
        }
    }

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
                    .put("release_date", s.releaseDate ?: "")
                    .put("version_name", s.versionName))
            }
            prefs(context).edit().putString(SONGS, a.toString()).apply()
        }
    }

}
