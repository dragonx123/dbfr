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
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                WAKE_WORD_CHANNEL_ID,
                "Jarvis listening",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while Jarvis is listening for the wake word in the background."
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                VOICE_SESSION_CHANNEL_ID,
                "Voice conversation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while a voice conversation is running in the background."
            }
        )
    }

    companion object {
        const val WAKE_WORD_CHANNEL_ID = "jarvis_wake_word"
        const val VOICE_SESSION_CHANNEL_ID = "jarvis_voice_session"
    }
}
