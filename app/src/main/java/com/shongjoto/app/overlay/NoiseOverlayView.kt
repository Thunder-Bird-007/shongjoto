package com.shongjoto.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.random.Random

/**
 * Draws animated, fully-opaque RGB static/grain noise instead of a flat color — reads as a
 * corrupted/distorted image rather than a plain black screen, while every pixel stays 100%
 * opaque so the real content underneath is never visible, same guarantee as the flat scrim
 * it replaces.
 */
class NoiseOverlayView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val regenerateRunnable = Runnable { regenerateNoise() }

    init {
        regenerateNoise()
    }

    private fun regenerateNoise() {
        paint.shader = BitmapShader(createNoiseTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        invalidate()
        handler.postDelayed(regenerateRunnable, REFRESH_INTERVAL_MS)
    }

    private fun createNoiseTile(): Bitmap {
        val bitmap = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(TILE_SIZE * TILE_SIZE)
        for (i in pixels.indices) {
            val r = Random.nextInt(256)
            val g = Random.nextInt(256)
            val b = Random.nextInt(256)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b // alpha always 0xFF
        }
        bitmap.setPixels(pixels, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE)
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
        // Tunables — smaller tile / shorter interval = grainier and more "alive", at a
        // (still very cheap) CPU cost for regenerating the noise texture.
        private const val TILE_SIZE = 48
        private const val REFRESH_INTERVAL_MS = 120L
    }
}
