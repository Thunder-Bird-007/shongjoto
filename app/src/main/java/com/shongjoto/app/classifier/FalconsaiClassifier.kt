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
import kotlin.math.exp

/**
 * Wraps Falconsai/nsfw_image_detection (github.com/huggingface.co/Falconsai/nsfw_image_detection,
 * Apache-2.0), a ViT-base classifier fine-tuned for binary nsfw/normal classification — bundled
 * for A/B comparison against [ExplicitContentClassifier] (GantMan's 5-class MobileNetV2 model),
 * never as a replacement. Only used from calibration taps
 * ([com.shongjoto.app.capture.ScreenCaptureService.requestCalibrationCapture]), never the
 * periodic live-blur loop: a 12-layer transformer at 224x224 is far more expensive per inference
 * than GantMan's MobileNetV2, and unlike that one this isn't tiled (single full-frame pass only)
 * — running this on every ~1s capture, let alone tiled, would be a real battery/latency cost for
 * an unproven model.
 *
 * Converted PyTorch -> ONNX -> TF SavedModel (onnx2tf) -> TFLite with dynamic-range int8
 * weight quantization (activations stay float, no representative dataset needed) — the plain
 * float16 export was numerically fine but came out to 164MB, over GitHub's 100MB per-file limit;
 * this weighs 88MB. The original model's exact-erf GELU activation doesn't lower to any native
 * TFLite op (needs the Flex-ops runtime, which this app doesn't bundle), so the converted model
 * substitutes the standard tanh approximation of GELU before export. Both substitutions verified
 * numerically near-identical to the original PyTorch model's own ONNX export: nsfw-probability
 * deviation under 6e-5 across 5 random inputs, against outputs in the 0.0005-0.0006 range.
 */
class FalconsaiClassifier(context: Context) {

    private val interpreter: Interpreter = Interpreter(loadModelFile(context.assets))

    /** Softmax-normalized probability that the frame is "nsfw" (the model's own binary label,
     * not to be confused with GantMan's per-class breakdown). Higher = more likely explicit. */
    fun classify(bitmap: Bitmap): Float {
        val output = Array(1) { FloatArray(NUM_CLASSES) }
        interpreter.run(preprocess(bitmap), output)
        val (normalLogit, nsfwLogit) = output[0][0] to output[0][1]
        return softmaxNsfwProbability(normalLogit, nsfwLogit)
    }

    fun close() {
        interpreter.close()
    }

    private fun softmaxNsfwProbability(normalLogit: Float, nsfwLogit: Float): Float {
        // Standard 2-class softmax, shifted by the max logit for numerical stability.
        val maxLogit = maxOf(normalLogit, nsfwLogit)
        val expNormal = exp((normalLogit - maxLogit).toDouble())
        val expNsfw = exp((nsfwLogit - maxLogit).toDouble())
        return (expNsfw / (expNormal + expNsfw)).toFloat()
    }

    /** Resize to 224x224 RGB, normalize to [-1, 1] per channel: this model's own preprocessor
     * config (image_mean=[0.5,0.5,0.5], image_std=[0.5,0.5,0.5]) rescales to [0,1] first, then
     * applies (x - 0.5) / 0.5 — equivalent to pixel/127.5 - 1. This is deliberately NOT the same
     * as GantMan's [0,1] normalization; confirmed against the model's actual
     * preprocessor_config.json rather than assumed, since a mismatch here would silently wreck
     * this model's accuracy the same way it would GantMan's (see ExplicitContentClassifier). */
    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val buffer = ByteBuffer.allocateDirect(BYTES_PER_FLOAT * INPUT_SIZE * INPUT_SIZE * CHANNELS)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            buffer.putFloat((((pixel shr 16) and 0xFF) / 127.5f) - 1f) // R
            buffer.putFloat((((pixel shr 8) and 0xFF) / 127.5f) - 1f)  // G
            buffer.putFloat(((pixel and 0xFF) / 127.5f) - 1f)          // B
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
        private const val MODEL_FILE = "model_falconsai.tflite"
        private const val BYTES_PER_FLOAT = 4
        private const val INPUT_SIZE = 224
        private const val CHANNELS = 3
        private const val NUM_CLASSES = 2 // [normal, nsfw], in that order per the model's config
    }
}
