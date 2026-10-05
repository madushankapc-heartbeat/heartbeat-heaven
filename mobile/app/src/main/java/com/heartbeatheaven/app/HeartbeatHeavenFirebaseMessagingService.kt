package com.heartbeatheaven.app

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class HeartbeatHeavenFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        GlobalChatManager.handlePushMessage(applicationContext, message.data)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Token registration with the HEARTBEAT HEAVEN backend is handled by GlobalChatManager.
    }
}
