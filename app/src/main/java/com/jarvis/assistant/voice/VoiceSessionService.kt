package com.jarvis.assistant.voice

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.util.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val TAG = "VoiceSession"

/**
 * Keeps a full-screen voice conversation alive while the app isn't in the
 * foreground — screen off, or another app on top — the way Gemini's and
 * ChatGPT's voice modes do.
 *
 * It deliberately does NOT own the conversation loop; that stays in
 * `ChatViewModel`, which survives as long as the process does. What this
 * service provides is the two things a backgrounded app can't get on its
 * own: an ongoing foreground-service notification so the process isn't
 * killed, and the `microphone` foreground-service type, without which
 * Android 12+ silently cuts off `SpeechRecognizer` the moment the app
 * stops being visible.
 *
 * The notification's Mute/End buttons come back through
 * [VoiceSessionEvents] so the running session reacts to them.
 */
class VoiceSessionService : Service() {

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "Background voice session started")
        startForegroundCompat("Listening")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_MUTE -> VoiceSessionEvents.emit(VoiceSessionCommand.TOGGLE_MUTE)
            ACTION_END -> VoiceSessionEvents.emit(VoiceSessionCommand.END)
            ACTION_UPDATE_STATE -> startForegroundCompat(
                intent.getStringExtra(EXTRA_STATE_LABEL) ?: "Listening",
                intent.getBooleanExtra(EXTRA_MUTED, false),
            )
        }
        return START_STICKY
    }

    private fun startForegroundCompat(stateLabel: String, muted: Boolean = false) {
        val notification = buildNotification(stateLabel, muted)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(stateLabel: String, muted: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(requestCode: Int, action: String, title: String): NotificationCompat.Action {
            val pending = PendingIntent.getService(
                this, requestCode,
                Intent(this, VoiceSessionService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Action.Builder(0, title, pending).build()
        }

        return NotificationCompat.Builder(this, JarvisApplication.VOICE_SESSION_CHANNEL_ID)
            .setContentTitle("Voice conversation active")
            .setContentText(if (muted) "Muted" else stateLabel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .addAction(action(1, ACTION_TOGGLE_MUTE, if (muted) "Unmute" else "Mute"))
            .addAction(action(2, ACTION_END, "End"))
            .build()
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "Background voice session stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 43
        const val ACTION_TOGGLE_MUTE = "com.jarvis.assistant.voice.TOGGLE_MUTE"
        const val ACTION_END = "com.jarvis.assistant.voice.END"
        const val ACTION_UPDATE_STATE = "com.jarvis.assistant.voice.UPDATE_STATE"
        const val EXTRA_STATE_LABEL = "state_label"
        const val EXTRA_MUTED = "muted"
    }
}

enum class VoiceSessionCommand { TOGGLE_MUTE, END }

/** Bridge from the voice-session notification's buttons back to the live session. */
object VoiceSessionEvents {
    private val _events = MutableSharedFlow<VoiceSessionCommand>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    fun emit(command: VoiceSessionCommand) {
        _events.tryEmit(command)
    }
}
