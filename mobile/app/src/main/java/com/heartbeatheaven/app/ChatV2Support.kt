package com.heartbeatheaven.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.OffsetDateTime
import java.time.LocalDateTime
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

    private fun parse(iso: String): Instant? = runCatching {
        Instant.parse(iso)
    }.getOrElse {
        runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrElse {
            runCatching { LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()
        }
    }

    fun time(iso: String): String = parse(iso)?.atZone(ZoneId.systemDefault())?.format(timeFormatter).orEmpty()

    fun dateLabel(iso: String): String = runCatching {
        val local = parse(iso)?.atZone(ZoneId.systemDefault()) ?: return@runCatching ""
        val today = java.time.LocalDate.now()
        when (local.toLocalDate()) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> local.format(dateFormatter)
        }
    }.getOrDefault("")
}

// Chat v2 integration checkpoint: keep shared models isolated until each UI step builds cleanly.
