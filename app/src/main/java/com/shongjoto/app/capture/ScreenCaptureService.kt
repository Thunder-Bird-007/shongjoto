package com.shongjoto.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.shongjoto.app.classifier.ExplicitContentClassifier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Periodically calls takeScreenshot() (API 30+) roughly once per second while the
 * accessibility service is enabled, then classifies each frame with
 * [ExplicitContentClassifier] off the main thread. Capture scheduling never waits on
 * classification — the ~1s cadence is independent of how long inference takes. No overlay
 * wiring yet; this step just logs confidence scores (via [CaptureLog], shown on the main
 * screen) so the classifier can be sanity-checked before it drives anything.
 */
class ScreenCaptureService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val captureRunnable = Runnable { captureFrame() }
    private lateinit var classifier: ExplicitContentClassifier
    private lateinit var classificationExecutor: ExecutorService

    override fun onServiceConnected() {
        super.onServiceConnected()
        classifier = ExplicitContentClassifier(this)
        classificationExecutor = Executors.newSingleThreadExecutor()
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
        Log.d(TAG, "Service unbound, capture loop stopped")
        return super.onUnbind(intent)
    }

    private fun scheduleNextCapture(delayMs: Long) {
        handler.removeCallbacks(captureRunnable)
        handler.postDelayed(captureRunnable, delayMs)
    }

    private fun captureFrame() {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    // Reschedule immediately — classification latency must not affect cadence.
                    scheduleNextCapture(CAPTURE_INTERVAL_MS)
                    classifyAndLog(result)
                }

                override fun onFailure(errorCode: Int) {
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
    private fun classifyAndLog(result: ScreenshotResult) {
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
            val classification = classifier.classify(softwareBitmap)
            softwareBitmap.recycle()
            val confidenceText = "%.3f".format(classification.explicitConfidence)
            handler.post {
                Log.d(TAG, "Frame captured at ${System.currentTimeMillis()}, explicit=$confidenceText")
                CaptureLog.record("captured (explicit=$confidenceText)")
            }
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureService"

        // Tunables.
        private const val CAPTURE_INTERVAL_MS = 1000L
        private const val BACKOFF_MS = 2000L
    }
}
