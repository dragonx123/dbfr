package com.jarvis.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.jarvis.assistant.util.CrashReporter

class JarvisApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Installed first, before anything else gets a chance to crash.
        CrashReporter.install(this)
        createWakeWordNotificationChannel()
    }

    private fun createWakeWordNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                WAKE_WORD_CHANNEL_ID,
                "Jarvis listening",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while Jarvis is listening for the wake word in the background."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val WAKE_WORD_CHANNEL_ID = "jarvis_wake_word"
    }
}
