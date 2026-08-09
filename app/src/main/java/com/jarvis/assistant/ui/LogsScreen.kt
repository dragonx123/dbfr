package com.jarvis.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.JarvisWarn
import com.jarvis.assistant.util.AppLogger
import com.jarvis.assistant.util.LogEntry
import com.jarvis.assistant.util.LogLevel
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Reverse-chronological view of [com.jarvis.assistant.util.AppLogger] —
 * backend connects, tool calls, generation errors/stalls, speech/TTS state.
 * There's no adb/logcat access in normal use of this app, so this + the
 * crash dialog (see `CrashReportDialog`) are the only windows into what
 * actually happened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    entries: List<LogEntry>,
    onShare: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Logs") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { onShare(AppLogger.formatAll()) }, enabled = entries.isNotEmpty()) {
                            Icon(Icons.Filled.Share, contentDescription = "Share logs")
                        }
                        IconButton(onClick = onClear, enabled = entries.isNotEmpty()) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Clear logs")
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
            }
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No log entries yet. Backend connections, tool calls, and errors will show up here as they happen.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Newest first — that's almost always what you want when checking
                // "what just happened" right after reproducing something. No custom
                // key: two entries can share a timestamp+message (e.g. a fast loop
                // logging the same line twice in one millisecond), which would
                // collide and crash if used as a LazyColumn item key.
                items(entries.asReversed()) { entry ->
                    LogRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val levelColor = when (entry.level) {
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.WARN -> JarvisWarn
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = TIME_FORMAT.format(entry.timestamp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Column {
            Text(
                text = "[${entry.tag}] ${entry.message}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = levelColor,
            )
        }
    }
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
