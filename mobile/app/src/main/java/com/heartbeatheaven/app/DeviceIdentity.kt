package com.heartbeatheaven.app

import android.content.Context
import android.provider.Settings
import java.util.UUID

internal object DeviceIdentity {
    private const val PREFS = "heartbeat_device_identity"
    private const val KEY_FALLBACK = "fallback_device_id"

    fun get(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.trim()
        if (!androidId.isNullOrBlank()) return "android-id:$androidId"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_FALLBACK, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_FALLBACK, it).apply()
        }
    }
}
