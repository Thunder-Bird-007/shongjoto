package com.shongjoto.app.overlay

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Owns the blur overlay's lifetime. Starting this service shows the overlay; stopping it
 * (or the service getting destroyed for any other reason) hides it.
 *
 * This is a plain background service for now — no foreground notification yet. That lands
 * in a later step once this is driven by the accessibility service instead of a debug toggle.
 */
class OverlayService : Service() {

    private lateinit var overlayController: BlurOverlayController

    override fun onCreate() {
        super.onCreate()
        overlayController = BlurOverlayController(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        overlayController.show()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        overlayController.hide()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
