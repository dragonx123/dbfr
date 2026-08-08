package com.jarvis.assistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Shown once, on the launch after a crash (see `util/CrashReporter.kt` and
 * `MainActivity`) — there's no adb/logcat access in normal use of this app,
 * so this is how a stack trace actually gets out of the phone: copy or
 * share it, then send it along.
 */
@Composable
fun CrashReportDialog(
    crashText: String,
    onShare: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jarvis crashed last time") },
        text = {
            Box(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = crashText,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(crashText))
                onDismiss()
            }) {
                Text("Copy")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onShare(crashText) }) {
                    Text("Share")
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        },
    )
}
