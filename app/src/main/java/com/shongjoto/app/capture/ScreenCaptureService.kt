package com.shongjoto.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.shongjoto.app.classifier.ExplicitContentClassifier
import com.shongjoto.app.overlay.BlurOverlayController
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Periodically calls takeScreenshot() (API 30+) roughly once per second while the
 * accessibility service is enabled, then classifies each frame with
 * [ExplicitContentClassifier] off the main thread. Capture scheduling never waits on
 * classification — the ~1s cadence is independent of how long inference takes.
 * [AutoBlurController] turns each result into show/hide decisions on its own
 * [BlurOverlayController] instance (separate from the manual debug toggle's). Results also log
 * through [CaptureLog], shown on the main screen, for sanity-checking.
 */
class ScreenCaptureService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val captureRunnable = Runnable { captureFrame() }
    private lateinit var classifier: ExplicitContentClassifier
    private lateinit var classificationExecutor: ExecutorService
    private lateinit var overlayController: BlurOverlayController
    private lateinit var autoBlurController: AutoBlurController

    override fun onServiceConnected() {
        super.onServiceConnected()
        classifier = ExplicitContentClassifier(this)
        classificationExecutor = Executors.newSingleThreadExecutor()
        overlayController = BlurOverlayController(this)
        autoBlurController = AutoBlurController(overlayController)
        Log.d(TAG, "Service connected, starting capture loop")
        scheduleNextCapture(0L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — capture runs on its own timer, independent of accessibility events.
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(captureRunnable)
        classificationExecutor.shutdownNow()
        classifier.close()
        overlayController.hide()
        Log.d(TAG, "Service unbound, capture loop stopped")
        return super.onUnbind(intent)
    }

    private fun scheduleNextCapture(delayMs: Long) {
        handler.removeCallbacks(captureRunnable)
        handler.postDelayed(captureRunnable, delayMs)
    }

    private fun captureFrame() {
        val wasBlurredBeforeCapture = overlayController.isShowing
        if (wasBlurredBeforeCapture) {
            // takeScreenshot() captures the whole composited display, including our own
            // overlay — if we don't hide it first, we just photograph our own black screen,
            // which always reads as "clean" and causes an endless show/hide loop. Give the
            // compositor a couple of frames to actually settle before capturing.
            overlayController.hide()
            handler.postDelayed({ requestScreenshot(wasBlurredBeforeCapture = true) }, HIDE_SETTLE_DELAY_MS)
        } else {
            requestScreenshot(wasBlurredBeforeCapture = false)
        }
    }

    private fun requestScreenshot(wasBlurredBeforeCapture: Boolean) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    if (wasBlurredBeforeCapture) {
                        // Re-cover immediately, before classification runs, to minimize how
                        // long the real content is exposed.
                        overlayController.show(touchable = false)
                    }
                    // Reschedule immediately — classification latency must not affect cadence.
                    scheduleNextCapture(CAPTURE_INTERVAL_MS)
                    classifyAndLog(result, wasBlurredBeforeCapture)
                }

                override fun onFailure(errorCode: Int) {
                    if (wasBlurredBeforeCapture) {
                        overlayController.show(touchable = false)
                    }
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        Log.w(TAG, "Rate limited, backing off ${BACKOFF_MS}ms")
                        CaptureLog.record("rate limited")
                        scheduleNextCapture(BACKOFF_MS)
                    } else {
                        Log.w(TAG, "Capture failed, errorCode=$errorCode")
                        CaptureLog.record("failed ($errorCode)")
                        scheduleNextCapture(CAPTURE_INTERVAL_MS)
                    }
                }
            }
        )
    }

    /**
     * Converts the hardware-backed screenshot to a software Bitmap and classifies it off the
     * main thread, then logs the result back on the main thread.
     */
    private fun classifyAndLog(result: ScreenshotResult, wasBlurredBeforeCapture: Boolean) {
        val hardwareBitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
        val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
        hardwareBitmap?.recycle()
        result.hardwareBuffer.close()

        if (softwareBitmap == null) {
            Log.w(TAG, "Could not wrap screenshot HardwareBuffer as a Bitmap")
            CaptureLog.record("captured (classify failed)")
            return
        }

        classificationExecutor.execute {
            val startMs = SystemClock.elapsedRealtime()
            val classification = classifier.classifyTiled(softwareBitmap)
            val elapsedMs = SystemClock.elapsedRealtime() - startMs
            softwareBitmap.recycle()
            val confidenceText = "%.3f".format(classification.explicitConfidence)
            val peekTag = if (wasBlurredBeforeCapture) "peek, " else ""
            handler.post {
                // show()/hide() go through WindowManager and must run on a Looper thread.
                autoBlurController.onClassification(classification.explicitConfidence)
                val blurState = if (overlayController.isShowing) "BLUR ON" else "blur off"
                Log.d(TAG, "Frame captured at ${System.currentTimeMillis()}, $peekTag" +
                    "explicit=$confidenceText, ${elapsedMs}ms, $blurState")
                CaptureLog.record("captured ($peekTag" + "explicit=$confidenceText, ${elapsedMs}ms) [$blurState]")
            }
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureService"

        // Tunables.
        private const val CAPTURE_INTERVAL_MS = 1000L
        private const val BACKOFF_MS = 2000L
        private const val HIDE_SETTLE_DELAY_MS = 50L
    }
}
