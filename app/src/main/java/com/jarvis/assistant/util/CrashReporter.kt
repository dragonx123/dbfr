package com.jarvis.assistant.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Installs a global uncaught-exception handler that writes the full stack
 * trace to app-private storage before the default handler kills the
 * process. There's no adb/logcat access in most of this app's real-world
 * use, so this is how a crash gets inspected after the fact — see
 * `MainActivity`, which checks [readLastCrash] on the next launch and shows
 * it with copy/share buttons.
 */
object CrashReporter {

    private const val CRASH_FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashLog(appContext, thread, throwable) }
            // Always hand off to the platform's default handler afterwards so the
            // normal "app has stopped" behavior / process death still happens.
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("Jarvis crash report")
            appendLine("Time: $timestamp")
            appendLine("Thread: ${thread.name}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            append(stackTrace)
        }
        crashLogFile(context).writeText(text)
    }

    private fun crashLogFile(context: Context): File = File(context.filesDir, CRASH_FILE_NAME)

    /** The most recent crash log's full text, if one exists, without clearing it. */
    fun readLastCrash(context: Context): String? {
        val file = crashLogFile(context)
        return if (file.exists()) runCatching { file.readText() }.getOrNull() else null
    }

    fun clearLastCrash(context: Context) {
        crashLogFile(context).delete()
    }
}
