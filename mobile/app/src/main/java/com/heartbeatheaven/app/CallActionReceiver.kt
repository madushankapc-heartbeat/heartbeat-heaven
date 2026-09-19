package com.heartbeatheaven.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != CallNotificationManager.ACTION_DECLINE) return
        val callId = intent.getStringExtra(CallNotificationManager.EXTRA_CALL_ID) ?: return
        val pending = goAsync()
        Thread {
            try {
                val auth = AuthApi(context.applicationContext)
                val api = CallApi(context.applicationContext, auth)
                runCatching { api.updateStatusBlocking(callId, "declined") }
            } finally {
                CallNotificationManager.cancelIncoming(context.applicationContext)
                pending.finish()
            }
        }.start()
    }
}
