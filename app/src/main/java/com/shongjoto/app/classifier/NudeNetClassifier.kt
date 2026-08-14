package com.shongjoto.app.classifier

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Wraps notAI-tech/NudeNet's bundled YOLOv8n-based detector (320n.onnx from the `nudenet` PyPI
 * package, AGPL-3.0), converted ONNX -> TFLite via onnx2tf — bundled for A/B comparison against
 * [ExplicitContentClassifier] and [FalconsaiClassifier], never as a live-blur replacement (see
 * those classes' docs for why calibration-only). Structurally different from the other two: this
 * is a region detector with 18 body-part classes (each with an EXPOSED/COVERED variant), not a
 * single explicit/safe classifier — [classify] collapses that down to one score by taking the max
 * confidence across the classes that actually indicate exposed explicit content, ignoring box
 * locations entirely (irrelevant to a single frame-level comparison score).
 */
class NudeNetClassifier(context: Context) {

    private val interpreter: Interpreter = Interpreter(loadModelFile(context.assets))

    /** Max confidence, anywhere in the frame, across the classes indicating exposed
     * genitalia/anus/breast/buttocks. Doesn't include MALE_BREAST_EXPOSED, BELLY_EXPOSED,
     * ARMPITS_EXPOSED, or FEET_EXPOSED — those are common in ordinary shirtless/beach/gym photos
     * and aren't a meaningful explicit-content signal on their own. */
    fun classify(bitmap: Bitmap): Float {
        val input = preprocess(bitmap)
        val output = Array(1) { Array(NUM_ROWS) { FloatArray(NUM_ANCHORS) } }
        interpreter.run(input, output)

        var best = 0f
        for (classIndex in EXPLICIT_CLASS_INDICES) {
            val row = output[0][BOX_COORDS + classIndex]
            for (anchor in 0 until NUM_ANCHORS) {
                if (row[anchor] > best) best = row[anchor]
            }
        }
        return best
    }

    fun close() {
        interpreter.close()
    }

    /**
     * Matches NudeNet's own preprocessing (see notAI-tech/NudeNet's `_read_image`): pad the
     * image to square with black borders on the bottom/right (not centered — a straight
     * top-left-anchored pad), *then* resize to 320x320, normalize to [0,1] RGB. Padding first
     * (rather than resizing with distortion or center-cropping) is what the model was trained
     * against, so it's reproduced exactly rather than approximated.
     */
    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        val squared = Bitmap.createBitmap(maxSide, maxSide, Bitmap.Config.ARGB_8888)
        Canvas(squared).drawBitmap(bitmap, 0f, 0f, null) // leaves the pad area black by default

        val resized = Bitmap.createScaledBitmap(squared, INPUT_SIZE, INPUT_SIZE, true)
        squared.recycle()

        val buffer = ByteBuffer.allocateDirect(BYTES_PER_FLOAT * INPUT_SIZE * INPUT_SIZE * CHANNELS)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255f) // R
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)  // G
            buffer.putFloat((pixel and 0xFF) / 255f)          // B
        }
        if (resized !== bitmap) {
            resized.recycle()
        }
        buffer.rewind()
        return buffer
    }

    private fun loadModelFile(assets: AssetManager): MappedByteBuffer {
        val fd = assets.openFd(MODEL_FILE)
        FileInputStream(fd.fileDescriptor).use { input ->
            return input.channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            )
        }
    }

    companion object {
        private const val MODEL_FILE = "model_nudenet.tflite"
        private const val BYTES_PER_FLOAT = 4
        private const val INPUT_SIZE = 320
        private const val CHANNELS = 3

        // Output tensor is [1, 22, 2100]: 4 box-coord rows (cx, cy, w, h), then 18 per-class
        // confidence rows, across 2100 anchor positions. Box rows are unused — see [classify].
        private const val BOX_COORDS = 4
        private const val NUM_ROWS = 22
        private const val NUM_ANCHORS = 2100

        // Indices into NudeNet's 18-class label list (BUTTOCKS_EXPOSED, FEMALE_BREAST_EXPOSED,
        // FEMALE_GENITALIA_EXPOSED, ANUS_EXPOSED, MALE_GENITALIA_EXPOSED) — the *_EXPOSED classes
        // that indicate genuinely explicit content, as opposed to merely shirtless/midriff/etc.
        private val EXPLICIT_CLASS_INDICES = intArrayOf(2, 3, 4, 6, 14)
    }
}
