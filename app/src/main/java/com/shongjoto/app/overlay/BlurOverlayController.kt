package com.shongjoto.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Adds/removes a full-screen, fully OPAQUE [TYPE_APPLICATION_OVERLAY] scrim over whatever
 * app is in the foreground. Deliberately solid rather than a translucent "blur" look:
 * Android has no API to read another app's window pixels to blur them, and the only real
 * blur mechanism (FLAG_BLUR_BEHIND, API 31+) requires the window to stay translucent — which
 * means some of the content underneath stays visible through it. Full opacity is what
 * actually satisfies "hide this content," so that's the only mode here.
 */
class BlurOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    val isShowing: Boolean
        get() = overlayView != null

    /**
     * @param onTap Debug-only escape hatch: tapping the overlay dismisses it. This goes away
     * once the real disable flow (challenge activity) exists in a later step — a production
     * overlay must not be dismissible by a single tap.
     */
    fun show(onTap: () -> Unit) {
        if (overlayView != null) return

        val view = View(context).apply {
            setBackgroundColor(SCRIM_COLOR)
            isClickable = true
            setOnClickListener { onTap() }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // minSdk is 30, so LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS (API 28+) is always available.
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            Log.d(TAG, "Overlay shown")
        } catch (e: Exception) {
            // Most likely SYSTEM_ALERT_WINDOW was revoked after the caller checked it.
            Log.e(TAG, "Failed to add overlay view", e)
        }
    }

    fun hide() {
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
            Log.d(TAG, "Overlay hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay view", e)
        } finally {
            overlayView = null
        }
    }

    companion object {
        private const val TAG = "BlurOverlayController"
        private const val SCRIM_COLOR = 0xFF1B1B1F.toInt() // fully opaque — must never be < 0xFF
    }
}
