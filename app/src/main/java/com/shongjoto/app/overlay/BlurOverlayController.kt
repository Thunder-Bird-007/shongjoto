package com.shongjoto.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Minimal surface [AutoBlurController][com.shongjoto.app.capture.AutoBlurController] needs to
 * drive the overlay — split out from [BlurOverlayController] so the hysteresis/debounce state
 * machine can be unit-tested with an in-memory fake instead of a real WindowManager.
 */
interface BlurSurface {
    val isShowing: Boolean
    fun show(touchable: Boolean = true, onTap: (() -> Unit)? = null)
    fun hide()
}

/**
 * Adds/removes a full-screen, fully OPAQUE [TYPE_APPLICATION_OVERLAY] over whatever app is in
 * the foreground, rendering [NoiseOverlayView] (animated grain/static) rather than an actual
 * blur: Android has no API to read another app's window pixels to blur them, and the only real
 * blur mechanism (FLAG_BLUR_BEHIND, API 31+) requires the window to stay translucent — which
 * means some of the content underneath stays visible through it. Every pixel of the noise
 * texture is fully opaque, so this still fully hides content; it just looks like corrupted
 * static instead of a flat color.
 */
class BlurOverlayController(private val context: Context) : BlurSurface {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    override val isShowing: Boolean
        get() = overlayView != null

    /**
     * @param touchable When true, the overlay itself intercepts touches (and [onTap], if set,
     * fires on tap) — used by the manual debug toggle, which has no classifier driving it and
     * needs its own tap-to-dismiss escape hatch. When false, touches pass straight through to
     * whatever's underneath: the user can still scroll/navigate blindly while blurred, which is
     * what actually lets them get away from content and let the auto-deblur logic clear it —
     * this is the mode the real classifier-driven overlay uses.
     * @param onTap Only used when [touchable] is true.
     */
    override fun show(touchable: Boolean, onTap: (() -> Unit)?) {
        if (overlayView != null) return

        val view = NoiseOverlayView(context).apply {
            if (touchable && onTap != null) {
                isClickable = true
                setOnClickListener { onTap() }
            }
        }

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!touchable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // minSdk is 30, so LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS (API 28+) is always available.
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            Log.d(TAG, "Overlay shown (touchable=$touchable)")
        } catch (e: Exception) {
            // Most likely SYSTEM_ALERT_WINDOW was revoked after the caller checked it.
            Log.e(TAG, "Failed to add overlay view", e)
        }
    }

    override fun hide() {
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
    }
}
