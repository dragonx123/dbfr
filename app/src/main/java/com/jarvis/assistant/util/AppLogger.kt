package com.jarvis.assistant.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Locale

enum class LogLevel { INFO, WARN, ERROR }

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/**
 * A rolling in-memory log of what Jarvis is doing under the hood — backend
 * connects, tool calls, generation errors/stalls, speech/TTS state — shown
 * on the Settings > Logs screen. There's no adb/logcat access in normal use
 * of this app (see `CrashReporter` for the equivalent when it actually
 * crashes instead of just misbehaving), so this is the only window into
 * what happened without one.
 *
 * Deliberately in-memory only — resets on process death. This is for
 * live/recent troubleshooting while the app is running, a different job
 * than the crash report surviving a kill.
 */
object AppLogger {

    private const val MAX_ENTRIES = 500

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun i(tag: String, message: String) = add(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = add(LogLevel.WARN, tag, message)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        add(LogLevel.ERROR, tag, if (throwable != null) "$message: ${throwable.message}" else message)

    fun clear() {
        _entries.value = emptyList()
    }

    /** Plain-text dump of every entry, oldest first — for the Logs screen's copy/share actions. */
    fun formatAll(): String {
        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return _entries.value.joinToString("\n") { entry ->
            "${format.format(entry.timestamp)} ${entry.level} [${entry.tag}] ${entry.message}"
        }
    }

    // Log calls can come from many threads at once (OkHttp callbacks, TTS
    // utterance callbacks, tool calls, the main thread) — synchronized so a
    // read-modify-write race never silently drops an entry.
    @Synchronized
    private fun add(level: LogLevel, tag: String, message: String) {
        _entries.value = (_entries.value + LogEntry(System.currentTimeMillis(), level, tag, message))
            .takeLast(MAX_ENTRIES)
    }
}
