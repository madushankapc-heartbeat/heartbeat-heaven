package com.heartbeatheaven.app

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val SUPABASE_URL = "https://fafvhyeesenpimxncupp.supabase.co"
private const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_MlBmbt3bdFDjMkikjxrdwg_fa3MqBKs"
private const val AUTH_REDIRECT_URL = "https://heartbeat-heaven.onrender.com"
private const val PASSWORD_RESET_REDIRECT_URL = "heartbeatheaven://auth/reset"

internal data class AccountProfile(
    val id: String,
    val username: String,
    val gender: String,
    val email: String?,
    val phone: String?,
    val isAdmin: Boolean = false
)
internal data class AuthSession(val accessToken: String, val refreshToken: String, val profile: AccountProfile)

internal class AuthApi(context: Context) {
    private val prefs = context.getSharedPreferences("heartbeat_auth", Context.MODE_PRIVATE)
    fun hasStoredSession(): Boolean = !prefs.getString("access_token", null).isNullOrBlank()

    @Synchronized
    fun currentSession(): AuthSession? {
        val access = prefs.getString("access_token", null) ?: return null
        val refresh = prefs.getString("refresh_token", "").orEmpty()
        val userId = prefs.getString("user_id", null) ?: return null
        val cachedUsername = prefs.getString("profile_username", null)
        val cached = if (!cachedUsername.isNullOrBlank()) {
            AuthSession(
                access,
                refresh,
                AccountProfile(
                    userId,
                    cachedUsername,
                    prefs.getString("profile_gender", "male").orEmpty().ifBlank { "male" },
                    prefs.getString("profile_email", null),
                    prefs.getString("profile_phone", null),
                    prefs.getBoolean("profile_admin", false)
                )
            )
        } else null
        if (refresh.isNotBlank() && isExpiredOrNearExpiry(access)) {
            return runCatching { refreshSession(refresh) }.getOrElse { cached ?: run { clear(); null } }
        }
        if (cached != null) return cached
        return try {
            val user = requestUser(access)
            val profile = fetchProfile(access, userId, user)
            saveProfile(profile)
            AuthSession(access, refresh, profile)
        } catch (_: Exception) {
            if (refresh.isBlank()) {
                clear(); null
            } else {
                runCatching { refreshSession(refresh) }.getOrElse { clear(); null }
            }
        }
    }

    fun signUp(email: String, password: String, username: String, gender: String, phone: String): Result<String> = try {
        val body = JSONObject().apply {
            put("email", email.trim())
            put("password", password)
            put("data", JSONObject().apply {
                put("username", username.trim())
                put("gender", gender.lowercase())
                put("phone", phone.trim())
            })
        }
        val json = JSONObject(request("/auth/v1/signup?redirect_to=${encode(AUTH_REDIRECT_URL)}", "POST", body.toString(), "application/json").body)
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        val user = json.optJSONObject("user")
        if (access.isNotBlank() && user != null) {
            saveTokens(access, refresh, user.optString("id"))
            val profile = fetchProfile(access, user.optString("id"), user)
            saveProfile(profile)
            Result.success("Account created successfully. Welcome, ${profile.username}.")
        } else Result.success("Account created. Check your email to confirm your account.")
    } catch (e: Exception) { Result.failure(e) }

    fun signIn(email: String, password: String): Result<AuthSession> = try {
        val body = JSONObject().apply { put("email", email.trim()); put("password", password) }.toString()
        val json = JSONObject(request("/auth/v1/token?grant_type=password", "POST", body, "application/json").body)
        val access = json.optString("access_token").ifBlank { error("No access token returned") }
        val refresh = json.optString("refresh_token")
        val user = json.optJSONObject("user") ?: error("No user returned")
        val id = user.optString("id").ifBlank { error("No user id returned") }
        saveTokens(access, refresh, id)
        val profile = fetchProfile(access, id, user)
        saveProfile(profile)
        Result.success(AuthSession(access, refresh, profile))
    } catch (e: Exception) { Result.failure(e) }

    fun requestPasswordReset(email: String): Result<String> = try {
        request("/auth/v1/recover?redirect_to=${encode(PASSWORD_RESET_REDIRECT_URL)}", "POST", JSONObject().put("email", email.trim()).toString(), "application/json")
        Result.success("If an account exists for this email, a password reset link has been sent.")
    } catch (e: Exception) { Result.failure(e) }

    fun updatePassword(accessToken: String, newPassword: String): Result<String> = try {
        request("/auth/v1/user", "PUT", JSONObject().put("password", newPassword).toString(), "application/json", accessToken)
        Result.success("Password updated successfully.")
    } catch (e: Exception) { Result.failure(e) }

    fun signOut() {
        prefs.getString("access_token", null)?.let { runCatching { request("/auth/v1/logout", "POST", "{}", "application/json", it) } }
        clear()
    }

    private fun refreshSession(refreshToken: String): AuthSession {
        val json = JSONObject(request("/auth/v1/token?grant_type=refresh_token", "POST", JSONObject().put("refresh_token", refreshToken).toString(), "application/json").body)
        val access = json.optString("access_token").ifBlank { error("Refresh failed") }
        val refresh = json.optString("refresh_token").ifBlank { refreshToken }
        val user = json.optJSONObject("user") ?: requestUser(access)
        val id = user.optString("id").ifBlank { error("No user id returned") }
        saveTokens(access, refresh, id)
        val profile = fetchProfile(access, id, user)
        saveProfile(profile)
        return AuthSession(access, refresh, profile)
    }

    private fun isExpiredOrNearExpiry(token: String): Boolean {
        return try {
            val parts = token.split('.')
            if (parts.size < 2) return false
            val payload = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val exp = JSONObject(String(payload, Charsets.UTF_8)).optLong("exp", 0L)
            exp > 0L && exp <= System.currentTimeMillis() / 1000L + 60L
        } catch (_: Exception) { false }
    }

    private fun requestUser(accessToken: String) = JSONObject(request("/auth/v1/user", "GET", null, null, accessToken).body)

    private fun fetchProfile(accessToken: String, userId: String, user: JSONObject): AccountProfile {
        val a = org.json.JSONArray(request("/rest/v1/profiles?id=eq.${encode(userId)}&select=id,username,gender", "GET", null, null, accessToken).body)
        if (a.length() == 0) error("Profile is not ready yet. Please try again.")
        val row = a.getJSONObject(0)
        val isAdmin = request("/rest/v1/rpc/is_admin", "POST", "{}", "application/json", accessToken).body.trim().equals("true", ignoreCase = true)
        return AccountProfile(
            row.optString("id", userId),
            row.optString("username", "User"),
            row.optString("gender", "male"),
            user.optString("email").takeIf { it.isNotBlank() },
            user.optString("phone").takeIf { it.isNotBlank() } ?: row.optString("phone").takeIf { it.isNotBlank() },
            isAdmin
        )
    }

    private fun saveTokens(access: String, refresh: String, userId: String) {
        prefs.edit().putString("access_token", access).putString("refresh_token", refresh).putString("user_id", userId).apply()
    }

    private fun saveProfile(p: AccountProfile) {
        prefs.edit()
            .putString("profile_username", p.username)
            .putString("profile_gender", p.gender)
            .putBoolean("profile_admin", p.isAdmin)
            .apply {
                if (p.email != null) putString("profile_email", p.email) else remove("profile_email")
                if (p.phone != null) putString("profile_phone", p.phone) else remove("profile_phone")
            }
            .apply()
    }

    private fun clear() { prefs.edit().clear().apply() }
    private fun encode(v: String) = URLEncoder.encode(v, Charsets.UTF_8.name())
    private data class Response(val code: Int, val body: String)

    private fun request(path: String, method: String, body: String?, contentType: String?, accessToken: String? = null): Response {
        val c = URL(SUPABASE_URL + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            c.setRequestProperty("Accept", "application/json")
            if (!accessToken.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $accessToken")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType ?: "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (c.responseCode !in 200..299) {
                val msg = runCatching {
                    JSONObject(text).optString("msg")
                        .ifBlank { JSONObject(text).optString("message") }
                        .ifBlank { JSONObject(text).optString("error_description") }
                }.getOrDefault("")
                throw IllegalStateException(if (msg.isBlank()) "Request failed (${c.responseCode})" else msg)
            }
            return Response(c.responseCode, text)
        } finally { c.disconnect() }
    }
}
