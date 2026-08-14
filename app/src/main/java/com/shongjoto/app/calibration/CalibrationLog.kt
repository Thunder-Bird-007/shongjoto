package com.shongjoto.app.calibration

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.shongjoto.app.classifier.FrameReading
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class CalibrationEntry(
    val timestampMs: Long,
    val label: CalibrationLabel,
    val strongConfidence: Float,
    val sexyConfidence: Float,
    val overlayWasShowing: Boolean
)

/**
 * Records manually-labeled calibration data points: each entry pairs a human-provided
 * ground-truth label (tapped on [CalibrationOverlayService]'s floating button) with the
 * classifier's live strong/sexy readings at that exact moment, on that exact frame (the tap
 * triggers a fresh on-demand capture+classify — see
 * [com.shongjoto.app.capture.ScreenCaptureService.requestCalibrationCapture] — rather than reusing
 * a possibly-stale periodic reading). This is what turns "the app misses stuff" into an actual
 * dataset threshold tuning can be checked against, instead of more guessing.
 *
 * Persisted as CSV in the app's external files dir so it survives app restarts and can be pulled
 * off the device (via MainActivity's share button, a file manager, or `adb pull`) without a
 * network connection — this app has none.
 */
object CalibrationLog {
    /** Most-recent-first, for MainActivity's live display. Not the source of truth — the CSV
     * file is — but avoids re-reading the file just to show a running count. */
    val entries = mutableStateListOf<CalibrationEntry>()

    private const val FILE_NAME = "calibration_log.csv"
    private const val HEADER = "timestamp_iso,label,strong_confidence,sexy_confidence,overlay_was_showing\n"

    fun record(context: Context, label: CalibrationLabel, reading: FrameReading, overlayWasShowing: Boolean) {
        val entry = CalibrationEntry(
            timestampMs = System.currentTimeMillis(),
            label = label,
            strongConfidence = reading.strongConfidence,
            sexyConfidence = reading.sexyConfidence,
            overlayWasShowing = overlayWasShowing
        )
        entries.add(0, entry)
        appendToFile(context, entry)
    }

    fun file(context: Context): File = File(context.getExternalFilesDir(null), FILE_NAME)

    private fun appendToFile(context: Context, entry: CalibrationEntry) {
        val target = file(context)
        val isNew = !target.exists()
        target.appendText(
            buildString {
                if (isNew) append(HEADER)
                append(isoTimestamp(entry.timestampMs)).append(',')
                append(entry.label.csvValue).append(',')
                append("%.4f".format(entry.strongConfidence)).append(',')
                append("%.4f".format(entry.sexyConfidence)).append(',')
                append(entry.overlayWasShowing).append('\n')
            }
        )
    }

    private fun isoTimestamp(timestampMs: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(timestampMs))
    }
}
