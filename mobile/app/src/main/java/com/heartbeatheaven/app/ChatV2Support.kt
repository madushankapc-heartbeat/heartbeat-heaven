package com.heartbeatheaven.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/** Shared presentation models/helpers for the Chat v2 UI. */
internal enum class MessageDeliveryState {
    SENT,
    DELIVERED,
    SEEN
}

internal data class ChatUiMessage(
    val id: String,
    val senderId: String,
    val body: String,
    val createdAt: String,
    val deliveryState: MessageDeliveryState = MessageDeliveryState.SENT,
    val replyToId: String? = null,
    val edited: Boolean = false,
    val deleted: Boolean = false
)

internal object ChatTimeFormatter {
    private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

    fun time(iso: String): String = runCatching {
        Instant.parse(iso).atZone(ZoneId.systemDefault()).format(timeFormatter)
    }.getOrElse { "" }

    fun dateLabel(iso: String): String = runCatching {
        val local = Instant.parse(iso).atZone(ZoneId.systemDefault())
        val today = java.time.LocalDate.now()
        when (local.toLocalDate()) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> local.format(dateFormatter)
        }
    }.getOrElse { "" }
}
