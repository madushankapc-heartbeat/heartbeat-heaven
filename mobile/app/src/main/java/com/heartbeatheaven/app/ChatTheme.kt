package com.heartbeatheaven.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal data class ChatThemeOption(
    val id: String,
    val name: String,
    val background: Color,
    val myBubble: Color,
    val otherBubble: Color
)

internal val chatThemeOptions = listOf(
    ChatThemeOption("classic", "Classic", Color(0xFFF5F5F5), Color(0xFFDDF8C6), Color(0xFFFFFFFF)),
    ChatThemeOption("ocean", "Ocean", Color(0xFFEAF7FB), Color(0xFFCDEFF5), Color(0xFFFFFFFF)),
    ChatThemeOption("lavender", "Lavender", Color(0xFFF4F0FA), Color(0xFFE8DDF8), Color(0xFFFFFFFF)),
    ChatThemeOption("rose", "Rose", Color(0xFFFDF0F3), Color(0xFFF7D9E1), Color(0xFFFFFFFF)),
    ChatThemeOption("mint", "Mint", Color(0xFFEDF8F1), Color(0xFFD5F0DF), Color(0xFFFFFFFF)),
    ChatThemeOption("sunset", "Sunset", Color(0xFFFFF4E8), Color(0xFFFFE0C2), Color(0xFFFFFFFF)),
    ChatThemeOption("sky", "Sky", Color(0xFFEEF5FF), Color(0xFFD8E9FF), Color(0xFFFFFFFF)),
    ChatThemeOption("sand", "Sand", Color(0xFFF8F3E8), Color(0xFFEDE1C5), Color(0xFFFFFFFF)),
    ChatThemeOption("midnight", "Midnight", Color(0xFF12161C), Color(0xFF253A52), Color(0xFF242A31)),
    ChatThemeOption("obsidian", "Obsidian", Color(0xFF0F1012), Color(0xFF24262A), Color(0xFF1B1D20)),
    ChatThemeOption("dark_lavender", "Dark Lavender", Color(0xFF171421), Color(0xFF3A2F50), Color(0xFF282331)),
    ChatThemeOption("deep_ocean", "Deep Ocean", Color(0xFF0D1820), Color(0xFF163A4A), Color(0xFF1A252C)),
    ChatThemeOption("dark_rose", "Dark Rose", Color(0xFF1D1218), Color(0xFF542B3B), Color(0xFF302027)),
    ChatThemeOption("emerald_night", "Emerald Night", Color(0xFF101A16), Color(0xFF24483A), Color(0xFF202B26)),
    ChatThemeOption("night_sky", "Night Sky", Color(0xFF101525), Color(0xFF29365F), Color(0xFF20263A)),
    ChatThemeOption("dark_romance", "Dark Romance", Color(0xFF171012), Color(0xFF4A202A), Color(0xFF2A1B20))
)

internal fun chatThemeOption(id: String): ChatThemeOption =
    chatThemeOptions.firstOrNull { it.id == id } ?: chatThemeOptions.first()

internal class ChatThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("heartbeat_chat_themes", Context.MODE_PRIVATE)

    fun themeId(chatId: String): String =
        prefs.getString("theme_$chatId", "classic").orEmpty().ifBlank { "classic" }

    fun setTheme(chatId: String, themeId: String) {
        prefs.edit().putString("theme_$chatId", themeId).apply()
    }

    fun wallpaperUri(chatId: String): String =
        prefs.getString("wallpaper_$chatId", "").orEmpty()

    fun setWallpaper(chatId: String, uri: String?) {
        prefs.edit().apply {
            if (uri.isNullOrBlank()) remove("wallpaper_$chatId")
            else putString("wallpaper_$chatId", uri)
        }.apply()
    }
}

@Composable
internal fun ChatThemePickerDialog(
    selectedThemeId: String,
    hasWallpaper: Boolean,
    onThemeSelected: (ChatThemeOption) -> Unit,
    onPickWallpaper: () -> Unit,
    onRemoveWallpaper: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat Theme") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This theme is saved only for this chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                chatThemeOptions.chunked(2).forEach { rowOptions ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowOptions.forEach { option ->
                            val selected = option.id == selectedThemeId
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onThemeSelected(option) },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = option.background)
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Spacer(
                                            Modifier.size(18.dp)
                                                .background(option.otherBubble, RoundedCornerShape(6.dp))
                                        )
                                        Spacer(Modifier.size(5.dp))
                                        Spacer(
                                            Modifier.size(18.dp)
                                                .background(option.myBubble, RoundedCornerShape(6.dp))
                                        )
                                        if (selected) {
                                            Spacer(Modifier.size(5.dp))
                                            Icon(Icons.Default.Check, "Selected", Modifier.size(16.dp))
                                        }
                                    }
                                    Spacer(Modifier.height(5.dp))
                                    Text(option.name, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        if (rowOptions.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                TextButton(onClick = onPickWallpaper, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Wallpaper, null, Modifier.size(18.dp))
                    Text("  Choose wallpaper from gallery")
                }
                if (hasWallpaper) {
                    TextButton(onClick = onRemoveWallpaper, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Image, null, Modifier.size(18.dp))
                        Text("  Remove wallpaper")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}
