package com.jarvis.assistant.voice

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Best-effort "wake word" listener: repeatedly runs short speech-recognition
 * passes in the background and, whenever a transcript contains "jarvis",
 * publishes whatever followed the word to [WakeWordEvents] so the UI can act
 * on it. This is a simple restart-loop approach built on the stock
 * [android.speech.SpeechRecognizer] — not a true low-power hotword detector
 * — so expect a battery cost while the service runs, and treat it as
 * opt-in (see the mic-with-waves toggle in the app's top bar).
 */
class WakeWordService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var speechToText: SpeechToText
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        speechToText = SpeechToText(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            listenLoop()
        }
        return START_STICKY
    }

    private fun listenLoop() {
        if (!isRunning) return
        speechToText.startListening(
            onFinalResult = { transcript ->
                handleTranscript(transcript)
                mainHandler.postDelayed({ listenLoop() }, RESTART_DELAY_MS)
            },
            onError = {
                // No speech / timeout / busy — just try again shortly.
                mainHandler.postDelayed({ listenLoop() }, RESTART_DELAY_MS)
            },
        )
    }

    private fun handleTranscript(transcript: String) {
        val lower = transcript.lowercase()
        val wakeIndex = WAKE_WORDS.firstNotNullOfOrNull { word ->
            lower.indexOf(word).takeIf { it >= 0 }?.let { it to word }
        } ?: return

        val (index, word) = wakeIndex
        val command = transcript.substring(index + word.length).trim(' ', ',', '.', '!', '?')
        WakeWordEvents.emit(command)
    }

    private fun buildNotification(): Notification {
        val openApp = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, JarvisApplication.WAKE_WORD_CHANNEL_ID)
            .setContentTitle("Jarvis is listening")
            .setContentText("Say \"Jarvis\" followed by a command.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        speechToText.stopListening()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val RESTART_DELAY_MS = 400L
        private val WAKE_WORDS = listOf("jarvis")
    }
}

/** Fire-and-forget bridge from [WakeWordService] to whoever's listening in the UI layer. */
object WakeWordEvents {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    fun emit(command: String) {
        _events.tryEmit(command)
    }
}
