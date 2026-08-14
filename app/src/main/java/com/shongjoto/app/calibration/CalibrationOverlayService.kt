package com.shongjoto.app.calibration

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.shongjoto.app.capture.ScreenCaptureService
import kotlin.math.abs

/**
 * A small draggable bubble that stays on top of every app while enabled, for manually labeling
 * live content during calibration. Tapping it (without dragging) expands a panel with one button
 * per [CalibrationLabel]; tapping a label triggers a fresh on-demand capture+classify via
 * [ScreenCaptureService.requestCalibrationCapture] and writes the result to [CalibrationLog].
 *
 * Debug/calibration tool only — separate from [com.shongjoto.app.overlay.BlurOverlayController],
 * which does the actual content-hiding. This one is inert with respect to blur decisions; it only
 * ever reads the classifier's output, never acts on it.
 */
class CalibrationOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private var rootView: LinearLayout? = null
    private var panel: LinearLayout? = null
    private var expanded = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addOverlay()
        CalibrationOverlayState.isRunning.value = true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        CalibrationOverlayState.isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addOverlay() {
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_TOUCH_MODAL: touches outside this view's own bounds pass through to whatever
            // app is underneath — this must never block interaction with the rest of the screen.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(120)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val bubble = TextView(this).apply {
            text = "🏷"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val size = dp(48)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(220, 30, 30, 30))
            }
        }

        container.addView(bubble)
        rootView = container

        installDragAndTapHandling(bubble)

        windowManager.addView(container, params)
    }

    private fun installDragAndTapHandling(bubble: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var dragging = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!dragging && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        rootView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) toggleExpanded()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleExpanded() {
        if (expanded) collapse() else expand()
    }

    private fun expand() {
        val container = rootView ?: return
        if (panel != null) return

        val newPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(230, 20, 20, 20))
            }
        }

        for (label in CalibrationLabel.entries) {
            newPanel.addView(
                Button(this).apply {
                    text = label.buttonText
                    textSize = 11f
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    setBackgroundColor(colorFor(label))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, dp(2), 0, dp(2)) }
                    setOnClickListener { recordLabel(label) }
                }
            )
        }

        panel = newPanel
        container.addView(newPanel)
        expanded = true
    }

    private fun collapse() {
        val container = rootView ?: return
        panel?.let { container.removeView(it) }
        panel = null
        expanded = false
    }

    private fun colorFor(label: CalibrationLabel): Int = when (label) {
        CalibrationLabel.EXPLICIT -> Color.rgb(180, 40, 40)
        CalibrationLabel.NOT_EXPLICIT -> Color.rgb(40, 140, 60)
        CalibrationLabel.FALSE_POSITIVE -> Color.rgb(200, 120, 20)
        CalibrationLabel.FALSE_NEGATIVE -> Color.rgb(120, 40, 160)
    }

    /**
     * Hides this entire overlay (bubble + panel, if expanded) before requesting a capture, and
     * restores it once the result comes back — otherwise the screenshot being classified would
     * include our own label buttons, which is exactly the bug this fixes (see commit history:
     * an earlier version only called [collapse] synchronously, which doesn't wait for the
     * compositor to actually stop drawing the panel before the screenshot is taken). Mirrors
     * [com.shongjoto.app.overlay.BlurOverlayController]'s hide-before-capture pattern.
     */
    private fun recordLabel(label: CalibrationLabel) {
        val service = ScreenCaptureService.instance
        if (service == null) {
            Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_SHORT).show()
            collapse()
            return
        }
        collapse()
        val container = rootView
        container?.visibility = View.INVISIBLE
        handler.postDelayed({
            service.requestCalibrationCapture(
                onResult = { result, overlayWasShowing ->
                    container?.visibility = View.VISIBLE
                    CalibrationLog.record(applicationContext, label, result, overlayWasShowing)
                    Toast.makeText(
                        this,
                        "Logged ${label.csvValue}: gantman(strong=%.2f sexy=%.2f) falconsai=%.2f nudenet=%.2f".format(
                            result.gantman.strongConfidence,
                            result.gantman.sexyConfidence,
                            result.falconsaiScore,
                            result.nudenetScore
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                },
                onFailure = {
                    container?.visibility = View.VISIBLE
                    Toast.makeText(this, "Capture failed, not logged", Toast.LENGTH_SHORT).show()
                }
            )
        }, CAPTURE_SETTLE_DELAY_MS)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val DRAG_THRESHOLD_PX = 12

        // Same reasoning/value as ScreenCaptureService.HIDE_SETTLE_DELAY_MS: give the
        // compositor a couple of frames to actually stop drawing this overlay before the
        // screenshot is taken.
        private const val CAPTURE_SETTLE_DELAY_MS = 50L
    }
}
