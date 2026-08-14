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
import com.shongjoto.app.classifier.CalibrationComparisonResult
import com.shongjoto.app.classifier.ExplicitContentClassifier
import com.shongjoto.app.classifier.FalconsaiClassifier
import com.shongjoto.app.classifier.NudeNetClassifier
import com.shongjoto.app.mode.BlurModeScheduler
import com.shongjoto.app.overlay.BlurOverlayController
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Periodically calls takeScreenshot() (API 30+) roughly once per second while the
 * accessibility service is enabled, then classifies each frame off the main thread with BOTH
 * live models — [ExplicitContentClassifier] (GantMan, tiled) and [FalconsaiClassifier]
 * (single full-frame pass) — feeding both into [AutoBlurController]'s OR-ensemble. Capture
 * scheduling never waits on classification — the ~1s cadence is independent of how long
 * inference takes. [AutoBlurController] turns each pair of readings into a show/hide decision on
 * its own [BlurOverlayController] instance (separate from the manual debug toggle's). Results
 * also log through [CaptureLog], shown on the main screen, for sanity-checking.
 *
 * [NudeNetClassifier] stays comparison-only, loaded lazily and only ever touched from
 * [requestCalibrationCapture] — see its own doc for why it hasn't earned a place in the live
 * ensemble (yet).
 *
 * Also exposes [requestCalibrationCapture] for
 * [com.shongjoto.app.calibration.CalibrationOverlayService] to trigger an immediate, one-off
 * capture+classify outside the periodic loop, since `takeScreenshot()` is only callable from
 * within an [AccessibilityService]. [instance] is how that unrelated component finds this one —
 * there's exactly one of this service on a device, so a static handle is simpler than plumbing a
 * bind/connection through for a single low-stakes debug feature.
 */
class ScreenCaptureService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val captureRunnable = Runnable { captureFrame() }
    private lateinit var classifier: ExplicitContentClassifier
    private lateinit var falconsaiClassifier: FalconsaiClassifier
    // Comparison-only — see NudeNetClassifier's doc for why it never enters the live loop.
    private var nudenetClassifier: NudeNetClassifier? = null
    private lateinit var classificationExecutor: ExecutorService
    private lateinit var overlayController: BlurOverlayController
    private lateinit var autoBlurController: AutoBlurController

    override fun onServiceConnected() {
        super.onServiceConnected()
        classifier = ExplicitContentClassifier(this)
        falconsaiClassifier = FalconsaiClassifier(this)
        classificationExecutor = Executors.newSingleThreadExecutor()
        overlayController = BlurOverlayController(this)
        autoBlurController = AutoBlurController(overlayController)
        instance = this
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
        instance = null
        handler.removeCallbacks(captureRunnable)
        classificationExecutor.shutdownNow()
        classifier.close()
        falconsaiClassifier.close()
        nudenetClassifier?.close()
        overlayController.hide()
        Log.d(TAG, "Service unbound, capture loop stopped")
        return super.onUnbind(intent)
    }

    /**
     * One-off capture+classify for calibration labeling, independent of the periodic loop's
     * cadence and without disturbing it. Shares the same "hide our own overlay before shooting,
     * restore immediately after" dance as the periodic loop for the same reason: a screenshot
     * taken while blurred would just photograph our own noise texture, and calibration needs the
     * real content underneath to pair with the human's label regardless of current blur state.
     *
     * Runs all three bundled models — [ExplicitContentClassifier] (live/production),
     * [FalconsaiClassifier], and [NudeNetClassifier] (both comparison-only) — against the exact
     * same captured bitmap, so a single human label can be compared against all three fairly:
     * same frame, same moment, no risk of content changing between separate captures per model.
     * The two comparison models are lazily constructed here (not in onServiceConnected) since
     * they're only ever needed when calibration is actually in use.
     */
    fun requestCalibrationCapture(
        onResult: (result: CalibrationComparisonResult, overlayWasShowing: Boolean) -> Unit,
        onFailure: () -> Unit = {}
    ) {
        val wasBlurredBeforeCapture = overlayController.isShowing
        val proceed = {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ContextCompat.getMainExecutor(this),
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        if (wasBlurredBeforeCapture) {
                            overlayController.show(touchable = false)
                        }
                        classifyForCalibration(result, wasBlurredBeforeCapture, onResult, onFailure)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (wasBlurredBeforeCapture) {
                            overlayController.show(touchable = false)
                        }
                        Log.w(TAG, "Calibration capture failed, errorCode=$errorCode")
                        onFailure()
                    }
                }
            )
        }
        if (wasBlurredBeforeCapture) {
            overlayController.hide()
            handler.postDelayed(proceed, HIDE_SETTLE_DELAY_MS)
        } else {
            proceed()
        }
    }

    private fun classifyForCalibration(
        result: ScreenshotResult,
        wasBlurredBeforeCapture: Boolean,
        onResult: (CalibrationComparisonResult, Boolean) -> Unit,
        onFailure: () -> Unit
    ) {
        val bitmap = hardwareResultToBitmap(result) ?: run {
            Log.w(TAG, "Could not wrap calibration screenshot HardwareBuffer as a Bitmap")
            onFailure()
            return
        }
        classificationExecutor.execute {
            val nudenet = nudenetClassifier ?: NudeNetClassifier(this).also { nudenetClassifier = it }

            val gantman = classifier.classifyTiled(bitmap)
            val falconsaiScore = falconsaiClassifier.classify(bitmap)
            val nudenetScore = nudenet.classify(bitmap)
            bitmap.recycle()

            val combined = CalibrationComparisonResult(gantman, falconsaiScore, nudenetScore)
            handler.post { onResult(combined, wasBlurredBeforeCapture) }
        }
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
        val softwareBitmap = hardwareResultToBitmap(result)
        if (softwareBitmap == null) {
            Log.w(TAG, "Could not wrap screenshot HardwareBuffer as a Bitmap")
            CaptureLog.record("captured (classify failed)")
            return
        }

        classificationExecutor.execute {
            val startMs = SystemClock.elapsedRealtime()
            val gantman = classifier.classifyTiled(softwareBitmap)
            val falconsaiScore = falconsaiClassifier.classify(softwareBitmap)
            val elapsedMs = SystemClock.elapsedRealtime() - startMs
            softwareBitmap.recycle()
            val peekTag = if (wasBlurredBeforeCapture) "peek, " else ""
            handler.post {
                // show()/hide() go through WindowManager and must run on a Looper thread.
                autoBlurController.onClassification(gantman, falconsaiScore)
                val blurState = if (overlayController.isShowing) "BLUR ON" else "blur off"
                val strongText = "%.3f".format(gantman.strongConfidence)
                val sexyText = "%.3f".format(gantman.sexyConfidence)
                val falconsaiText = "%.3f".format(falconsaiScore)
                val mode = BlurModeScheduler.currentMode().label
                Log.d(TAG, "Frame captured at ${System.currentTimeMillis()}, $peekTag" +
                    "gantman(strong=$strongText, sexy=$sexyText) falconsai=$falconsaiText, " +
                    "${elapsedMs}ms, $blurState, mode=$mode")
                CaptureLog.record(
                    "captured ($peekTag" +
                        "strong=$strongText, sexy=$sexyText, falconsai=$falconsaiText, " +
                        "${elapsedMs}ms) [$blurState, $mode]"
                )
            }
        }
    }

    /** Consumes (recycles/closes) the hardware buffer either way — caller must not touch [result]
     * again after calling this. */
    private fun hardwareResultToBitmap(result: ScreenshotResult): Bitmap? {
        val hardwareBitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
        val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
        hardwareBitmap?.recycle()
        result.hardwareBuffer.close()
        return softwareBitmap
    }

    companion object {
        private const val TAG = "ScreenCaptureService"

        /** The single running instance, or null while the accessibility service is disabled.
         * Set/cleared on the main thread from [onServiceConnected]/[onUnbind]. */
        var instance: ScreenCaptureService? = null
            private set

        // Tunables.
        private const val CAPTURE_INTERVAL_MS = 1000L
        private const val BACKOFF_MS = 2000L
        private const val HIDE_SETTLE_DELAY_MS = 50L
    }
}
