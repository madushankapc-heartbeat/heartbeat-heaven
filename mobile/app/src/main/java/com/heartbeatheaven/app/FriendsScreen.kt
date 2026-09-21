package com.heartbeatheaven.app

import java.io.File
import android.Manifest
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.time.Instant
import java.time.temporal.ChronoUnit

private const val FRIENDS_SUPABASE_URL = "https://fafvhyeesenpimxncupp.supabase.co"
private const val FRIENDS_KEY = "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"
private data class FriendUser(val id: String, val username: String, val gender: String, val avatarUrl: String = "", val lastSeenAt: String = "", val lastSeenVisibility: String = "everyone")
private enum class FriendSearchState { Idle, Loading, Results, Empty, Error }
private data class FriendProfile(val id: String, val username: String, val gender: String, val bio: String = "", val lastSeenAt: String, val avatarUrl: String = "", val lastSeenVisibility: String = "everyone")
private data class FriendRequest(val id: String, val user: FriendUser, val incoming: Boolean)
private data class ChatSummary(val user: FriendUser, val lastMessage: String, val lastMessageAt: String, val unreadCount: Int, val pinned: Boolean, val muted: Boolean)
private data class ChatMessage(val id: String, val senderId: String, val body: String, val createdAt: String, val deliveredAt: String = "", val readAt: String = "", val editedAt: String = "", val deletedAt: String = "", val replyToId: String = "", val messageType: String = "text", val mediaUrl: String = "", val mediaName: String = "", val mediaSize: Long = 0L)
private data class MessageReaction(val messageId: String, val userId: String, val reaction: String)
private data class PendingChatAttachment(val uri: Uri, val name: String, val mime: String, val size: Long)
private fun formatAttachmentSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
    return String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
}


private class FriendsApi(private val auth: AuthApi, initialSession: AuthSession) {
    private var session = initialSession
    fun token(): String = session.accessToken
    fun userId(): String = session.profile.id

    private fun request(path: String, method: String, body: String? = null): String {
        fun doRequest(): Pair<Int, String> {
            val c = URL(FRIENDS_SUPABASE_URL + path).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("apikey", FRIENDS_KEY)
                c.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
                c.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                return c.responseCode to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally { c.disconnect() }
        }
        auth.currentSession()?.let { session = it }
        var (code, text) = doRequest()
        if (code == 401) {
            session = auth.currentSession() ?: throw IllegalStateException("Your session has expired. Please log in again.")
            val retry = doRequest()
            code = retry.first
            text = retry.second
        }
        if (code !in 200..299) {
            val detail = runCatching {
                JSONObject(text).optString("message")
                    .ifBlank { JSONObject(text).optString("msg") }
                    .ifBlank { JSONObject(text).optString("error") }
            }.getOrDefault("")
            throw IllegalStateException(if (detail.isBlank()) "Request failed ($code)" else detail)
        }
        return text
    }

    suspend fun touchPresence() = withContext(Dispatchers.IO) {
        auth.touchLastSeen(session.accessToken).getOrThrow()
    }

    suspend fun onlineUsers(friendIdsOverride: Set<String>? = null): List<FriendUser> = withContext(Dispatchers.IO) {
        val since = Instant.now().minus(2, ChronoUnit.MINUTES).toString()
        val encoded = URLEncoder.encode(since, "UTF-8")
        val mine = userId()
        val friendIds = friendIdsOverride ?: runCatching {
            val f = JSONArray(request("/rest/v1/friendships?or=(requester_id.eq." + mine + ",addressee_id.eq." + mine + ")&status=eq.accepted&select=requester_id,addressee_id&limit=500", "GET"))
            buildSet {
                for (i in 0 until f.length()) {
                    val o = f.getJSONObject(i)
                    add(if (o.optString("requester_id") == mine) o.optString("addressee_id") else o.optString("requester_id"))
                }
            }
        }.getOrDefault(emptySet())
        val a = JSONArray(request("/rest/v1/public_profiles?last_seen_at=gte." + encoded + "&select=id,username,gender,avatar_url,last_seen_at,last_seen_visibility&limit=100", "GET"))
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val id = o.optString("id")
                val visibility = o.optString("last_seen_visibility").ifBlank { "everyone" }
                val allowed = id != mine && (visibility == "everyone" || (visibility == "friends" && id in friendIds))
                if (allowed) add(FriendUser(id, o.optString("username"), o.optString("gender"), o.optString("avatar_url").takeUnless { it == "null" }.orEmpty(), o.optString("last_seen_at").takeUnless { it == "null" }.orEmpty(), visibility))
            }
        }
    }

    suspend fun search(username: String): List<FriendUser> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(username.trim(), "UTF-8")
        val a = runCatching { JSONArray(request("/rest/v1/public_profiles?username=ilike.*$q*&select=id,username,gender,avatar_url&limit=20", "GET")) }.getOrElse { JSONArray(request("/rest/v1/public_profiles?username=ilike.*$q*&select=id,username,gender&limit=20", "GET")) }
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optString("id") != userId()) add(
                    FriendUser(
                        o.optString("id"),
                        o.optString("username"),
                        o.optString("gender"),
                        o.optString("avatar_url").takeUnless { it == "null" }.orEmpty()
                    )
                )
            }
        }
    }

    suspend fun requests(): List<FriendRequest> = withContext(Dispatchers.IO) {
        val mine = userId()
        val a = JSONArray(request("/rest/v1/friendships?or=(requester_id.eq.$mine,addressee_id.eq.$mine)&status=eq.pending&select=id,requester_id,addressee_id", "GET"))
        if (a.length() == 0) return@withContext emptyList()
        val ids = buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(if (o.optString("addressee_id") == mine) o.optString("requester_id") else o.optString("addressee_id"))
            }
        }.filter { it.isNotBlank() }.distinct()
        val profileMap = HashMap<String, FriendUser>()
        if (ids.isNotEmpty()) {
            val filter = ids.joinToString(",")
            val profiles = runCatching {
                JSONArray(request("/rest/v1/public_profiles?id=in.($filter)&select=id,username,gender,avatar_url", "GET"))
            }.getOrElse {
                JSONArray(request("/rest/v1/public_profiles?id=in.($filter)&select=id,username,gender", "GET"))
            }
            for (i in 0 until profiles.length()) {
                val u = profiles.getJSONObject(i)
                val id = u.optString("id")
                profileMap[id] = FriendUser(id, u.optString("username"), u.optString("gender"), u.optString("avatar_url").takeUnless { it == "null" }.orEmpty())
            }
        }
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val incoming = o.optString("addressee_id") == mine
                val uid = if (incoming) o.optString("requester_id") else o.optString("addressee_id")
                profileMap[uid]?.let { add(FriendRequest(o.optString("id"), it, incoming)) }
            }
        }
    }

    suspend fun send(targetUserId: String): String = withContext(Dispatchers.IO) {
        val mine = userId()
        val existing = JSONArray(request("/rest/v1/friendships?or=(and(requester_id.eq.$mine,addressee_id.eq.$targetUserId),and(requester_id.eq.$targetUserId,addressee_id.eq.$mine))&select=id,status,requester_id,addressee_id", "GET"))
        if (existing.length() > 0) {
            val row = existing.getJSONObject(0)
            when (row.optString("status")) {
                "accepted" -> return@withContext "Already friends."
                "pending" -> return@withContext "Friend request already pending."
                "rejected" -> {
                    request("/rest/v1/friendships?id=eq.${row.optString("id")}", "PATCH", JSONObject().put("requester_id", mine).put("addressee_id", targetUserId).put("status", "pending").toString())
                    return@withContext "Friend request sent."
                }
                "blocked" -> return@withContext "This friendship is blocked."
            }
        }
        request("/rest/v1/friendships", "POST", JSONObject().put("requester_id", mine).put("addressee_id", targetUserId).toString())
        "Friend request sent."
    }

    suspend fun accept(id: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/friendships?id=eq.$id", "PATCH", JSONObject().put("status", "accepted").toString())
    }

    suspend fun decline(id: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/friendships?id=eq.$id&status=eq.pending", "DELETE")
    }

    suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/friendships?id=eq.$id&status=eq.pending", "DELETE")
    }

suspend fun unfriend(other: String) = withContext(Dispatchers.IO) {
        val mine = userId()
        request(
            "/rest/v1/friendships?status=eq.accepted&or=(and(requester_id.eq.$mine,addressee_id.eq.$other),and(requester_id.eq.$other,addressee_id.eq.$mine))",
            "DELETE"
        )
    }

    suspend fun friends(): List<FriendUser> = withContext(Dispatchers.IO) {
        val mine = userId()
        val a = JSONArray(request("/rest/v1/friendships?or=(requester_id.eq." + mine + ",addressee_id.eq." + mine + ")&status=eq.accepted&select=requester_id,addressee_id", "GET"))
        if (a.length() == 0) return@withContext emptyList()
        val ids = buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(if (o.optString("requester_id") == mine) o.optString("addressee_id") else o.optString("requester_id"))
            }
        }.filter { it.isNotBlank() }.distinct()
        val filter = ids.joinToString(",")
        val profiles = runCatching {
            JSONArray(request("/rest/v1/public_profiles?id=in.($filter)&select=id,username,gender,avatar_url,last_seen_at,last_seen_visibility", "GET"))
        }.getOrElse {
            JSONArray(request("/rest/v1/public_profiles?id=in.($filter)&select=id,username,gender,avatar_url", "GET"))
        }
        val profileMap = HashMap<String, FriendUser>()
        for (i in 0 until profiles.length()) {
            val u = profiles.getJSONObject(i)
            val id = u.optString("id")
            profileMap[id] = FriendUser(id, u.optString("username"), u.optString("gender"), u.optString("avatar_url").takeUnless { it == "null" }.orEmpty(), u.optString("last_seen_at").takeUnless { it == "null" }.orEmpty(), u.optString("last_seen_visibility").ifBlank { "everyone" })
        }
        ids.mapNotNull { profileMap[it] }
    }

    suspend fun chatSummaries(friendListOverride: List<FriendUser>? = null): List<ChatSummary> = withContext(Dispatchers.IO) {
        val mine = userId()
        val friendList = friendListOverride ?: friends()
        if (friendList.isEmpty()) return@withContext emptyList()

        data class SummaryParts(
            val hidden: Set<String>,
            val pinned: Set<String>,
            val muted: Set<String>,
            val messages: JSONArray
        )

        val parts = coroutineScope {
            val hiddenDeferred = async(Dispatchers.IO) {
                runCatching {
                    val d = JSONArray(request("/rest/v1/message_deletions?user_id=eq.$mine&select=message_id&limit=5000", "GET"))
                    buildSet { for (i in 0 until d.length()) add(d.getJSONObject(i).optString("message_id")) }
                }.getOrDefault(emptySet())
            }
            val pinnedDeferred = async(Dispatchers.IO) {
                runCatching {
                    val p = JSONArray(request("/rest/v1/chat_pins?user_id=eq.$mine&select=other_user_id&limit=5000", "GET"))
                    buildSet { for (i in 0 until p.length()) add(p.getJSONObject(i).optString("other_user_id")) }
                }.getOrDefault(emptySet())
            }
            val mutedDeferred = async(Dispatchers.IO) {
                runCatching {
                    val m = JSONArray(request("/rest/v1/chat_mutes?user_id=eq.$mine&select=other_user_id&limit=5000", "GET"))
                    buildSet { for (i in 0 until m.length()) add(m.getJSONObject(i).optString("other_user_id")) }
                }.getOrDefault(emptySet())
            }
            val messagesDeferred = async(Dispatchers.IO) {
                JSONArray(
                    request(
                        "/rest/v1/messages?or=(sender_id.eq.$mine,receiver_id.eq.$mine)&select=id,sender_id,receiver_id,body,created_at,read_at,deleted_at&order=created_at.desc&limit=1000",
                        "GET"
                    )
                )
            }
            SummaryParts(
                hiddenDeferred.await(),
                pinnedDeferred.await(),
                mutedDeferred.await(),
                messagesDeferred.await()
            )
        }

        val hidden = parts.hidden
        val pinned = parts.pinned
        val muted = parts.muted
        val a = parts.messages

        data class Row(val otherId: String, val body: String, val createdAt: String, val unread: Boolean)
        val latest = LinkedHashMap<String, Row>()

        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val id = o.optString("id")
            if (id in hidden) continue

            val sender = o.optString("sender_id")
            val receiver = o.optString("receiver_id")
            val otherId = if (sender == mine) receiver else sender
            if (otherId.isBlank() || latest.containsKey(otherId)) continue

            val deleted = o.optString("deleted_at").takeUnless { it == "null" }.orEmpty().isNotBlank()
            val body = if (deleted) "This message was deleted" else o.optString("body")
            latest[otherId] = Row(
                otherId = otherId,
                body = body,
                createdAt = o.optString("created_at"),
                unread = sender != mine && o.optString("read_at").takeUnless { it == "null" }.orEmpty().isBlank()
            )
        }

        val unreadCounts = HashMap<String, Int>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val id = o.optString("id")
            if (id in hidden) continue
            if (o.optString("receiver_id") == mine && o.optString("read_at").takeUnless { it == "null" }.orEmpty().isBlank()) {
                unreadCounts[o.optString("sender_id")] = (unreadCounts[o.optString("sender_id")] ?: 0) + 1
            }
        }

        friendList.map { friend ->
            val row = latest[friend.id]
            ChatSummary(
                user = friend,
                lastMessage = row?.body.orEmpty(),
                lastMessageAt = row?.createdAt.orEmpty(),
                unreadCount = unreadCounts[friend.id] ?: 0,
                pinned = friend.id in pinned,
                muted = friend.id in muted
            )
        }.sortedWith(
            compareByDescending<ChatSummary> { it.pinned }
                .thenByDescending { it.lastMessageAt.isNotBlank() }
                .thenByDescending { it.lastMessageAt }
                .thenBy { it.user.username.lowercase() }
        )
    }

    suspend fun profile(other: String): FriendProfile? = withContext(Dispatchers.IO) {
        val a = runCatching {
            JSONArray(request("/rest/v1/public_profiles?id=eq." + other + "&select=id,username,gender,bio,last_seen_at,last_seen_visibility,avatar_url&limit=1", "GET"))
        }.getOrElse {
            JSONArray(request("/rest/v1/public_profiles?id=eq." + other + "&select=id,username,gender,bio,last_seen_at,avatar_url&limit=1", "GET"))
        }
        if (a.length() == 0) return@withContext null
        val o = a.getJSONObject(0)
        FriendProfile(
            o.optString("id"),
            o.optString("username"),
            o.optString("gender"),
            o.optString("bio").takeUnless { it == "null" }.orEmpty(),
            o.optString("last_seen_at").takeUnless { it == "null" }.orEmpty(),
            o.optString("avatar_url").takeUnless { it == "null" }.orEmpty(),
            o.optString("last_seen_visibility").ifBlank { "everyone" }
        )
    }

    suspend fun isMuted(other: String): Boolean = withContext(Dispatchers.IO) {
        JSONArray(request("/rest/v1/chat_mutes?user_id=eq.${userId()}&other_user_id=eq.${other}&select=other_user_id&limit=1", "GET")).length() > 0
    }

    suspend fun friendshipSince(other: String): String = withContext(Dispatchers.IO) {
        val mine = userId()
        val rows = runCatching {
            JSONArray(request("/rest/v1/friendships?status=eq.accepted&or=(and(requester_id.eq.$mine,addressee_id.eq.$other),and(requester_id.eq.$other,addressee_id.eq.$mine))&select=updated_at,created_at&limit=1", "GET"))
        }.getOrDefault(JSONArray())
        if (rows.length() == 0) return@withContext ""
        val row = rows.getJSONObject(0)
        row.optString("updated_at").takeUnless { it == "null" }.orEmpty()
            .ifBlank { row.optString("created_at").takeUnless { it == "null" }.orEmpty() }
    }

    suspend fun setMuted(other: String, muted: Boolean) = withContext(Dispatchers.IO) {
        val mine = userId()
        if (muted) {
            val existing = JSONArray(request("/rest/v1/chat_mutes?user_id=eq.${mine}&other_user_id=eq.${other}&select=other_user_id&limit=1", "GET"))
            if (existing.length() == 0) request("/rest/v1/chat_mutes", "POST", JSONObject().put("user_id", mine).put("other_user_id", other).toString())
        } else request("/rest/v1/chat_mutes?user_id=eq.${mine}&other_user_id=eq.${other}", "DELETE")
    }

    suspend fun isPinned(other: String): Boolean = withContext(Dispatchers.IO) {
        JSONArray(request("/rest/v1/chat_pins?user_id=eq.${userId()}&other_user_id=eq.${other}&select=other_user_id&limit=1", "GET")).length() > 0
    }

    suspend fun setPinned(other: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        val mine = userId()
        if (pinned) {
            val existing = JSONArray(request("/rest/v1/chat_pins?user_id=eq.${mine}&other_user_id=eq.${other}&select=other_user_id&limit=1", "GET"))
            if (existing.length() == 0) request("/rest/v1/chat_pins", "POST", JSONObject().put("user_id", mine).put("other_user_id", other).toString())
        } else request("/rest/v1/chat_pins?user_id=eq.${mine}&other_user_id=eq.${other}", "DELETE")
    }

    suspend fun isBlocked(other: String): Boolean = withContext(Dispatchers.IO) {
        JSONArray(request("/rest/v1/user_blocks?blocker_id=eq.${userId()}&blocked_id=eq.${other}&select=blocked_id&limit=1", "GET")).length() > 0
    }

    suspend fun setBlocked(other: String, blocked: Boolean) = withContext(Dispatchers.IO) {
        val mine = userId()
        if (blocked) {
            val existing = JSONArray(request("/rest/v1/user_blocks?blocker_id=eq.${mine}&blocked_id=eq.${other}&select=blocked_id&limit=1", "GET"))
            if (existing.length() == 0) request("/rest/v1/user_blocks", "POST", JSONObject().put("blocker_id", mine).put("blocked_id", other).toString())
        } else request("/rest/v1/user_blocks?blocker_id=eq.${mine}&blocked_id=eq.${other}", "DELETE")
    }

    suspend fun report(other: String, reason: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/chat_reports", "POST", JSONObject().put("reporter_id", userId()).put("reported_user_id", other).put("reason", reason.trim().take(500)).toString())
    }

    private fun parseMessages(a: JSONArray, hidden: Set<String>): List<ChatMessage> = buildList {
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val id = o.optString("id")
            if (id !in hidden) add(ChatMessage(
                id, o.optString("sender_id"), o.optString("body"), o.optString("created_at"),
                o.optString("delivered_at").takeUnless { it == "null" }.orEmpty(),
                o.optString("read_at").takeUnless { it == "null" }.orEmpty(),
                o.optString("edited_at").takeUnless { it == "null" }.orEmpty(),
                o.optString("deleted_at").takeUnless { it == "null" }.orEmpty(),
                o.optString("reply_to_id").takeUnless { it == "null" }.orEmpty(),
                o.optString("message_type").ifBlank { "text" },
                o.optString("media_url").takeUnless { it == "null" }.orEmpty(),
                o.optString("media_name").takeUnless { it == "null" }.orEmpty(),
                o.optLong("media_size", 0L)
            ))
        }
    }

    suspend fun messagesPage(other: String, beforeCreatedAt: String? = null): Pair<List<ChatMessage>, Boolean> = withContext(Dispatchers.IO) {
        val mine = userId()
        val cursor = beforeCreatedAt?.let { "&created_at=lt.${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        val a = JSONArray(request("/rest/v1/messages?or=(and(sender_id.eq.${mine},receiver_id.eq.${other}),and(sender_id.eq.${other},receiver_id.eq.${mine}))${cursor}&select=id,sender_id,body,created_at,delivered_at,read_at,edited_at,deleted_at,reply_to_id,message_type,media_url,media_name,media_size&order=created_at.desc&limit=101", "GET"))
        val hidden = runCatching {
            val d = JSONArray(request("/rest/v1/message_deletions?user_id=eq.${mine}&select=message_id&limit=2000", "GET"))
            buildSet { for (i in 0 until d.length()) add(d.getJSONObject(i).optString("message_id")) }
        }.getOrDefault(emptySet())
        val hasMore = a.length() > 100
        parseMessages(a, hidden).take(100).sortedBy { it.createdAt } to hasMore
    }

    suspend fun messages(other: String): List<ChatMessage> = messagesPage(other).first

    suspend fun searchMessages(other: String, query: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        val mine = userId()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val a = JSONArray(request("/rest/v1/messages?or=(and(sender_id.eq.${mine},receiver_id.eq.${other}),and(sender_id.eq.${other},receiver_id.eq.${mine}))&body=ilike.*${encoded}*&select=id,sender_id,body,created_at,delivered_at,read_at,edited_at,deleted_at,reply_to_id,message_type,media_url,media_name,media_size&order=created_at.asc&limit=1000", "GET"))
        val hidden = runCatching {
            val d = JSONArray(request("/rest/v1/message_deletions?user_id=eq.${mine}&select=message_id&limit=2000", "GET"))
            buildSet { for (i in 0 until d.length()) add(d.getJSONObject(i).optString("message_id")) }
        }.getOrDefault(emptySet())
        parseMessages(a, hidden)
    }

    suspend fun markDelivered(messageId: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/messages?id=eq.$messageId&receiver_id=eq.${userId()}", "PATCH", JSONObject().put("delivered_at", Instant.now().toString()).toString())
    }

    suspend fun markSeen(other: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/messages?sender_id=eq.$other&receiver_id=eq.${userId()}&read_at=is.null", "PATCH", JSONObject().put("read_at", Instant.now().toString()).toString())
    }

    suspend fun forwardMessage(other: String, message: ChatMessage) = withContext(Dispatchers.IO) {
        if (message.deletedAt.isNotBlank()) error("Deleted messages cannot be forwarded.")
        if (message.messageType != "text" && message.mediaUrl.isNotBlank()) {
            val media = UploadedChatMedia(message.messageType, message.mediaUrl, message.mediaName.ifBlank { "attachment" }, message.mediaSize)
            sendMediaMessage(other, media, message.body.trim())
        } else {
            sendMessage(other, message.body)
        }
    }

    suspend fun sendMediaMessage(other: String, media: UploadedChatMedia, caption: String = "") = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("sender_id", userId()).put("receiver_id", other).put("body", caption.trim()).put("message_type", media.type).put("media_url", media.url).put("media_name", media.name).put("media_size", media.size)
        request("/rest/v1/messages", "POST", payload.toString())
    }
    suspend fun sendMessage(other: String, body: String, replyToId: String? = null): ChatMessage? = withContext(Dispatchers.IO) {
        val clean = body.trim()
        if (clean.isBlank()) return@withContext null
        val payload = JSONObject().put("sender_id", userId()).put("receiver_id", other).put("body", clean)
        replyToId?.takeIf { it.isNotBlank() }?.let { payload.put("reply_to_id", it) }
        val response = request("/rest/v1/messages", "POST", payload.toString())
        runCatching {
            val a = JSONArray(response)
            if (a.length() > 0) {
                val o = a.getJSONObject(0)
                ChatMessage(o.optString("id"), o.optString("sender_id"), o.optString("body"), o.optString("created_at"), o.optString("delivered_at").takeUnless { it == "null" }.orEmpty(), o.optString("read_at").takeUnless { it == "null" }.orEmpty(), o.optString("edited_at").takeUnless { it == "null" }.orEmpty(), o.optString("deleted_at").takeUnless { it == "null" }.orEmpty(), o.optString("reply_to_id").takeUnless { it == "null" }.orEmpty())
            } else null
        }.getOrNull()
    }

    suspend fun deleteForMe(messageId: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/rpc/delete_message_for_me", "POST", JSONObject().put("p_message_id", messageId).toString())
    }

    suspend fun clearChatForMe(otherUserId: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/rpc/clear_chat_for_me", "POST", JSONObject().put("p_other_user_id", otherUserId).toString())
    }

    suspend fun clearChatForEveryone(otherUserId: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/rpc/clear_chat_for_everyone", "POST", JSONObject().put("p_other_user_id", otherUserId).toString())
    }

    suspend fun deleteForEveryone(messageId: String): ChatMessage = withContext(Dispatchers.IO) {
        val response = request("/rest/v1/rpc/delete_message_for_everyone", "POST", JSONObject().put("p_message_id", messageId).toString())
        val o = JSONObject(response)
        ChatMessage(
            o.optString("id"),
            o.optString("sender_id"),
            o.optString("body"),
            o.optString("created_at"),
            o.optString("delivered_at").takeUnless { it == "null" }.orEmpty(),
            o.optString("read_at").takeUnless { it == "null" }.orEmpty(),
            o.optString("edited_at").takeUnless { it == "null" }.orEmpty(),
            o.optString("deleted_at").takeUnless { it == "null" }.orEmpty(),
            o.optString("reply_to_id").takeUnless { it == "null" }.orEmpty()
        )
    }

    suspend fun reactions(other: String): List<MessageReaction> = withContext(Dispatchers.IO) {
        reactionsForMessageIds(messages(other).map { it.id })
    }

    suspend fun reactionsForMessageIds(ids: List<String>): List<MessageReaction> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val idFilter = ids.joinToString(",")
        val a = JSONArray(request("/rest/v1/message_reactions?select=message_id,user_id,reaction&message_id=in.($idFilter)&limit=500", "GET"))
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(MessageReaction(o.optString("message_id"), o.optString("user_id"), o.optString("reaction")))
            }
        }
    }

    suspend fun setReaction(messageId: String, reaction: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/rpc/set_message_reaction", "POST", JSONObject().put("p_message_id", messageId).put("p_reaction", reaction).toString())
    }

    suspend fun removeReaction(messageId: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/rpc/remove_message_reaction", "POST", JSONObject().put("p_message_id", messageId).toString())
    }

    suspend fun editMessage(messageId: String, body: String): ChatMessage = withContext(Dispatchers.IO) {
        val clean = body.trim()
        if (clean.isBlank()) throw IllegalStateException("Message cannot be empty")
        if (clean.length > 4000) throw IllegalStateException("Message is too long")
        val response = request("/rest/v1/rpc/edit_my_message", "POST", JSONObject().put("p_message_id", messageId).put("p_body", clean).toString())
        val o = JSONObject(response)
        ChatMessage(o.optString("id"), o.optString("sender_id"), o.optString("body"), o.optString("created_at"), o.optString("delivered_at").takeUnless { it == "null" }.orEmpty(), o.optString("read_at").takeUnless { it == "null" }.orEmpty(), o.optString("edited_at").takeUnless { it == "null" }.orEmpty(), o.optString("deleted_at").takeUnless { it == "null" }.orEmpty(), o.optString("reply_to_id").takeUnless { it == "null" }.orEmpty())
    }
}

private fun targetIsMine(message: ChatMessage?, userId: String): Boolean = message?.senderId == userId

private fun saveRemoteAttachment(context: android.content.Context, sourceUrl: String, destination: Uri): Result<Unit> = runCatching {
    val connection = URL(sourceUrl).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15000
        connection.readTimeout = 60000
        if (connection.responseCode !in 200..299) error("Download failed (${connection.responseCode}).")
        val output = context.contentResolver.openOutputStream(destination) ?: error("Could not open the selected save location.")
        connection.inputStream.use { input -> output.use { input.copyTo(it) } }
    } finally {
        connection.disconnect()
    }
}

@Composable
internal fun FriendsScreen(refreshTrigger: Int = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { AuthApi(context) }
    var session by remember { mutableStateOf<AuthSession?>(null) }
    var checkingSession by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FriendUser>>(emptyList()) }
    var searchState by remember { mutableStateOf(FriendSearchState.Idle) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var online by remember { mutableStateOf<List<FriendUser>>(emptyList()) }
    var friends by remember { mutableStateOf<List<FriendUser>>(emptyList()) }
    var chatSummaries by remember { mutableStateOf<List<ChatSummary>>(emptyList()) }
    var requests by remember { mutableStateOf<List<FriendRequest>>(emptyList()) }
    var selected by remember { mutableStateOf<FriendUser?>(null) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editText by remember { mutableStateOf("") }
    var selectedMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var forwardingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var replyingTo by remember { mutableStateOf<ChatMessage?>(null) }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var reactions by remember { mutableStateOf<List<MessageReaction>>(emptyList()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var chatProfile by remember { mutableStateOf<FriendProfile?>(null) }
    var showChatProfile by remember { mutableStateOf(false) }
    var chatFriendSince by remember { mutableStateOf("") }
    var showChatMenu by remember { mutableStateOf(false) }
    var showChatThemeDialog by remember { mutableStateOf(false) }
    var showChatSearch by remember { mutableStateOf(false) }
    var chatSearch by remember { mutableStateOf("") }
    var chatMuted by remember { mutableStateOf(false) }
    var chatPinned by remember { mutableStateOf(false) }
    var chatBlocked by remember { mutableStateOf(false) }
    var unfriendTarget by remember { mutableStateOf<FriendUser?>(null) }
    var unfriendBusy by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("") }
    var clearChatMode by remember { mutableStateOf<String?>(null) }
    var otherTyping by remember { mutableStateOf(false) }
    var hasOlderMessages by remember { mutableStateOf(false) }
    var loadingOlderMessages by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<ChatMessage>?>(null) }
    var initialMessagesLoaded by remember { mutableStateOf(false) }
    var mediaBusy by remember { mutableStateOf(false) }
    var mediaProgress by remember { mutableStateOf(0) }
    var mediaProgressLabel by remember { mutableStateOf("") }
    var pendingMediaItems by remember { mutableStateOf<List<PendingChatAttachment>>(emptyList()) }
    var mediaUploadJob by remember { mutableStateOf<Job?>(null) }
    var voiceRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var voiceFile by remember { mutableStateOf<java.io.File?>(null) }
    var voiceRecording by remember { mutableStateOf(false) }
    var voiceUploading by remember { mutableStateOf(false) }
    var voiceElapsedMs by remember { mutableStateOf(0L) }
    var voiceStartedAt by remember { mutableStateOf(0L) }
    var voicePermissionPending by remember { mutableStateOf(false) }
    var startVoiceAfterPermission by remember { mutableStateOf(false) }
    var voicePlayingId by remember { mutableStateOf<String?>(null) }
    var voicePlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var showLatestButton by remember { mutableStateOf(false) }
    var pendingLatestScroll by remember { mutableStateOf(false) }
    var fullScreenImage by remember { mutableStateOf<ChatMessage?>(null) }
    var saveTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var friendTab by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        session = withContext(Dispatchers.IO) { auth.currentSession() }
        checkingSession = false
    }

    val api = session?.let { remember(it.accessToken) { FriendsApi(auth, it) } }
    fun isBlockedSendError(error: Throwable?): Boolean {
        val message = error?.message.orEmpty().lowercase()
        return message.contains("messages_blocked_users_denied") ||
            (message.contains("row-level security policy") && message.contains("messages"))
    }

    fun readPendingAttachment(uri: Uri): PendingChatAttachment {
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null
        } ?: "attachment"
        val size = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE)) else -1L
        } ?: -1L
        return PendingChatAttachment(uri, name, context.contentResolver.getType(uri).orEmpty(), size)
    }

    fun stopVoicePlayback() {
        voicePlayer?.runCatching { stop() }
        voicePlayer?.release()
        voicePlayer = null
        voicePlayingId = null
    }

    fun startVoiceRecordingNow() {
        val currentChat = selected ?: return
        if (chatBlocked || mediaBusy || voiceUploading || voiceRecording) return

        fun tryCreateRecorder(
            file: File,
            outputFormat: Int,
            audioEncoder: Int,
            configure: (MediaRecorder) -> Unit = {}
        ): MediaRecorder? {
            val recorder = MediaRecorder()
            return try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(outputFormat)
                recorder.setAudioEncoder(audioEncoder)
                recorder.setOutputFile(file.absolutePath)
                recorder.setMaxDuration(5 * 60 * 1000)
                recorder.setMaxFileSize(10L * 1024L * 1024L)
                configure(recorder)
                recorder.prepare()
                recorder.start()
                recorder
            } catch (_: Throwable) {
                runCatching { recorder.reset() }
                recorder.release()
                file.delete()
                null
            }
        }

        val primaryFile = VoiceMessageSupport.newRecordingFile(context, "m4a")
        val recorder = tryCreateRecorder(
            file = primaryFile,
            outputFormat = MediaRecorder.OutputFormat.MPEG_4,
            audioEncoder = MediaRecorder.AudioEncoder.AAC,
            configure = { recorder ->
                recorder.setAudioEncodingBitRate(64000)
            }
        ) ?: run {
            // Some Android/Samsung devices reject AAC/MPEG-4 at runtime.
            // Fall back to the broadly supported 3GP/AMR-NB voice format.
            val fallbackFile = VoiceMessageSupport.newRecordingFile(context, "3gp")
            tryCreateRecorder(
                file = fallbackFile,
                outputFormat = MediaRecorder.OutputFormat.THREE_GPP,
                audioEncoder = MediaRecorder.AudioEncoder.AMR_NB,
                configure = { }
            )?.also {
                voiceFile = fallbackFile
            } ?: run {
                fallbackFile.delete()
                statusMessage = "Microphone recording could not be started. Please close other apps using the microphone and try again."
                return
            }
        }

        if (voiceFile == null) voiceFile = primaryFile
        voiceRecorder = recorder
        voiceStartedAt = SystemClock.elapsedRealtime()
        voiceElapsedMs = 0L
        voiceRecording = true
        statusMessage = "Recording voice message…"
    }

    fun stopVoiceRecording(send: Boolean) {
        val recorder = voiceRecorder
        val file = voiceFile
        voiceRecorder = null
        voiceFile = null
        voiceRecording = false
        voiceElapsedMs = SystemClock.elapsedRealtime() - voiceStartedAt
        if (recorder != null) {
            runCatching { recorder.stop() }
            recorder.release()
        }
        if (!send || file == null) {
            file?.delete()
            statusMessage = if (send) "Voice recording was not saved." else "Voice recording cancelled."
            return
        }
        if (voiceElapsedMs < 500L || file.length() <= 0L) {
            file.delete()
            statusMessage = "Voice message is too short."
            return
        }

        val target = selected ?: run {
            file.delete()
            return
        }
        val authSession = session ?: run {
            file.delete()
            statusMessage = "Please log in again."
            return
        }
        val chatApi = api ?: run {
            file.delete()
            statusMessage = "Chat is not ready yet."
            return
        }
        voiceUploading = true
        mediaProgress = 0
        mediaProgressLabel = "Uploading voice message…"
        statusMessage = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    VoiceMessageSupport.upload(context, file, authSession, target.id) { sent, total ->
                        if (total > 0L) {
                            mediaProgress = (sent.toDouble() / total.toDouble() * 100.0).toInt().coerceIn(0, 99)
                        }
                    }
                }
                val media = result.getOrElse { throw it }
                chatApi.sendMediaMessage(target.id, media)
                messages = chatApi.messages(target.id)
                reactions = chatApi.reactions(target.id)
                chatSummaries = chatApi.chatSummaries()
                mediaProgress = 100
                mediaProgressLabel = "Voice message sent"
                statusMessage = "Voice message sent."
            } catch (error: Throwable) {
                statusMessage = if (isBlockedSendError(error)) null else (error.message ?: "Voice message could not be sent.")
            } finally {
                file.delete()
                voiceUploading = false
                mediaProgress = 0
                mediaProgressLabel = ""
            }
        }
    }

    val voicePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        voicePermissionPending = false
        if (granted) {
            startVoiceAfterPermission = true
        } else {
            statusMessage = "Microphone permission is required for voice messages."
        }
    }

    LaunchedEffect(startVoiceAfterPermission) {
        if (startVoiceAfterPermission) {
            startVoiceAfterPermission = false
            startVoiceRecordingNow()
        }
    }

    LaunchedEffect(voiceRecording) {
        if (voiceRecording) {
            while (voiceRecording) {
                voiceElapsedMs = SystemClock.elapsedRealtime() - voiceStartedAt
                if (voiceElapsedMs >= 5 * 60 * 1000L) {
                    stopVoiceRecording(send = true)
                    break
                }
                delay(250L)
            }
        }
    }

    DisposableEffect(selected?.id) {
        onDispose {
            voiceRecorder?.let { recorder ->
                runCatching { recorder.stop() }
                recorder.release()
            }
            voiceRecorder = null
            voiceFile?.delete()
            voiceFile = null
            voicePlayer?.release()
            voicePlayer = null
            voicePlayingId = null
        }
    }

    fun requestVoiceRecording() {
        if (chatBlocked || mediaBusy || voiceUploading) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startVoiceRecordingNow()
        } else {
            voicePermissionPending = true
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun toggleVoicePlayback(message: ChatMessage) {
        if (voicePlayingId == message.id) {
            stopVoicePlayback()
            return
        }
        stopVoicePlayback()
        runCatching {
            val player = MediaPlayer()
            voicePlayer = player
            voicePlayingId = message.id
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener {
                it.release()
                if (voicePlayer === it) {
                    voicePlayer = null
                    voicePlayingId = null
                }
            }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                if (voicePlayer === mp) {
                    voicePlayer = null
                    voicePlayingId = null
                }
                statusMessage = "Could not play this voice message."
                true
            }
            player.setDataSource(message.mediaUrl)
            player.prepareAsync()
        }.onFailure {
            stopVoicePlayback()
            statusMessage = it.message ?: "Could not play this voice message."
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && selected != null && !chatBlocked) {
            pendingMediaItems = uris.map { readPendingAttachment(it) }
            statusMessage = null
            mediaProgress = 0
            mediaProgressLabel = ""
        }
    }

    val saveAttachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { destination ->
        val target = saveTarget
        saveTarget = null
        if (destination != null && target != null) {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { saveRemoteAttachment(context, target.mediaUrl, destination) }.getOrThrow() }
                    .onSuccess { statusMessage = "Saved to the selected location." }
                    .onFailure { statusMessage = it.message ?: "Could not save attachment." }
            }
        }
    }

    fun reload() {
        val a = api ?: return
        busy = true
        scope.launch {
            runCatching {
                val (friendList, _) = coroutineScope {
                    val presenceDeferred = async(Dispatchers.IO) { a.touchPresence() }
                    val friendsDeferred = async(Dispatchers.IO) { a.friends() }
                    friendsDeferred.await() to presenceDeferred.await()
                }
                val friendIds = friendList.mapTo(hashSetOf()) { it.id }
                val (requestsResult, onlineResult) = coroutineScope {
                    val requestsDeferred = async(Dispatchers.IO) { a.requests() }
                    val onlineDeferred = async(Dispatchers.IO) { a.onlineUsers(friendIds) }
                    requestsDeferred.await() to onlineDeferred.await()
                }
                Triple(friendList, requestsResult, onlineResult)
            }.onSuccess { (f, r, o) ->
                friends = f
                requests = r
                online = o
                chatSummaries = runCatching { a.chatSummaries(f) }.getOrDefault(emptyList())
            }.onFailure { statusMessage = it.message ?: "Could not load Friends." }
            busy = false
        }
    }

    LaunchedEffect(session?.accessToken, refreshTrigger) { if (session != null) reload() }

    LaunchedEffect(api) {
        if (api != null) while (true) {
            runCatching {
                val friendList = coroutineScope {
                    val presenceDeferred = async(Dispatchers.IO) { api.touchPresence() }
                    val friendsDeferred = async(Dispatchers.IO) { api.friends() }
                    friendsDeferred.await().also { presenceDeferred.await() }
                }
                friends = friendList
                coroutineScope {
                    val requestsDeferred = async(Dispatchers.IO) { api.requests() }
                    val onlineDeferred = async(Dispatchers.IO) {
                        api.onlineUsers(friendList.mapTo(hashSetOf()) { it.id })
                    }
                    val summariesDeferred = async(Dispatchers.IO) {
                        api.chatSummaries(friendList)
                    }
                    requests = requestsDeferred.await()
                    online = onlineDeferred.await()
                    chatSummaries = summariesDeferred.await()
                }
            }
            delay(10000)
        }
    }

    LaunchedEffect(selected?.id, api, refreshTrigger) {
        val current = selected ?: return@LaunchedEffect
        val a = api ?: return@LaunchedEffect
        messages = emptyList()
        searchResults = null
        pendingMediaItems = emptyList()
        if (voiceRecording) stopVoiceRecording(send = false)
        stopVoicePlayback()
        mediaProgress = 0
        mediaProgressLabel = ""
        fullScreenImage = null
        hasOlderMessages = false
        initialMessagesLoaded = false
        runCatching {
            a.markSeen(current.id)
            val page = a.messagesPage(current.id)
            messages = page.first
            hasOlderMessages = page.second
            initialMessagesLoaded = true
            reactions = runCatching { a.reactionsForMessageIds(messages.map { it.id }) }.getOrDefault(emptyList())
        }.onFailure { statusMessage = it.message ?: "Could not load messages." }
        while (true) {
            delay(3000)
            runCatching {
                a.markSeen(current.id)
                val fresh = a.messages(current.id)
                val existingById = messages.associateBy { it.id }
                val freshNew = fresh.filter { it.id !in existingById }
                val hasNewIncoming = freshNew.any { it.senderId == current.id }

                // Keep the established chat order for messages that are already on screen.
                // Newly arrived messages are appended in the server's chronological order.
                // This avoids a device/server timestamp difference moving an incoming
                // message above the user's most recent message.
                val merged = ArrayList<ChatMessage>(messages.size + freshNew.size)
                merged.addAll(messages.map { existingById[it.id] ?: it })
                fresh.forEach { message ->
                    if (message.id in existingById) {
                        val index = merged.indexOfFirst { it.id == message.id }
                        if (index >= 0) merged[index] = message
                    }
                }
                merged.addAll(freshNew.sortedBy { it.createdAt })
                messages = merged

                if (hasNewIncoming && !showLatestButton) {
                    pendingLatestScroll = true
                }
                reactions = runCatching { a.reactionsForMessageIds(messages.map { it.id }) }.getOrDefault(emptyList())
                messages.filter { it.senderId == current.id && it.deliveredAt.isBlank() }.forEach { a.markDelivered(it.id) }
            }
        }
    }

    LaunchedEffect(chatSearch, selected?.id) {
        val current = selected ?: return@LaunchedEffect
        val a = api ?: return@LaunchedEffect
        if (chatSearch.isBlank()) searchResults = null
        else {
            delay(300)
            searchResults = runCatching { a.searchMessages(current.id, chatSearch) }.getOrDefault(emptyList())
        }
    }

    DisposableEffect(api, selected?.id) {
        val selectedId = selected?.id
        val realtime = api?.let { currentApi ->
            RealtimeMessagesClient(
                { currentApi.token() },
                currentApi.userId(),
                FRIENDS_KEY,
                onMessage = { id, senderId, body, createdAt ->
                    scope.launch(Dispatchers.Main) {
                        if (selectedId != null && selected?.id == selectedId && senderId == selectedId && messages.none { it.id == id }) {
                            // Realtime delivery can arrive before the polling refresh.
                            // Keep incoming messages in the same chronological order.
                            val wasAtLatest = !showLatestButton
                            val incoming = ChatMessage(id, senderId, body, createdAt, Instant.now().toString(), "", "", "", "")
                            // Realtime arrival is the authoritative position for a new
                            // incoming message while this chat is open. Append it to
                            // the current conversation instead of re-sorting by timestamp.
                            messages = if (messages.any { it.id == id }) {
                                messages
                            } else {
                                messages + incoming
                            }
                            if (wasAtLatest) pendingLatestScroll = true
                            scope.launch(Dispatchers.IO) {
                                currentApi.markDelivered(id)
                                currentApi.markSeen(selectedId)
                            }
                        }
                    }
                },
                onMessageChange = { change ->
                    when {
                        selectedId != null && change.eventType.equals("DELETE", true) -> {
                            scope.launch(Dispatchers.Main) {
                                if (selected?.id == selectedId && change.id.isNotBlank()) {
                                    // Remove the deleted row locally as soon as Realtime delivers
                                    // the DELETE event; do not wait for the 3-second polling reload.
                                    messages = messages.filterNot { it.id == change.id }
                                    reactions = reactions.filterNot { it.messageId == change.id }
                                    searchResults = searchResults?.filterNot { it.id == change.id }
                                    if (selectedMessage?.id == change.id) selectedMessage = null
                                    chatSummaries = runCatching { currentApi.chatSummaries(friends) }.getOrDefault(chatSummaries)
                                }
                            }
                        }
                        selectedId == null && change.eventType.equals("INSERT", true) -> {
                            scope.launch(Dispatchers.Main) {
                                chatSummaries = currentApi.chatSummaries(friends)
                            }
                        }
                    }
                }
            )
        }
        realtime?.start()
        onDispose { realtime?.stop() }
    }

    DisposableEffect(api) {
        val currentApi = api
        val realtime = currentApi?.let { a ->
            RealtimeFriendshipsClient(
                accessTokenProvider = { a.token() },
                userId = a.userId(),
                apiKey = FRIENDS_KEY,
                onFriendshipChange = {
                    scope.launch(Dispatchers.Main) {
                        if (api === a) reload()
                    }
                }
            )
        }
        realtime?.start()
        onDispose { realtime?.stop() }
    }


    if (checkingSession) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    if (session == null || api == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(60.dp))
            Icon(Icons.Default.Lock, null, Modifier.size(72.dp))
            Text("Login required", style = MaterialTheme.typography.headlineSmall)
            Text("Please log in from the Profile tab first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    unfriendTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!unfriendBusy) unfriendTarget = null },
            title = { Text("Unfriend ${target.username}?") },
            text = { Text("You can send a new friend request later. Your existing chat messages will not be deleted.") },
            confirmButton = {
                TextButton(
                    enabled = !unfriendBusy,
                    onClick = {
                        unfriendBusy = true
                        scope.launch {
                            runCatching {
                                api.unfriend(target.id)
                                if (selected?.id == target.id) {
                                    selected = null
                                    messages = emptyList()
                                    reactions = emptyList()
                                    pendingMediaItems = emptyList()
                                }
                                chatSummaries = api.chatSummaries()
                                friends = api.friends()
                                unfriendTarget = null
                                statusMessage = "Friend removed"
                            }.onFailure {
                                statusMessage = it.message ?: "Could not unfriend this user."
                            }
                            unfriendBusy = false
                        }
                    }
                ) { Text("Unfriend") }
            },
            dismissButton = {
                TextButton(enabled = !unfriendBusy, onClick = { unfriendTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (selected != null) {
        val chat = selected!!
        val chatThemeStore = remember { ChatThemeStore(context) }
        var chatThemeId by remember(chat.id) { mutableStateOf(chatThemeStore.themeId(chat.id)) }
        var chatWallpaperUri by remember(chat.id) { mutableStateOf(chatThemeStore.wallpaperUri(chat.id)) }
        val chatTheme = chatThemeOption(chatThemeId)
        val chatWallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                chatThemeStore.setWallpaper(chat.id, uri.toString())
                chatWallpaperUri = uri.toString()
                statusMessage = "Chat wallpaper updated."
            }
        }
        val typingClient = remember(api, chat.id) {
            api?.let { a ->
                RealtimeTypingClient(
                    { a.token() }, a.userId(), FRIENDS_KEY, chat.id
                ) { value ->
                    scope.launch(Dispatchers.Main) { if (selected?.id == chat.id) otherTyping = value }
                }
            }
        }
        DisposableEffect(typingClient) {
            typingClient?.start()
            onDispose { typingClient?.stop(); otherTyping = false }
        }
        LaunchedEffect(text, typingClient) {
            if (text.isBlank()) {
                typingClient?.setTyping(false)
            } else {
                val snapshot = text
                typingClient?.setTyping(true)
                delay(1500)
                if (text == snapshot) typingClient?.setTyping(false)
            }
        }
        val listState = rememberLazyListState()
        LaunchedEffect(messages.size, chat.id) {
            if (pendingLatestScroll && messages.isNotEmpty()) {
                pendingLatestScroll = false
                withFrameNanos { }
                runCatching { listState.scrollToItem(messages.lastIndex) }
            }
        }
        val selectedForActions = selectedMessage
        val replyTarget = replyingTo
        val visibleMessages = searchResults ?: messages

        LaunchedEffect(chat.id) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            }.collectLatest { lastVisible ->
                // "At latest" must be based on the LAST visible message, not the first.
                // Using firstVisibleItemIndex made showLatestButton true even while the
                // user was already at the bottom of a long chat, so incoming messages
                // could remain hidden underneath the composer.
                val atLatest = messages.isNotEmpty() && lastVisible >= messages.lastIndex - 1
                showLatestButton = messages.isNotEmpty() && !atLatest
                if (lastVisible <= 2 && chatSearch.isBlank() && !loadingOlderMessages && hasOlderMessages && messages.isNotEmpty()) {
                    loadingOlderMessages = true
                    val oldest = messages.first()
                    runCatching { api.messagesPage(chat.id, oldest.createdAt) }
                        .onSuccess { (older, more) ->
                            messages = (older + messages).distinctBy { it.id }.sortedBy { it.createdAt }
                            hasOlderMessages = more
                        }
                        .onFailure { statusMessage = it.message ?: "Could not load older messages." }
                    loadingOlderMessages = false
                }
            }
        }

        LaunchedEffect(initialMessagesLoaded, chat.id) {
            if (initialMessagesLoaded && messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
        }

        LaunchedEffect(chat.id) {
            chatProfile = runCatching { api.profile(chat.id) }.getOrNull()
            chatFriendSince = runCatching { api.friendshipSince(chat.id) }.getOrDefault("")
            chatMuted = runCatching { api.isMuted(chat.id) }.getOrDefault(false)
            chatPinned = runCatching { api.isPinned(chat.id) }.getOrDefault(false)
            chatBlocked = runCatching { api.isBlocked(chat.id) }.getOrDefault(false)
            chatSearch = ""
            showChatSearch = false
            showChatMenu = false
            showChatThemeDialog = false
        }

        if (showChatProfile) {
            AlertDialog(
                onDismissRequest = { showChatProfile = false },
                title = { Text(chatProfile?.username ?: chat.username) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val avatar = chatProfile?.avatarUrl.orEmpty().ifBlank { chat.avatarUrl }
                            if (avatar.isNotBlank()) AsyncImage(model = avatar, contentDescription = "Profile picture", modifier = Modifier.size(168.dp).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            else Surface(modifier = Modifier.size(168.dp).clip(CircleShape)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, "Profile picture", Modifier.size(72.dp)) } }
                        }
                        Text("Username: ${chatProfile?.username ?: chat.username}")
                        Text("Gender: ${chatProfile?.gender ?: "—"}")
                        val bio = chatProfile?.bio.orEmpty()
                        if (bio.isNotBlank()) Text("Bio: $bio")
                        if (chatFriendSince.isNotBlank()) Text("Friends since ${ChatTimeFormatter.dateLabel(chatFriendSince)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val seen = chatProfile?.lastSeenAt.orEmpty()
                        Text(if (online.any { it.id == chat.id }) "Online now" else if (seen.isBlank()) "Last seen: unknown" else "Last seen: ${ChatTimeFormatter.time(seen)}")
                        Text(if (chatBlocked) "Blocked" else "Not blocked", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { showChatProfile = false }) { Text("Message") }
                        TextButton(onClick = { showChatProfile = false; unfriendTarget = chat }) { Text("Unfriend") }
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching {
                                    api.setBlocked(chat.id, !chatBlocked)
                                    chatBlocked = !chatBlocked
                                    statusMessage = if (chatBlocked) "User blocked" else "User unblocked"
                                }.onFailure { statusMessage = it.message ?: "Could not update block status." }
                            }
                        }) { Text(if (chatBlocked) "Unblock" else "Block") }
                        TextButton(onClick = { showReportDialog = true; showChatProfile = false }) { Text("Report") }
                    }
                }
            )
        }

        if (showReportDialog) {
            AlertDialog(
                onDismissRequest = { showReportDialog = false },
                title = { Text("Report ${chat.username}") },
                text = {
                    OutlinedTextField(
                        value = reportReason,
                        onValueChange = { if (it.length <= 500) reportReason = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Reason") },
                        maxLines = 5
                    )
                },
                confirmButton = {
                    TextButton(enabled = reportReason.trim().isNotBlank(), onClick = {
                        val reason = reportReason.trim()
                        scope.launch {
                            runCatching {
                                api.report(chat.id, reason)
                                reportReason = ""
                                showReportDialog = false
                                statusMessage = "Report submitted"
                            }.onFailure { statusMessage = it.message ?: "Could not submit report." }
                        }
                    }) { Text("Submit") }
                },
                dismissButton = { TextButton(onClick = { showReportDialog = false }) { Text("Cancel") } }
            )
        }

        if (forwardingMessage != null) {
            val targetMessage = forwardingMessage
            val forwardFriends = friends.filter { it.id != api.userId() }
            AlertDialog(
                onDismissRequest = { if (!busy) forwardingMessage = null },
                title = { Text("Forward message") },
                text = {
                    if (forwardFriends.isEmpty()) {
                        Text("You don't have any friends to forward this message to.")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(forwardFriends, key = { it.id }) { friend ->
                                Row(
                                    Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                        val target = targetMessage ?: return@clickable
                                        busy = true
                                        scope.launch {
                                            runCatching {
                                                api.forwardMessage(friend.id, target)
                                            }.onSuccess {
                                                statusMessage = "Forwarded to " + friend.username
                                                forwardingMessage = null
                                                selectedMessage = null
                                            }.onFailure {
                                                statusMessage = it.message ?: "Message could not be forwarded."
                                            }
                                            busy = false
                                        }
                                    }.padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val avatar = friend.avatarUrl
                                    if (avatar.isNotBlank()) {
                                        AsyncImage(model = avatar, contentDescription = null, modifier = Modifier.size(42.dp).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                    } else {
                                        Surface(modifier = Modifier.size(42.dp), tonalElevation = 2.dp) {
                                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, "Profile picture") }
                                        }
                                    }
                                    Text(friend.username, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = { forwardingMessage = null }) { Text("Cancel") }
                }
            )
        }

        if (editingMessage != null) {
            AlertDialog(
                onDismissRequest = { editingMessage = null },
                title = { Text("Edit message") },
                text = {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { if (it.length <= 2000) editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 6,
                        supportingText = { Text("${editText.length}/2000") }
                    )
                },
                confirmButton = {
                    TextButton(enabled = editText.trim().isNotBlank(), onClick = {
                        val target = editingMessage ?: return@TextButton
                        val newBody = editText.trim()
                        scope.launch {
                            runCatching { api.editMessage(target.id, newBody) }
                                .onSuccess { updated ->
                                    messages = messages.map { if (it.id == updated.id) updated else it }
                                    editingMessage = null
                                    selectedMessage = null
                                    statusMessage = null
                                }
                                .onFailure { statusMessage = it.message ?: "Message could not be edited." }
                        }
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { editingMessage = null }) { Text("Cancel") } }
            )
        }

        if (deleteTarget != null) {
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("Delete message") },
                text = { Text("Choose how you want to delete this message.") },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = {
                            val target = deleteTarget ?: return@TextButton
                            deleteTarget = null
                            selectedMessage = null
                            scope.launch {
                                runCatching {
                                    api.deleteForMe(target.id)
                                    messages = messages.filterNot { it.id == target.id }
                                    searchResults = searchResults?.filterNot { it.id == target.id }
                                    reactions = reactions.filterNot { it.messageId == target.id }
                                    chatSummaries = api.chatSummaries()
                                }.onFailure { statusMessage = it.message ?: "Message could not be deleted." }
                            }
                        }) { Text("Delete for me") }
                        if (targetIsMine(deleteTarget, api.userId())) {
                            TextButton(onClick = {
                                val target = deleteTarget ?: return@TextButton
                                deleteTarget = null
                                selectedMessage = null
                                scope.launch {
                                    runCatching {
                                        val updated = api.deleteForEveryone(target.id)
                                        // Keep the sender's open chat in sync immediately.
                                        // Realtime will independently remove the same row on
                                        // the other participant's device.
                                        messages = messages.filterNot { it.id == target.id }
                                        searchResults = searchResults?.filterNot { it.id == target.id }
                                        reactions = reactions.filterNot { it.messageId == target.id }
                                        selectedMessage = null
                                        chatSummaries = api.chatSummaries()
                                    }.onFailure { statusMessage = it.message ?: "Message could not be deleted for everyone." }
                                }
                            }) { Text("Delete for everyone") }
                        }
                    }
                },
                dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
            )
        }

        clearChatMode?.let { mode ->
            val everyone = mode == "everyone"
            AlertDialog(
                onDismissRequest = { clearChatMode = null },
                title = { Text(if (everyone) "Clear my messages for everyone?" else "Clear chat?") },
                text = {
                    Text(
                        if (everyone)
                            "All messages in this chat will be removed for both people."
                        else
                            "All messages in this chat will be cleared from your view."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        clearChatMode = null
                        scope.launch {
                            runCatching {
                                if (everyone) api.clearChatForEveryone(chat.id)
                                else api.clearChatForMe(chat.id)
                                messages = emptyList()
                                searchResults = null
                                reactions = emptyList()
                                hasOlderMessages = false
                                initialMessagesLoaded = true
                                showLatestButton = false
                                chatSummaries = api.chatSummaries()
                                statusMessage = if (everyone) "Your messages were cleared for everyone" else "Chat cleared"
                            }.onFailure { statusMessage = it.message ?: "Chat could not be cleared." }
                        }
                    }) { Text("Clear") }
                },
                dismissButton = { TextButton(onClick = { clearChatMode = null }) { Text("Cancel") }
                }
            )
        }

        if (showChatThemeDialog) {
            ChatThemePickerDialog(
                selectedThemeId = chatThemeId,
                hasWallpaper = chatWallpaperUri.isNotBlank(),
                onThemeSelected = { option ->
                    chatThemeId = option.id
                    chatThemeStore.setTheme(chat.id, option.id)
                    showChatThemeDialog = false
                    statusMessage = "Chat theme: ${option.name}"
                },
                onPickWallpaper = {
                    chatWallpaperPicker.launch(arrayOf("image/*"))
                },
                onRemoveWallpaper = {
                    chatWallpaperUri = ""
                    chatThemeStore.setWallpaper(chat.id, null)
                    statusMessage = "Chat wallpaper removed."
                },
                onDismiss = { showChatThemeDialog = false }
            )
        }

        fullScreenImage?.let { image ->
            Dialog(
                onDismissRequest = { fullScreenImage = null },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().padding(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { fullScreenImage = null }) { Icon(Icons.Default.Close, "Close image") }
                            Text(image.mediaName.ifBlank { "Image" }, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            IconButton(onClick = {
                                saveTarget = image
                                saveAttachmentLauncher.launch(image.mediaName.ifBlank { "image.jpg" })
                            }) { Icon(Icons.Default.Download, "Save image") }
                        }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            AsyncImage(
                                model = image.mediaUrl,
                                contentDescription = image.mediaName.ifBlank { "Image" },
                                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            )
                        }
                    }
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            if (chatWallpaperUri.isNotBlank()) {
                AsyncImage(
                    model = Uri.parse(chatWallpaperUri),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    alpha = 1f
                )
            }
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = if (chatWallpaperUri.isBlank()) chatTheme.background else androidx.compose.ui.graphics.Color.Transparent
            ) {
                Column(Modifier.fillMaxSize()) {
                    Surface(shadowElevation = 2.dp) {
                if (selectedForActions != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { selectedMessage = null }) { Icon(Icons.Default.Close, "Close selection") }
                        Text("1", modifier = Modifier.width(20.dp), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(2.dp))
                        IconButton(onClick = {
                            replyingTo = selectedForActions
                            selectedMessage = null
                            text = ""
                        }) { Icon(Icons.Default.Reply, "Reply") }
                        IconButton(onClick = { deleteTarget = selectedForActions }) { Icon(Icons.Default.Delete, "Delete") }
                        IconButton(onClick = { forwardingMessage = selectedForActions }) {
                            Icon(Icons.Default.Share, "Forward")
                        }
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Message", selectedForActions.body))
                            selectedMessage = null
                            statusMessage = "Copied"
                        }) { Icon(Icons.Default.ContentCopy, "Copy") }
                        if (selectedForActions.senderId == api.userId() && selectedForActions.deletedAt.isBlank()) {
                            IconButton(onClick = {
                                editingMessage = selectedForActions
                                editText = selectedForActions.body
                                selectedMessage = null
                            }) { Icon(Icons.Default.Edit, "Edit") }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selected = null; messages = emptyList(); text = ""; replyingTo = null; pendingMediaItems = emptyList(); mediaProgress = 0; mediaProgressLabel = ""; fullScreenImage = null; chatSearch = ""; showChatSearch = false; unfriendTarget = null }) { Icon(Icons.Default.ArrowBack, "Back") }
                        val headerAvatar = chatProfile?.avatarUrl.orEmpty().ifBlank { chat.avatarUrl }
                        if (headerAvatar.isNotBlank()) AsyncImage(model = headerAvatar, contentDescription = "Profile picture", modifier = Modifier.size(44.dp).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        else Surface(modifier = Modifier.size(44.dp).clip(CircleShape), tonalElevation = 2.dp) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, "Profile picture") } }
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(
                                chat.username,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            val isOnline = online.any { it.id == chat.id }
                            if (chatFriendSince.isNotBlank()) Text("Friends since ${ChatTimeFormatter.dateLabel(chatFriendSince)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val seen = chatProfile?.lastSeenAt.orEmpty()
                            Text(
                                when {
                                    chatBlocked -> "Blocked"
                                    isOnline -> "Online"
                                    seen.isBlank() -> "Offline"
                                    else -> "Last seen ${ChatTimeFormatter.time(seen)}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box {
                            IconButton(onClick = { showChatMenu = true }) {
                                Icon(Icons.Default.MoreVert, "Chat actions")
                            }
                            DropdownMenu(expanded = showChatMenu, onDismissRequest = { showChatMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("🔍 Search messages") },
                                    onClick = { showChatMenu = false; showChatSearch = !showChatSearch; if (!showChatSearch) chatSearch = "" }
                                )
                                DropdownMenuItem(
                                    text = { Text("🎨 Chat Theme") },
                                    onClick = {
                                        showChatMenu = false
                                        showChatThemeDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (chatPinned) "📌 Unpin chat" else "📌 Pin chat") },
                                    onClick = {
                                        showChatMenu = false
                                        scope.launch {
                                            runCatching {
                                                api.setPinned(chat.id, !chatPinned)
                                                chatPinned = !chatPinned
                                                chatSummaries = api.chatSummaries()
                                                statusMessage = if (chatPinned) "Chat pinned" else "Chat unpinned"
                                            }.onFailure { statusMessage = it.message ?: "Could not update pin." }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (chatMuted) "🔕 Unmute chat" else "🔔 Mute chat") },
                                    onClick = {
                                        showChatMenu = false
                                        scope.launch {
                                            runCatching {
                                                api.setMuted(chat.id, !chatMuted)
                                                chatMuted = !chatMuted
                                                statusMessage = if (chatMuted) "Chat unmuted" else "Chat muted"
                                            }.onFailure { statusMessage = it.message ?: "Could not update mute." }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("👤 View profile") },
                                    onClick = {
                                        showChatMenu = false
                                        scope.launch {
                                            chatProfile = runCatching { api.profile(chat.id) }.getOrNull()
                                            showChatProfile = true
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("👥 Unfriend") },
                                    onClick = {
                                        showChatMenu = false
                                        unfriendTarget = chat
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (chatBlocked) "🚫 Unblock user" else "🚫 Block user") },
                                    onClick = {
                                        showChatMenu = false
                                        scope.launch {
                                            runCatching {
                                                api.setBlocked(chat.id, !chatBlocked)
                                                chatBlocked = !chatBlocked
                                                statusMessage = if (chatBlocked) "User blocked" else "User unblocked"
                                            }.onFailure { statusMessage = it.message ?: "Could not update block status." }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("⚠️ Report user") },
                                    onClick = {
                                        showChatMenu = false
                                        reportReason = ""
                                        showReportDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("🗑️ Clear chat") },
                                    onClick = { showChatMenu = false; clearChatMode = "me" }
                                )
                                DropdownMenuItem(
                                    text = { Text("🗑️ Clear chat for everyone") },
                                    onClick = { showChatMenu = false; clearChatMode = "everyone" }
                                )
                            }
                        }                    }
                }
            }

            if (showChatSearch && selectedForActions == null) {
                OutlinedTextField(
                    value = chatSearch,
                    onValueChange = { chatSearch = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    singleLine = true,
                    label = { Text("Search messages") },
                    trailingIcon = { if (chatSearch.isNotBlank()) IconButton(onClick = { chatSearch = "" }) { Icon(Icons.Default.Clear, "Clear search") } }
                )
            }

            if (selectedForActions != null) {
                Surface(
                    tonalElevation = 3.dp,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("👍", "❤️", "😂", "😮", "😢", "😡").forEach { emoji ->
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching {
                                        val mineReaction = reactions.firstOrNull { it.messageId == selectedForActions.id && it.userId == api.userId() }
                                        if (mineReaction?.reaction == emoji) api.removeReaction(selectedForActions.id)
                                        else api.setReaction(selectedForActions.id, emoji)
                                        reactions = api.reactions(chat.id)
                                    }.onFailure { statusMessage = it.message ?: "Reaction failed." }
                                }
                            }) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                items(visibleMessages, key = { it.id }) { m ->
                    val mine = m.senderId == api.userId()
                    val isSelected = selectedForActions?.id == m.id
                    val reacted = reactions.filter { it.messageId == m.id }
                    val myReaction = reacted.firstOrNull { it.userId == api.userId() }
                    val replyPreview = m.replyToId.takeIf { it.isNotBlank() }?.let { rid -> messages.firstOrNull { it.id == rid } }
                    val isHighlighted = highlightedMessageId == m.id

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                    ) {
                        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                            Surface(
                                color = if (mine) chatTheme.myBubble else chatTheme.otherBubble,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .then(if (isHighlighted) Modifier.padding(2.dp) else Modifier)
                                    .pointerInput(m.id) {
                                        var totalDrag = 0f
                                        detectHorizontalDragGestures(
                                            onHorizontalDrag = { change, dragAmount ->
                                                change.consume()
                                                totalDrag += dragAmount
                                            },
                                            onDragEnd = {
                                                // WhatsApp-style: swipe a message to the right
                                                // far enough to start replying to that exact message.
                                                if (totalDrag >= 72f && selectedForActions == null) {
                                                    replyingTo = m
                                                    text = ""
                                                }
                                                totalDrag = 0f
                                            },
                                            onDragCancel = { totalDrag = 0f }
                                        )
                                    }
                                    .combinedClickable(
                                    onClick = { if (selectedForActions != null) selectedMessage = if (isSelected) null else m },
                                    onLongClick = { selectedMessage = m }
                                )
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                                    replyPreview?.let { quoted ->
                                        Surface(
                                            tonalElevation = 2.dp,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.widthIn(max = 300.dp).wrapContentWidth().padding(bottom = 5.dp).clickable {
                                                    scope.launch {
                                                        var index = messages.indexOfFirst { it.id == quoted.id }
                                                        while (index < 0 && hasOlderMessages && messages.isNotEmpty()) {
                                                            val oldest = messages.first()
                                                            val page = runCatching { api.messagesPage(chat.id, oldest.createdAt) }.getOrNull() ?: break
                                                            val beforeCount = messages.size
                                                            messages = (page.first + messages).distinctBy { it.id }.sortedBy { it.createdAt }
                                                            hasOlderMessages = page.second
                                                            if (messages.size == beforeCount) break
                                                            index = messages.indexOfFirst { it.id == quoted.id }
                                                        }
                                                        if (index >= 0) {
                                                            highlightedMessageId = quoted.id
                                                            listState.animateScrollToItem(index)
                                                            delay(900)
                                                            highlightedMessageId = null
                                                        }
                                                    }
                                                }
                                        ) {
                                            Text(
                                                "↩ ${quoted.body}",
                                                maxLines = 2,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelMedium,
                                                modifier = Modifier.padding(6.dp)
                                            )
                                        }
                                    }
                                    if (m.messageType != "text" && m.mediaUrl.isNotBlank()) {
                                        Surface(
                                            tonalElevation = 2.dp,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.widthIn(max = 300.dp).wrapContentWidth(Alignment.Start).padding(bottom = 5.dp)
                                        ) {
                                            Column(Modifier.widthIn(max = 300.dp).wrapContentWidth(Alignment.Start).padding(8.dp)) {
                                                if (m.messageType == "image") {
                                                    AsyncImage(
                                                        model = m.mediaUrl,
                                                        contentDescription = m.mediaName.ifBlank { "Image" },
                                                        modifier = Modifier.widthIn(max = 280.dp).heightIn(max = 180.dp).clip(RoundedCornerShape(8.dp)).clickable { fullScreenImage = m },
                                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                                    )
                                                    if (m.mediaName.isNotBlank()) {
                                                        Text(m.mediaName, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(max = 280.dp).padding(top = 6.dp))
                                                    }
                                                } else if (m.messageType == "audio") {
                                                    Row(
                                                        Modifier
                                                            .widthIn(max = 280.dp)
                                                            .wrapContentWidth(Alignment.Start)
                                                            .clickable { toggleVoicePlayback(m) },
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Surface(
                                                            modifier = Modifier.size(44.dp),
                                                            shape = CircleShape,
                                                            tonalElevation = 1.dp
                                                        ) {
                                                            Box(contentAlignment = Alignment.Center) {
                                                                Icon(
                                                                    if (voicePlayingId == m.id) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                                    contentDescription = if (voicePlayingId == m.id) "Pause voice message" else "Play voice message"
                                                                )
                                                            }
                                                        }
                                                        Spacer(Modifier.width(9.dp))
                                                        Column(Modifier.widthIn(max = 210.dp)) {
                                                            Text("Voice message", fontWeight = FontWeight.Medium)
                                                            Text(
                                                                if (voicePlayingId == m.id) "Playing…" else formatAttachmentSize(m.mediaSize),
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }
                                                } else {
                                                    Row(
                                                        Modifier.widthIn(max = 280.dp).wrapContentWidth(Alignment.Start).clickable {
                                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.mediaUrl))) }
                                                                .onFailure { statusMessage = "No app is available to open this attachment." }
                                                        },
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Surface(modifier = Modifier.size(42.dp), shape = RoundedCornerShape(10.dp), tonalElevation = 1.dp) {
                                                            Box(contentAlignment = Alignment.Center) {
                                                                Icon(
                                                                    when (m.messageType) {
                                                                        "video" -> Icons.Default.VideoFile
                                                                        "audio" -> Icons.Default.AudioFile
                                                                        else -> Icons.Default.InsertDriveFile
                                                                    },
                                                                    contentDescription = null,
                                                                    modifier = Modifier.size(25.dp)
                                                                )
                                                            }
                                                        }
                                                        Spacer(Modifier.width(9.dp))
                                                        Column(Modifier.widthIn(max = 220.dp).wrapContentWidth(Alignment.Start)) {
                                                            Text(
                                                                m.mediaName.ifBlank {
                                                                    when (m.messageType) {
                                                                        "video" -> "Video"
                                                                        "audio" -> "Audio"
                                                                        else -> "File"
                                                                    }
                                                                },
                                                                maxLines = 1,
                                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                            Text(
                                                                when (m.messageType) {
                                                                    "video" -> "VIDEO"
                                                                    "audio" -> "AUDIO"
                                                                    else -> "FILE"
                                                                } + if (m.mediaSize > 0) " • ${formatAttachmentSize(m.mediaSize)}" else "",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }
                                                }
                                                Row(Modifier.widthIn(max = 280.dp).wrapContentWidth(Alignment.Start), verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        if (m.messageType == "image") "IMAGE" else when (m.messageType) {
                                                            "video" -> "VIDEO"
                                                            "audio" -> "AUDIO"
                                                            else -> "FILE"
                                                        } + if (m.mediaSize > 0) " • ${formatAttachmentSize(m.mediaSize)}" else "",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.weight(1f, fill = false)
                                                    )
                                                    IconButton(onClick = {
                                                        saveTarget = m
                                                        saveAttachmentLauncher.launch(m.mediaName.ifBlank { "attachment" })
                                                    }, modifier = Modifier.size(36.dp)) {
                                                        Icon(Icons.Default.Download, "Save attachment", modifier = Modifier.size(20.dp))
                                                    }
                                                    IconButton(onClick = {
                                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                            type = when (m.messageType) {
                                                                "image" -> "image/*"
                                                                "video" -> "video/*"
                                                                "audio" -> "audio/*"
                                                                else -> "*/*"
                                                            }
                                                            putExtra(Intent.EXTRA_TEXT, m.mediaUrl)
                                                        }
                                                        context.startActivity(Intent.createChooser(shareIntent, "Share attachment"))
                                                    }, modifier = Modifier.size(36.dp)) {
                                                        Icon(Icons.Default.Share, "Share attachment", modifier = Modifier.size(20.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Text(
                                        if (m.deletedAt.isNotBlank()) "This message was deleted" else m.body,
                                        Modifier.padding(top = if (replyPreview != null) 1.dp else 0.dp),
                                        color = if (m.deletedAt.isNotBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                                    )
                                    myReaction?.let { Text(it.reaction, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp)) }
                                    if (reacted.isNotEmpty() && myReaction == null) {
                                        Text(reacted.first().reaction, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                                    }
                                }
                            }
                            val delivery = if (mine) when {
                                m.readAt.isNotBlank() -> "✓✓ Seen"
                                m.deliveredAt.isNotBlank() -> "✓✓ Delivered"
                                else -> "✓ Sent"
                            } else ""
                            val stateLabel = when {
                                m.deletedAt.isNotBlank() -> "  • deleted"
                                m.editedAt.isNotBlank() -> "  • edited"
                                else -> ""
                            }
                            Text(
                                if (mine) "You  ${ChatTimeFormatter.time(m.createdAt)}  $delivery$stateLabel"
                                else "${chat.username}  ${ChatTimeFormatter.time(m.createdAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (mine && m.readAt.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }

                if (showLatestButton && chatSearch.isBlank() && selectedForActions == null && messages.isNotEmpty()) {
                    IconButton(
                        onClick = { scope.launch { listState.animateScrollToItem(messages.lastIndex) } },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 14.dp, bottom = 14.dp)
                            .size(40.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Latest", modifier = Modifier.size(22.dp))
                    }
                }
            }

            // Keep the entire composer (reply/status/attachments/input) immediately above the IME.
            // The message list remains the flexible area, so opening the keyboard does not pan the whole chat.
            Column(Modifier.imePadding()) {
                replyTarget?.let { target ->
                    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Replying to", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(target.body, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { replyingTo = null }) { Icon(Icons.Default.Close, "Cancel reply") }
                    }
                }
            }

            statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
            if (otherTyping) Text("Typing…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            if (pendingMediaItems.isNotEmpty()) {
                Surface(tonalElevation = 3.dp, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Text(
                            if (mediaBusy) "Sending ${pendingMediaItems.size} file(s) • ${mediaProgress}%"
                            else "${pendingMediaItems.size} file(s) ready to send",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        pendingMediaItems.forEachIndexed { index, item ->
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (item.mime.startsWith("image/")) {
                                    AsyncImage(model = item.uri, contentDescription = "Selected image", modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                } else {
                                    Icon(Icons.Default.AttachFile, "Attachment", Modifier.size(36.dp))
                                }
                                Text(item.name, modifier = Modifier.weight(1f).padding(horizontal = 10.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                if (!mediaBusy) {
                                    IconButton(onClick = {
                                        pendingMediaItems = pendingMediaItems.filterIndexed { i, _ -> i != index }
                                    }) { Icon(Icons.Default.Close, "Remove attachment") }
                                }
                            }
                        }
                        if (mediaBusy) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = mediaProgress / 100f, modifier = Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(mediaProgressLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                TextButton(onClick = { mediaUploadJob?.cancel() }) { Text("Cancel") }
                            }
                        }
                    }
                }
            }
            Surface(tonalElevation = 2.dp) {
                if (chatBlocked) {
                    Text("You blocked this user. Unblock from the profile to send messages.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(14.dp))
                } else Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; statusMessage = null },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (pendingMediaItems.isNotEmpty()) "Add a caption (optional)" else "Message") },
                        maxLines = 4
                    )
                    Spacer(Modifier.width(2.dp))
                    if (voiceRecording) {
                        Text(
                            String.format(java.util.Locale.US, "%02d:%02d", voiceElapsedMs / 60000L, (voiceElapsedMs / 1000L) % 60L),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
                        )
                        IconButton(onClick = { stopVoiceRecording(send = false) }) {
                            Icon(Icons.Default.Delete, "Cancel voice recording")
                        }
                        IconButton(onClick = { stopVoiceRecording(send = true) }) {
                            Icon(Icons.Default.Send, "Send voice message")
                        }
                    } else {
                        IconButton(enabled = !mediaBusy && !voiceUploading, onClick = { requestVoiceRecording() }) {
                            Icon(Icons.Default.Mic, if (voiceUploading) "Sending voice message" else "Record voice message")
                        }
                    }
                    Spacer(Modifier.width(2.dp))
                    IconButton(enabled = !mediaBusy && !voiceUploading && !voiceRecording, onClick = { mediaPicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.AttachFile, if (mediaBusy) "Sending attachment" else "Attach files")
                    }
                    IconButton(enabled = !mediaBusy && !voiceUploading && !voiceRecording && (text.isNotBlank() || pendingMediaItems.isNotEmpty()), onClick = {
                        val pendingItems = pendingMediaItems
                        val outgoing = text.trim()
                        val replyId = replyingTo?.id
                        statusMessage = null
                        mediaUploadJob = scope.launch {
                            if (pendingItems.isNotEmpty()) {
                                mediaBusy = true
                                mediaProgress = 0
                                mediaProgressLabel = "Preparing ${pendingItems.size} file(s)…"
                                var sentCount = 0
                                try {
                                    for ((index, item) in pendingItems.withIndex()) {
                                        mediaProgressLabel = "Uploading ${index + 1}/${pendingItems.size}: ${item.name}"
                                        val result = withContext(Dispatchers.IO) {
                                            ChatMediaSupport.upload(
                                                context,
                                                item.uri,
                                                session!!,
                                                chat.id
                                            ) { sentBytes, totalBytes ->
                                                if (totalBytes > 0L) {
                                                    val fileFraction = sentBytes.toDouble() / totalBytes.toDouble()
                                                    mediaProgress = (((index + fileFraction) / pendingItems.size) * 100.0).toInt().coerceIn(0, 99)
                                                }
                                            }
                                        }
                                        val media = result.getOrElse { throw it }
                                        api.sendMediaMessage(chat.id, media, if (index == 0) outgoing else "")
                                        sentCount++
                                        mediaProgress = ((sentCount.toDouble() / pendingItems.size) * 100.0).toInt().coerceIn(0, 100)
                                    }
                                    messages = api.messages(chat.id)
                                    withFrameNanos { }
                                    if (messages.isNotEmpty()) {
                                        listState.animateScrollToItem(messages.lastIndex)
                                    }
                                    reactions = api.reactions(chat.id)
                                    chatSummaries = api.chatSummaries()
                                    text = ""
                                    replyingTo = null
                                    pendingMediaItems = emptyList()
                                    mediaProgress = 100
                                    mediaProgressLabel = "${sentCount} file(s) sent"
                                    statusMessage = "Attachment(s) sent."
                                } catch (error: Throwable) {
                                    if (error is CancellationException) {
                                        mediaProgressLabel = "Upload cancelled"
                                        statusMessage = null
                                    } else {
                                        statusMessage = if (isBlockedSendError(error)) null else (error.message ?: "Could not send attachment(s).")
                                    }
                                } finally {
                                    mediaBusy = false
                                    if (pendingMediaItems.isEmpty()) {
                                        mediaProgress = 0
                                        mediaProgressLabel = ""
                                    } else if (!mediaBusy) {
                                        mediaProgress = 0
                                    }
                                    mediaUploadJob = null
                                }
                            } else {
                                text = ""; replyingTo = null
                                val optimistic = ChatMessage("local-${System.nanoTime()}", api.userId(), outgoing, "")
                                messages = messages + optimistic
                                runCatching {
                                    api.sendMessage(chat.id, outgoing, replyId)
                                    messages = api.messages(chat.id)
                                    withFrameNanos { }
                                    if (messages.isNotEmpty()) {
                                        listState.animateScrollToItem(messages.lastIndex)
                                    }
                                    reactions = api.reactions(chat.id)
                                    chatSummaries = api.chatSummaries()
                                }.onFailure { error ->
                                    messages = messages.filterNot { it.id == optimistic.id }
                                    statusMessage = if (isBlockedSendError(error)) null else (error.message ?: "Message could not be sent.")
                                }
                            }
                        }
                    }) { Icon(Icons.Default.Send, "Send") }
                }

                }
            }
                }
            }
        }
        return
    }

    val onlineIds = remember(online) { online.mapTo(hashSetOf()) { it.id } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Friends", style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = { reload() }, enabled = !busy) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Friends")
                }
            }
        }
        if (session != null) {
            item {
                StoriesSection(session = session!!, onStatus = { statusMessage = it })
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
        item {
            Text("Online people and accepted friends are shown here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query,
                    {
                        query = it
                        results = emptyList()
                        searchError = null
                        searchState = if (it.isBlank()) FriendSearchState.Idle else FriendSearchState.Idle
                    },
                    Modifier.weight(1f),
                    label = { Text("Search username") },
                    singleLine = true
                )
                Button(enabled = query.isNotBlank() && searchState != FriendSearchState.Loading, onClick = {
                    scope.launch {
                        val term = query.trim()
                        if (term.isBlank()) {
                            results = emptyList()
                            searchError = null
                            searchState = FriendSearchState.Idle
                            return@launch
                        }
                        searchState = FriendSearchState.Loading
                        searchError = null
                        statusMessage = null
                        results = emptyList()
                        runCatching { api.search(term) }
                            .onSuccess {
                                results = it
                                searchState = if (it.isEmpty()) FriendSearchState.Empty else FriendSearchState.Results
                            }
                            .onFailure {
                                results = emptyList()
                                searchError = it.message ?: "Could not search users."
                                searchState = FriendSearchState.Error
                            }
                    }
                }) {
                    if (searchState == FriendSearchState.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Search")
                    }
                }
            }
        }
        item {
            TabRow(selectedTabIndex = friendTab) {
                Tab(
                    selected = friendTab == 0,
                    onClick = { friendTab = 0 },
                    text = { Text("Online Users") }
                )
                Tab(
                    selected = friendTab == 1,
                    onClick = { friendTab = 1 },
                    text = { Text("Friend List") }
                )
            }
        }
        when (searchState) {
            FriendSearchState.Loading -> item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Searching...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FriendSearchState.Empty -> item {
                Text("No users found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FriendSearchState.Error -> item {
                Text(
                    searchError ?: "Could not search users.",
                    color = MaterialTheme.colorScheme.error
                )
            }
            else -> Unit
        }
        if (results.isNotEmpty()) {
            item { Text("Search results", style = MaterialTheme.typography.titleMedium) }
            items(results, key = { "result-${it.id}" }) { u ->
                val isFriend = friends.any { it.id == u.id }
                val pendingRequest = requests.firstOrNull { it.user.id == u.id }
                val relationshipLabel = when {
                    isFriend -> "Friend"
                    pendingRequest?.incoming == true -> "Accept"
                    pendingRequest != null -> "Pending"
                    else -> "Add"
                }
                ListItem(
                    headlineContent = { Text(u.username) },
                    supportingContent = {
                        Text(
                            when {
                                isFriend -> "Already your friend"
                                pendingRequest?.incoming == true -> "Sent you a friend request"
                                pendingRequest != null -> "Friend request pending"
                                else -> "Not connected"
                            }
                        )
                    },
                    leadingContent = {
                        if (u.avatarUrl.isNotBlank()) AsyncImage(model = u.avatarUrl, contentDescription = "Profile picture", modifier = Modifier.size(44.dp).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        else Icon(Icons.Default.PersonAdd, "Add friend")
                    },
                    trailingContent = {
                        Button(
                            enabled = !isFriend && relationshipLabel != "Pending",
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        if (pendingRequest?.incoming == true) {
                                            api.accept(pendingRequest.id)
                                            "Friend request accepted."
                                        } else {
                                            api.send(u.id)
                                        }
                                    }.onSuccess {
                                        statusMessage = it
                                        reload()
                                    }.onFailure { statusMessage = it.message }
                                }
                            }
                        ) { Text(relationshipLabel) }
                    }
                )
            }
        }
        if (friendTab == 0) {
            item {
                Text("Online Users", style = MaterialTheme.typography.titleMedium)
            }
            if (online.isEmpty()) {
                item {
                    Text("No users are online right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(online, key = { "online-${it.id}" }) { u ->
                    val isFriend = friends.any { it.id == u.id }
                    ListItem(
                        headlineContent = { Text(u.username) },
                        supportingContent = { Text("Online") },
                        leadingContent = {
                            Box {
                                if (u.avatarUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = u.avatarUrl,
                                        contentDescription = "Profile picture",
                                        modifier = Modifier.size(44.dp).clip(CircleShape),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                } else {
                                    Surface(modifier = Modifier.size(44.dp).clip(CircleShape), tonalElevation = 2.dp) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Person, "Profile picture")
                                        }
                                    }
                                }
                                Surface(
                                    modifier = Modifier.size(11.dp).align(Alignment.BottomEnd),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.surface)
                                ) {}
                            }
                        },
                        trailingContent = {
                            Button(
                                enabled = !isFriend,
                                onClick = {
                                    scope.launch {
                                        runCatching { statusMessage = api.send(u.id); reload() }
                                            .onFailure { statusMessage = it.message }
                                    }
                                }
                            ) { Text(if (isFriend) "Friend" else "Add") }
                        }
                    )
                    HorizontalDivider()
                }
            }
        } else {
            if (requests.isNotEmpty()) {
                item { Text("Friend requests", style = MaterialTheme.typography.titleMedium) }
                items(requests, key = { "request-${it.id}" }) { r ->
                    ListItem(
                        headlineContent = { Text(r.user.username) },
                        supportingContent = { Text(if (r.incoming) "Wants to be your friend" else "Pending") },
                        leadingContent = {
                            if (r.user.avatarUrl.isNotBlank()) AsyncImage(model = r.user.avatarUrl, contentDescription = "Profile picture", modifier = Modifier.size(44.dp).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            else Icon(Icons.Default.Person, "Friend request")
                        },
                        trailingContent = {
                            if (r.incoming) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(onClick = {
                                        scope.launch {
                                            runCatching { api.accept(r.id); reload() }
                                                .onFailure { statusMessage = it.message }
                                        }
                                    }) { Text("Accept") }
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            runCatching { api.decline(r.id); reload() }
                                                .onFailure { statusMessage = it.message }
                                        }
                                    }) { Text("Decline") }
                                }
                            } else {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        runCatching { api.cancel(r.id); reload() }
                                            .onFailure { statusMessage = it.message }
                                    }
                                }) { Text("Cancel") }
                            }
                        }
                    )
                }
            }
        }
        if (friendTab == 1) {
            item { Text("Friend List", style = MaterialTheme.typography.titleMedium) }
        if (chatSummaries.isEmpty()) {
            item {
                Text(if (friends.isEmpty()) "No friends yet." else "No chats yet. Open a friend to start chatting.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(chatSummaries, key = { "chat-${it.user.id}" }) { chatItem ->
                val isOnline = chatItem.user.id in onlineIds
                ListItem(
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (chatItem.pinned) {
                                Icon(Icons.Default.PushPin, "Pinned", Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(chatItem.user.username, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            if (chatItem.muted) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Default.NotificationsOff, "Muted", Modifier.size(16.dp))
                            }
                            if (chatItem.unreadCount > 0) {
                                Spacer(Modifier.width(8.dp))
                                Badge { Text(chatItem.unreadCount.toString()) }
                            }
                        }
                    },
                    supportingContent = {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    chatItem.lastMessage.isBlank() -> if (isOnline) "Online now" else "No messages yet"
                                    else -> chatItem.lastMessage
                                },
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            if (chatItem.lastMessageAt.isNotBlank()) {
                                Text(ChatTimeFormatter.listTimestamp(chatItem.lastMessageAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    leadingContent = {
                        Box {
                            if (chatItem.user.avatarUrl.isNotBlank()) AsyncImage(model = chatItem.user.avatarUrl, contentDescription = "Profile picture", modifier = Modifier.size(48.dp).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            else Icon(Icons.Default.Person, "Friend", modifier = Modifier.size(48.dp))
                            if (isOnline) {
                                Surface(
                                    modifier = Modifier.size(13.dp).align(Alignment.BottomEnd),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.surface)
                                ) {}
                            }
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = {
                            unfriendTarget = chatItem.user
                        }) { Icon(Icons.Default.PersonRemove, "Unfriend") }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                selected = chatItem.user
                                selectedMessage = null
                                replyingTo = null
                                deleteTarget = null
                                editingMessage = null
                                editText = ""
                                text = ""
                                statusMessage = null
                            },
                            onLongClick = {
                                selected = chatItem.user
                                unfriendTarget = chatItem.user
                            }
                        )
                )
                HorizontalDivider()
            }
        }
        }
        item {
            statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}