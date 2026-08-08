package com.jarvis.assistant.control

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import com.jarvis.assistant.util.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

private const val TAG = "ScreenCapture"

/**
 * Captures single frames of whatever is on the device screen, so a
 * multimodal backend can literally see what the user sees.
 *
 * MediaProjection requires an explicit per-session consent dialog; the
 * granted result Intent is held here for the life of the session so
 * repeated captures (including the optional continuous mode in voice
 * conversations) don't re-prompt every time.
 *
 * Only the cloud backends can use the result — the on-device LiteRT models
 * this app loads are text-only. Text-only setups get the accessibility
 * service's [JarvisAccessibilityService.readScreenText] instead, which is
 * often more useful anyway since it returns real labels rather than pixels.
 */
object ScreenCaptureManager {

    private var projectionResultCode: Int = 0
    private var projectionResultData: Intent? = null
    private var projection: MediaProjection? = null

    val hasConsent: Boolean get() = projectionResultData != null

    fun createConsentIntent(context: Context): Intent {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        return manager.createScreenCaptureIntent()
    }

    fun onConsentResult(resultCode: Int, data: Intent?) {
        projectionResultCode = resultCode
        projectionResultData = data
        AppLogger.i(TAG, if (data != null) "Screen capture consent granted" else "Screen capture consent denied")
    }

    fun release() {
        projection?.stop()
        projection = null
        projectionResultData = null
    }

    /**
     * Grabs one frame and returns it as a base64 JPEG, or null if consent is
     * missing or the capture times out. Downscaled to at most [maxDimension]
     * on the long edge — full-resolution phone screenshots are needlessly
     * expensive to upload and no more legible to a vision model.
     */
    @SuppressLint("WrongConstant")
    suspend fun captureBase64Jpeg(context: Context, maxDimension: Int = 1280, quality: Int = 70): String? {
        val data = projectionResultData ?: run {
            AppLogger.w(TAG, "captureBase64Jpeg without consent")
            return null
        }

        val bitmap = suspendCancellableCoroutine<Bitmap?> { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val metrics = screenMetrics(context)
            val width = metrics.first
            val height = metrics.second
            val density = metrics.third

            val manager = context.getSystemService(MediaProjectionManager::class.java)
            // A fresh MediaProjection per capture: on Android 14+ a projection
            // can only be started once per consent token in some flows, and
            // holding one open indefinitely shows a persistent system warning.
            val mediaProjection = runCatching {
                manager.getMediaProjection(projectionResultCode, data)
            }.getOrNull()
            if (mediaProjection == null) {
                AppLogger.e(TAG, "Couldn't obtain MediaProjection — consent may have expired")
                projectionResultData = null
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            projection = mediaProjection

            val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            var virtualDisplay: VirtualDisplay? = null
            var settled = false

            // Android 14+ requires a registered callback before createVirtualDisplay.
            mediaProjection.registerCallback(object : MediaProjection.Callback() {}, handler)

            fun finish(result: Bitmap?) {
                if (settled) return
                settled = true
                runCatching { virtualDisplay?.release() }
                runCatching { reader.close() }
                runCatching { mediaProjection.stop() }
                projection = null
                continuation.resume(result)
            }

            reader.setOnImageAvailableListener({ r ->
                val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                val result = runCatching {
                    val plane = image.planes[0]
                    val rowPadding = plane.rowStride - plane.pixelStride * width
                    val full = Bitmap.createBitmap(
                        width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888
                    )
                    full.copyPixelsFromBuffer(plane.buffer)
                    Bitmap.createBitmap(full, 0, 0, width, height)
                }.getOrNull()
                runCatching { image.close() }
                finish(result)
            }, handler)

            virtualDisplay = runCatching {
                mediaProjection.createVirtualDisplay(
                    "JarvisScreenCapture", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, handler,
                )
            }.getOrNull()

            if (virtualDisplay == null) {
                AppLogger.e(TAG, "createVirtualDisplay failed")
                finish(null)
                return@suspendCancellableCoroutine
            }

            // Don't hang the turn forever if no frame ever arrives.
            handler.postDelayed({
                if (!settled) AppLogger.w(TAG, "Screen capture timed out")
                finish(null)
            }, 2500)

            continuation.invokeOnCancellation { handler.post { finish(null) } }
        } ?: return null

        return encode(bitmap, maxDimension, quality)
    }

    private fun encode(bitmap: Bitmap, maxDimension: Int, quality: Int): String {
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > maxDimension) {
            val ratio = maxDimension.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true
            )
        } else bitmap

        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        AppLogger.i(TAG, "Captured screen (${stream.size() / 1024} KB JPEG)")
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    @Suppress("DEPRECATION")
    private fun screenMetrics(context: Context): Triple<Int, Int, Int> {
        val windowManager = context.getSystemService(WindowManager::class.java)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), context.resources.displayMetrics.densityDpi)
        } else {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }
}
