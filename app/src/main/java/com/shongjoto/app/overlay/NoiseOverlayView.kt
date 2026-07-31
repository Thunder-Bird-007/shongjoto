package com.shongjoto.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.random.Random

/**
 * Draws animated, fully-opaque coarse color-block "static" instead of a flat color — reads as
 * a corrupted/distorted image rather than a plain black screen, while every pixel stays 100%
 * opaque so the real content underneath is never visible, same guarantee as the flat scrim it
 * replaces.
 *
 * Deliberately does NOT use RenderEffect/blur here: applying a real blur effect to this view
 * caused the window to render translucent, letting the actual screen content show through
 * underneath the noise — confirmed on-device. Whatever visual polish that would add isn't
 * worth risking the one guarantee this view exists for, so only plain, fully-opaque pixel
 * drawing is used.
 */
class NoiseOverlayView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val handler = Handler(Looper.getMainLooper())
    private val regenerateRunnable = Runnable { regenerateNoise() }

    init {
        regenerateNoise()
    }

    private fun regenerateNoise() {
        val shader = BitmapShader(createNoiseTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        shader.setLocalMatrix(Matrix().apply { setScale(CELL_SIZE_PX.toFloat(), CELL_SIZE_PX.toFloat()) })
        paint.shader = shader
        invalidate()
        handler.postDelayed(regenerateRunnable, REFRESH_INTERVAL_MS)
    }

    /** Small logical grid of random opaque colors — [regenerateNoise] scales each cell up to
     * [CELL_SIZE_PX] on screen, turning it into a coarse mosaic rather than single-pixel static. */
    private fun createNoiseTile(): Bitmap {
        val bitmap = Bitmap.createBitmap(GRID_CELLS, GRID_CELLS, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(GRID_CELLS * GRID_CELLS)
        for (i in pixels.indices) {
            val r = Random.nextInt(256)
            val g = Random.nextInt(256)
            val b = Random.nextInt(256)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b // alpha always 0xFF
        }
        bitmap.setPixels(pixels, 0, GRID_CELLS, 0, 0, GRID_CELLS, GRID_CELLS)
        return bitmap
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(regenerateRunnable)
        super.onDetachedFromWindow()
    }

    companion object {
        // Tunables.
        // Bigger CELL_SIZE_PX = chunkier-looking mosaic. Smaller GRID_CELLS = fewer, larger
        // blocks per tile. Shorter REFRESH_INTERVAL_MS = more "alive" but marginally more CPU
        // to regenerate.
        private const val GRID_CELLS = 16
        private const val CELL_SIZE_PX = 40
        private const val REFRESH_INTERVAL_MS = 150L
    }
}
