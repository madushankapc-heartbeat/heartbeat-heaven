package com.heartbeatheaven.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class HeartbeatHeavenFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val CHANNEL_ID = "chat_messages_v2"
        private const val CHANNEL_NAME = "Chat messages"
        private const val NOTIFICATION_ID = 4201

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    manager.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            CHANNEL_NAME,
                            NotificationManager.IMPORTANCE_HIGH
                        ).apply {
                            description = "New HEARTBEAT HEAVEN chat messages"
                        }
                    )
                }
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"]?.trim().takeUnless { it.isNullOrEmpty() } ?: "New message"
        val body = data["body"]?.trim().takeUnless { it.isNullOrEmpty() } ?: return
        val senderId = data["sender_id"]?.trim().orEmpty()
        val createdAt = data["created_at"]?.trim().orEmpty()

        createChannel(applicationContext)

        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (senderId.isNotEmpty()) putExtra("open_chat_sender_id", senderId)
            if (createdAt.isNotEmpty()) putExtra("message_created_at", createdAt)
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.heartbeat_heaven_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(NOTIFICATION_ID, notification)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Token registration with the HEARTBEAT HEAVEN backend is added separately.
    }
}
