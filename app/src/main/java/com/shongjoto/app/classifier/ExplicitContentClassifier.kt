package com.shongjoto.app.classifier

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class ClassificationResult(
    val scores: Map<String, Float>,
    val explicitConfidence: Float
)

/**
 * Wraps the MobileNetV2-based classifier in assets/model.tflite (from
 * github.com/GantMan/nsfw_model, MIT licensed). Input shape, output labels, and which labels
 * count as "explicit" are all isolated here so swapping to a different model later is a small,
 * contained change rather than a rewrite.
 */
class ExplicitContentClassifier(context: Context) {

    private val interpreter: Interpreter = Interpreter(loadModelFile(context.assets))

    fun classify(bitmap: Bitmap): ClassificationResult {
        val output = Array(1) { FloatArray(LABELS.size) }
        interpreter.run(preprocess(bitmap), output)

        val scores = LABELS.zip(output[0].toList()).toMap()
        val explicitConfidence = EXPLICIT_LABELS.maxOf { label -> scores.getValue(label) }
        return ClassificationResult(scores, explicitConfidence)
    }

    /**
     * Classifies the full frame plus a grid of [GRID_COLS]x[GRID_ROWS] tiles, returning
     * whichever result has the highest explicit confidence. A single whole-frame classify()
     * downscales everything to 224x224 first, so a small explicit region within an otherwise
     * busy screen (e.g. one image in a feed) can shrink below what the model can detect —
     * classifying tiles too catches that, while the whole-frame pass still catches content
     * that a tile boundary might otherwise cut awkwardly in half.
     */
    fun classifyTiled(bitmap: Bitmap): ClassificationResult {
        var best = classify(bitmap)

        val tileWidth = bitmap.width / GRID_COLS
        val tileHeight = bitmap.height / GRID_ROWS
        if (tileWidth <= 0 || tileHeight <= 0) return best

        for (row in 0 until GRID_ROWS) {
            for (col in 0 until GRID_COLS) {
                val x = col * tileWidth
                val y = row * tileHeight
                val w = if (col == GRID_COLS - 1) bitmap.width - x else tileWidth
                val h = if (row == GRID_ROWS - 1) bitmap.height - y else tileHeight

                val tile = Bitmap.createBitmap(bitmap, x, y, w, h)
                val result = classify(tile)
                tile.recycle()

                if (result.explicitConfidence > best.explicitConfidence) {
                    best = result
                }
            }
        }
        return best
    }

    fun close() {
        interpreter.close()
    }

    /** Resize to the model's input size and normalize to [0,1] RGB, matching the original
     * model's training-time preprocessing (`img_to_array(image) / 255`, see
     * github.com/GantMan/nsfw_model/blob/master/nsfw_detector/predict.py). */
    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
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
        private const val MODEL_FILE = "model.tflite"
        private const val BYTES_PER_FLOAT = 4

        // Input shape — adjust here if the model changes.
        private const val INPUT_SIZE = 224
        private const val CHANNELS = 3

        // Output labels, in the exact order this model emits them, and which subset counts as
        // "explicit" for the overlay threshold. Adjust both if the model changes.
        private val LABELS = listOf("drawings", "hentai", "neutral", "porn", "sexy")
        private val EXPLICIT_LABELS = listOf("hentai", "porn", "sexy")

        // Tile grid for classifyTiled() — 2 columns x 3 rows fits a portrait phone screen's
        // aspect ratio reasonably well. Higher = catches smaller explicit regions but costs
        // more inference time per capture (GRID_COLS * GRID_ROWS + 1 total runs).
        private const val GRID_COLS = 2
        private const val GRID_ROWS = 3
    }
}
