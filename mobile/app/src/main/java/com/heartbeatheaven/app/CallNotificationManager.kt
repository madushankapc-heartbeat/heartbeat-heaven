package com.heartbeatheaven.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.NotificationManagerCompat

internal object CallNotificationManager {
    private const val CHANNEL_ID = "incoming_calls_v4"
    private const val NOTIFICATION_ID = 7401

    @Volatile
    private var incomingRingtone: Ringtone? = null
    const val ACTION_DECLINE = "com.heartbeatheaven.app.ACTION_DECLINE_CALL"
    const val EXTRA_CALL_ID = "call_id"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        // The notification channel is intentionally silent because the call has
        // its own explicit ringtone. This prevents duplicate sounds.
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Incoming calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming HeartBeat Heaven voice and video calls"
            setSound(null, audio)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 300, 500)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun startIncomingRingtone(context: Context) {
        stopIncomingRingtone()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri) ?: return

        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        ringtone.audioAttributes = audio
        @Suppress("DEPRECATION")
        ringtone.streamType = AudioManager.STREAM_RING
        if (Build.VERSION.SDK_INT >= 28) ringtone.isLooping = true

        incomingRingtone = ringtone
        runCatching { ringtone.play() }
    }

    private fun stopIncomingRingtone() {
        incomingRingtone?.let { runCatching { it.stop() } }
        incomingRingtone = null
    }

    fun showIncoming(context: Context, callId: String, callType: String, fullScreen: Boolean = true) {
        ensureChannel(context)

        // Start the actual phone ringtone immediately when the incoming call
        // is detected. The notification remains responsible for vibration,
        // full-screen presentation, and Answer/Decline actions.
        startIncomingRingtone(context)

        val openIntent = Intent(context, CallActivity::class.java).apply {
            putExtra(EXTRA_CALL_ID, callId)
            putExtra("call_type", callType)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val answerIntent = Intent(context, CallActivity::class.java).apply {
            putExtra(EXTRA_CALL_ID, callId)
            putExtra("call_type", callType)
            putExtra("answer_now", true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = ACTION_DECLINE
            putExtra(EXTRA_CALL_ID, callId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val content = PendingIntent.getActivity(context, callId.hashCode(), openIntent, flags)
        val answer = PendingIntent.getActivity(context, callId.hashCode() + 1, answerIntent, flags)
        val decline = PendingIntent.getBroadcast(context, callId.hashCode() + 2, declineIntent, flags)

        val typeLabel = if (callType == "video") "video" else "voice"
        val caller = Person.Builder().setName("HeartBeat Heaven call").setImportant(true).build()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.heartbeat_heaven_logo)
            .setContentTitle("Incoming $typeLabel call")
            .setContentText("Someone is calling you")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(content)
            .setFullScreenIntent(content, fullScreen)

        if (Build.VERSION.SDK_INT >= 31) {
            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
        } else {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", decline)
                .addAction(android.R.drawable.ic_menu_call, "Answer", answer)
        }

        if (Build.VERSION.SDK_INT < 33 || NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }
    }

    fun cancelIncoming(context: Context) {
        stopIncomingRingtone()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
