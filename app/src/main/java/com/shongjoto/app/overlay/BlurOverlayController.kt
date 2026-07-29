package com.shongjoto.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Adds/removes a full-screen [TYPE_APPLICATION_OVERLAY] scrim over whatever app is in the
 * foreground. On API 31+ it also blurs the content behind the scrim via [FLAG_BLUR_BEHIND];
 * the scrim itself is the reliable part (works on every supported API level) and the blur is
 * a cosmetic layer on top of it, since cross-window blur isn't available below Android 12
 * and can be disabled system-wide (battery saver, "reduce transparency", etc).
 */
class BlurOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    val isShowing: Boolean
        get() = overlayView != null

    fun show() {
        if (overlayView != null) return

        val view = View(context).apply {
            setBackgroundColor(SCRIM_COLOR)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                if (windowManager.isCrossWindowBlurEnabled) {
                    flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    blurBehindRadius = blurRadiusPx()
                }
            }
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

    private fun blurRadiusPx(): Int =
        (BLUR_BEHIND_RADIUS_DP * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "BlurOverlayController"

        // Tunables — adjust once you can see the overlay on-device.
        private const val SCRIM_COLOR = 0xE61B1B1F.toInt() // ~90% opaque dark scrim
        private const val BLUR_BEHIND_RADIUS_DP = 60 // API 31+ only
    }
}
