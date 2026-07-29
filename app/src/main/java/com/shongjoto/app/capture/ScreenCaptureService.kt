package com.shongjoto.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * Periodically calls takeScreenshot() (API 30+) roughly once per second while the
 * accessibility service is enabled. No classification or overlay wiring yet — this step just
 * proves the capture loop runs at the right cadence. Verify via `adb logcat -s
 * ScreenCaptureService`, or in-app via [CaptureLog] (shown on the main screen once the
 * service is enabled) if adb isn't available.
 */
class ScreenCaptureService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val captureRunnable = Runnable { captureFrame() }

    override fun onServiceConnected() {
        super.onServiceConnected()
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
                    Log.d(TAG, "Frame captured at ${System.currentTimeMillis()}")
                    CaptureLog.record("captured")
                    result.hardwareBuffer.close()
                    scheduleNextCapture(CAPTURE_INTERVAL_MS)
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

    companion object {
        private const val TAG = "ScreenCaptureService"

        // Tunables.
        private const val CAPTURE_INTERVAL_MS = 1000L
        private const val BACKOFF_MS = 2000L
    }
}
