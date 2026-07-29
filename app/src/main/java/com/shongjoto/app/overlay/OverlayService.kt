package com.shongjoto.app.overlay

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Owns the blur overlay's lifetime. Starting this service shows the overlay; stopping it
 * (or the service getting destroyed for any other reason) hides it.
 *
 * Debug-only safety nets while there's no real disable flow yet: tapping the overlay
 * dismisses it, and it auto-clears after [AUTO_DISMISS_MS] no matter what. Both go away once
 * the real challenge-gated disable flow lands in a later step.
 */
class OverlayService : Service() {

    private lateinit var overlayController: BlurOverlayController
    private val handler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { stopSelf() }

    override fun onCreate() {
        super.onCreate()
        overlayController = BlurOverlayController(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        overlayController.show(onTap = { stopSelf() })
        handler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoDismissRunnable)
        overlayController.hide()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val AUTO_DISMISS_MS = 30_000L
    }
}
