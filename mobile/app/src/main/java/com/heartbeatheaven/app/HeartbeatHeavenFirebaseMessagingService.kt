package com.heartbeatheaven.app

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
        GlobalChatManager.handlePushMessage(applicationContext, message.data)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Token registration with the HEARTBEAT HEAVEN backend is added separately.
    }
}
