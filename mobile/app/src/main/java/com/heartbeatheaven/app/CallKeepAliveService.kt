package com.heartbeatheaven.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

internal class CallKeepAliveService : Service() {
    companion object {
        private const val CHANNEL_ID = "active_call_v1"
        private const val NOTIFICATION_ID = 7402
        const val ACTION_START = "com.heartbeatheaven.app.START_CALL_KEEP_ALIVE"
        const val ACTION_STOP = "com.heartbeatheaven.app.STOP_CALL_KEEP_ALIVE"

        fun start(context: android.content.Context) {
            val intent = Intent(context, CallKeepAliveService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, CallKeepAliveService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.heartbeat_heaven_logo)
            .setContentTitle("HeartBeat Heaven call")
            .setContentText("Call in progress")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Active calls",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps an active HeartBeat Heaven call alive while the screen is off"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }
}
