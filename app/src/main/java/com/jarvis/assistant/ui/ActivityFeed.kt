package com.jarvis.assistant.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.JarvisWarn
import com.jarvis.assistant.util.AppLogger
import com.jarvis.assistant.util.LogLevel

/**
 * The functional half of "coding in the background": a one-line live ticker
 * of the latest [AppLogger] entry, sitting above the chat input like a
 * console readout. Tap to expand into the last several entries; the full
 * history stays on Settings > Diagnostics > View logs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityFeed(accentColor: Color, modifier: Modifier = Modifier) {
    val entries by AppLogger.entries.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    if (entries.isEmpty()) return

    Surface(
        onClick = { expanded = !expanded },
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).animateContentSize()) {
            val shown = if (expanded) entries.takeLast(8) else entries.takeLast(1)
            shown.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "●",
                        color = when (entry.level) {
                            LogLevel.INFO -> accentColor.copy(alpha = 0.8f)
                            LogLevel.WARN -> JarvisWarn
                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "[${entry.tag}] ${entry.message}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
