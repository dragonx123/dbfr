package com.jarvis.assistant.control

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
import kotlinx.coroutines.CompletableDeferred

private const val TAG = "ScreenCaptureSvc"

/**
 * Exists solely to satisfy Android 14+, which refuses
 * `MediaProjection.createVirtualDisplay()` unless a foreground service
 * typed `mediaProjection` is already running. Without this the capture
 * throws `SecurityException` on any modern device — the manifest declared
 * the permission but no such service, so screenshots silently never worked
 * on API 34+.
 *
 * It holds no capture logic itself; [ScreenCaptureManager] waits on
 * [awaitStarted] before touching MediaProjection.
 */
class ScreenCaptureService : Service() {

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        AppLogger.i(TAG, "Screen capture service foregrounded")
        started.complete(Unit)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, JarvisApplication.SCREEN_CAPTURE_CHANNEL_ID)
            .setContentTitle("Jarvis can see your screen")
            .setContentText("Screen sharing is active.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "Screen capture service stopped")
        resetStarted()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 44

        @Volatile
        private var started = CompletableDeferred<Unit>()

        /**
         * Suspends until the service is actually foregrounded.
         * `startForegroundService` is asynchronous, and calling
         * `getMediaProjection()` before the service is up is exactly the
         * race Android 14 rejects.
         */
        suspend fun awaitStarted() = started.await()

        private fun resetStarted() {
            started = CompletableDeferred()
        }
    }
}
