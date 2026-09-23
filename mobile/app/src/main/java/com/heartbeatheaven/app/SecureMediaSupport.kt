package com.heartbeatheaven.app

import org.json.JSONObject
import java.net.URLEncoder

internal data class SecureMediaLink(val url: String, val expiresAtMs: Long)

internal object SecureMediaSupport {
    fun parse(response: String): SecureMediaLink {
        val o = JSONObject(response)
        val url = o.optString("url").trim()
        val expiresAt = o.optString("expires_at").trim()
        if (url.isBlank() || expiresAt.isBlank()) error("Could not create secure media access.")
        val millis = runCatching { java.time.Instant.parse(expiresAt).toEpochMilli() }.getOrElse { 0L }
        if (millis <= 0L) error("Invalid secure media expiry.")
        return SecureMediaLink(url, millis)
    }
}
