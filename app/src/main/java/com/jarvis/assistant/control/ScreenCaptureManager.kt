package com.jarvis.assistant.control

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import com.jarvis.assistant.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

private const val TAG = "ScreenCapture"

/**
 * Captures frames of the device screen so a multimodal backend can see what
 * the user sees.
 *
 * The projection is set up **once per session and kept alive**, rather than
 * rebuilt per screenshot. That's not an optimisation, it's a correctness
 * requirement: since Android 14 the consent token returned by the system
 * dialog is single-use, so re-calling `getMediaProjection()` for every
 * capture worked exactly once and then failed — which in continuous
 * screen-watch mode meant re-prompting the user on every turn.
 *
 * A live projection means the system's screen-sharing indicator stays
 * visible for as long as the session is open. That's the honest signal,
 * and the reason [release] is called when screen watching is switched off,
 * when voice mode ends, and when the ViewModel is cleared.
 */
object ScreenCaptureManager {

    private var projectionResultCode: Int = 0
    private var projectionResultData: Intent? = null

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var frameWidth = 0
    private var frameHeight = 0

    private const val SERVICE_START_TIMEOUT_MS = 5_000L

    /** Serialises session setup and teardown against concurrent captures. */
    private val lock = Mutex()

    val hasConsent: Boolean get() = projectionResultData != null

    fun createConsentIntent(context: Context): Intent =
        context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()

    fun onConsentResult(resultCode: Int, data: Intent?) {
        projectionResultCode = resultCode
        projectionResultData = data
        AppLogger.i(TAG, if (data != null) "Screen capture consent granted" else "Screen capture consent denied")
    }

    /**
     * Grabs the current screen as a base64 JPEG, or null if consent is
     * missing or the session can't be established. Downscaled to at most
     * [maxDimension] on the long edge — a full-resolution phone screenshot
     * is expensive to upload and no more legible to a vision model.
     */
    suspend fun captureBase64Jpeg(
        context: Context,
        maxDimension: Int = 1280,
        quality: Int = 70,
    ): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (projectionResultData == null) {
                AppLogger.w(TAG, "captureBase64Jpeg without consent")
                return@withLock null
            }
            if (!ensureSession(context)) return@withLock null

            val bitmap = acquireFrame() ?: run {
                AppLogger.w(TAG, "No frame available from the virtual display")
                return@withLock null
            }
            encode(bitmap, maxDimension, quality)
        }
    }

    /** Builds the projection + mirrored display if not already running. */
    @SuppressLint("WrongConstant")
    private suspend fun ensureSession(context: Context): Boolean {
        if (projection != null && reader != null && virtualDisplay != null) return true
        val data = projectionResultData ?: return false

        // Android 14+ rejects createVirtualDisplay unless a mediaProjection
        // foreground service is already running, so start it and wait for it
        // to actually be foregrounded before going near MediaProjection.
        val appContext = context.applicationContext
        runCatching {
            appContext.startForegroundService(Intent(appContext, ScreenCaptureService::class.java))
        }.onFailure {
            AppLogger.e(TAG, "Couldn't start screen capture service", it)
            return false
        }
        // Bounded: if the service never reaches the foreground (OEM
        // restrictions, background-start limits) an unbounded await would
        // wedge the turn forever instead of failing visibly.
        if (withTimeoutOrNull(SERVICE_START_TIMEOUT_MS) { ScreenCaptureService.awaitStarted() } == null) {
            AppLogger.e(TAG, "Screen capture service didn't start in time")
            teardown(appContext)
            return false
        }

        val thread = HandlerThread("JarvisScreenCapture").apply { start() }
        handlerThread = thread
        val captureHandler = Handler(thread.looper)
        handler = captureHandler

        val (width, height, density) = screenMetrics(appContext)
        frameWidth = width
        frameHeight = height

        val manager = appContext.getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = runCatching {
            manager.getMediaProjection(projectionResultCode, data)
        }.getOrNull()
        if (mediaProjection == null) {
            AppLogger.e(TAG, "getMediaProjection failed — consent token spent or revoked")
            projectionResultData = null
            teardown(appContext)
            return false
        }

        // Required before createVirtualDisplay on Android 14+; also how we
        // learn the user revoked sharing from the system UI.
        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    AppLogger.i(TAG, "Screen projection stopped by the system or user")
                    projectionResultData = null
                    teardown(appContext)
                }
            },
            captureHandler,
        )
        projection = mediaProjection

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader

        virtualDisplay = runCatching {
            mediaProjection.createVirtualDisplay(
                "JarvisScreenCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, captureHandler,
            )
        }.onFailure {
            AppLogger.e(TAG, "createVirtualDisplay failed", it)
        }.getOrNull()

        if (virtualDisplay == null) {
            teardown(appContext)
            return false
        }
        AppLogger.i(TAG, "Screen capture session ready (${width}x$height)")
        return true
    }

    /** Polls briefly — the mirror needs a moment to produce its first frame. */
    private suspend fun acquireFrame(): Bitmap? {
        val imageReader = reader ?: return null
        repeat(25) {
            val image = runCatching { imageReader.acquireLatestImage() }.getOrNull()
            if (image != null) {
                val bitmap = runCatching {
                    val plane = image.planes[0]
                    val rowPadding = plane.rowStride - plane.pixelStride * frameWidth
                    val padded = Bitmap.createBitmap(
                        frameWidth + rowPadding / plane.pixelStride,
                        frameHeight,
                        Bitmap.Config.ARGB_8888,
                    )
                    padded.copyPixelsFromBuffer(plane.buffer)
                    Bitmap.createBitmap(padded, 0, 0, frameWidth, frameHeight)
                        .also { if (it !== padded) padded.recycle() }
                }.getOrNull()
                runCatching { image.close() }
                if (bitmap != null) return bitmap
            }
            delay(80)
        }
        return null
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

    /** Tears the session down and drops the screen-sharing indicator. */
    fun release(context: Context) {
        teardown(context.applicationContext)
        projectionResultData = null
    }

    private fun teardown(appContext: Context) {
        runCatching { virtualDisplay?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        runCatching { handlerThread?.quitSafely() }
        virtualDisplay = null
        reader = null
        projection = null
        handlerThread = null
        handler = null
        runCatching { appContext.stopService(Intent(appContext, ScreenCaptureService::class.java)) }
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
