package com.heartbeatheaven.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val SUPABASE_URL = "https://fafvhyeesenpimxncupp.supabase.co"
private const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"

internal data class AccountProfile(
    val id: String,
    val username: String,
    val gender: String,
    val email: String?,
    val phone: String?
)

internal data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val profile: AccountProfile
)

internal class AuthApi(context: Context) {
    private val prefs = context.getSharedPreferences("heartbeat_auth", Context.MODE_PRIVATE)

    fun hasStoredSession(): Boolean = !prefs.getString("access_token", null).isNullOrBlank()

    fun currentSession(): AuthSession? {
        val access = prefs.getString("access_token", null) ?: return null
        val refresh = prefs.getString("refresh_token", "").orEmpty()
        val userId = prefs.getString("user_id", null) ?: return null
        return try {
            val user = requestUser(access)
            val profile = fetchProfile(access, userId, user)
            AuthSession(access, refresh, profile)
        } catch (_: Exception) {
            if (refresh.isBlank()) {
                clear()
                null
            } else {
                try { refreshSession(refresh) } catch (_: Exception) { clear(); null }
            }
        }
    }

    fun signUp(identifier: String, password: String, username: String, gender: String, phoneMode: Boolean): Result<String> {
        return try {
            val body = JSONObject().apply {
                if (phoneMode) put("phone", identifier.trim()) else put("email", identifier.trim())
                put("password", password)
                put("data", JSONObject().apply {
                    put("username", username.trim())
                    put("gender", gender.lowercase())
                })
            }
            val response = request("/auth/v1/signup", "POST", body.toString(), null)
            val json = JSONObject(response.body)
            val access = json.optString("access_token")
            val refresh = json.optString("refresh_token")
            val user = json.optJSONObject("user")
            if (access.isNotBlank() && user != null) {
                saveTokens(access, refresh, user.optString("id"))
                val profile = fetchProfile(access, user.optString("id"), user)
                Result.success("Account created successfully. Welcome, ${profile.username}.")
            } else {
                Result.success(if (phoneMode) "Account created. Complete the phone verification if requested." else "Account created. Check your email if verification is required.")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun signIn(identifier: String, password: String, phoneMode: Boolean): Result<AuthSession> {
        return try {
            val form = buildString {
                append("password=").append(encode(password))
                if (phoneMode) append("&phone=").append(encode(identifier.trim()))
                else append("&email=").append(encode(identifier.trim()))
            }
            val response = request("/auth/v1/token?grant_type=password", "POST", form, "application/x-www-form-urlencoded")
            val json = JSONObject(response.body)
            val access = json.optString("access_token")
            val refresh = json.optString("refresh_token")
            val user = json.optJSONObject("user") ?: error("No user returned")
            val userId = user.optString("id").ifBlank { error("No user id returned") }
            saveTokens(access, refresh, userId)
            Result.success(AuthSession(access, refresh, fetchProfile(access, userId, user)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun signOut() {
        val access = prefs.getString("access_token", null)
        if (!access.isNullOrBlank()) {
            runCatching { request("/auth/v1/logout", "POST", "{}", "application/json", access) }
        }
        clear()
    }

    private fun refreshSession(refreshToken: String): AuthSession {
        val response = request(
            "/auth/v1/token?grant_type=refresh_token",
            "POST",
            "refresh_token=${encode(refreshToken)}",
            "application/x-www-form-urlencoded"
        )
        val json = JSONObject(response.body)
        val access = json.optString("access_token").ifBlank { error("Refresh failed") }
        val refresh = json.optString("refresh_token").ifBlank { refreshToken }
        val user = json.optJSONObject("user") ?: requestUser(access)
        val userId = user.optString("id").ifBlank { error("No user id returned") }
        saveTokens(access, refresh, userId)
        return AuthSession(access, refresh, fetchProfile(access, userId, user))
    }

    private fun requestUser(accessToken: String): JSONObject =
        JSONObject(request("/auth/v1/user", "GET", null, null, accessToken).body)

    private fun fetchProfile(accessToken: String, userId: String, user: JSONObject): AccountProfile {
        val query = "/rest/v1/profiles?id=eq.${encode(userId)}&select=id,username,gender"
        val array = org.json.JSONArray(request(query, "GET", null, null, accessToken).body)
        if (array.length() == 0) error("Profile is not ready yet. Please try again.")
        val row = array.getJSONObject(0)
        return AccountProfile(
            id = row.optString("id", userId),
            username = row.optString("username", "User"),
            gender = row.optString("gender", "male"),
            email = user.optString("email").takeIf { it.isNotBlank() },
            phone = user.optString("phone").takeIf { it.isNotBlank() }
        )
    }

    private fun saveTokens(access: String, refresh: String, userId: String) {
        prefs.edit().putString("access_token", access).putString("refresh_token", refresh).putString("user_id", userId).apply()
    }

    private fun clear() { prefs.edit().clear().apply() }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private data class Response(val code: Int, val body: String)

    private fun request(path: String, method: String, body: String?, contentType: String?, accessToken: String? = null): Response {
        val connection = (URL(SUPABASE_URL + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            connection.setRequestProperty("Accept", "application/json")
            if (!accessToken.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $accessToken")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType ?: "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                val message = runCatching { JSONObject(text).optString("msg").ifBlank { JSONObject(text).optString("message") }.ifBlank { JSONObject(text).optString("error_description") } }.getOrDefault("")
                throw IllegalStateException(if (message.isBlank()) "Request failed (${connection.responseCode})" else message)
            }
            return Response(connection.responseCode, text)
        } finally {
            connection.disconnect()
        }
    }
}
