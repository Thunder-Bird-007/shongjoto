package com.shongjoto.app.calibration

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.shongjoto.app.classifier.CalibrationComparisonResult
import com.shongjoto.app.mode.BlurMode
import com.shongjoto.app.mode.BlurModeScheduler
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
    val falconsaiScore: Float,
    val nudenetScore: Float,
    val overlayWasShowing: Boolean,
    val mode: BlurMode
)

/**
 * Records manually-labeled calibration data points: each entry pairs a human-provided
 * ground-truth label (tapped on [CalibrationOverlayService]'s floating button) with all three
 * bundled models' readings at that exact moment, on that exact frame (the tap triggers a fresh
 * on-demand capture+classify — see
 * [com.shongjoto.app.capture.ScreenCaptureService.requestCalibrationCapture] — rather than reusing
 * a possibly-stale periodic reading, and all three models run against that one captured frame so
 * they're being compared on identical input). This is what turns "the app misses stuff" into an
 * actual dataset threshold tuning (or model selection) can be checked against, instead of more
 * guessing.
 *
 * Persisted as CSV in the app's external files dir so it survives app restarts and can be pulled
 * off the device (via MainActivity's share button, a file manager, or `adb pull`) without a
 * network connection — this app has none.
 *
 * File name bumped to v2 when the falconsai/nudenet columns were added, rather than appending
 * mixed-width rows to the original calibration_log.csv — that file's earlier GantMan-only rows
 * are still around and still valid for GantMan-vs-GantMan analysis, just in a different file.
 */
object CalibrationLog {
    /** Most-recent-first, for MainActivity's live display. Not the source of truth — the CSV
     * file is — but avoids re-reading the file just to show a running count. */
    val entries = mutableStateListOf<CalibrationEntry>()

    private const val FILE_NAME = "calibration_log_v2.csv"
    private const val HEADER = "timestamp_iso,label,strong_confidence,sexy_confidence," +
        "falconsai_score,nudenet_score,overlay_was_showing,mode\n"

    fun record(
        context: Context,
        label: CalibrationLabel,
        result: CalibrationComparisonResult,
        overlayWasShowing: Boolean
    ) {
        val entry = CalibrationEntry(
            timestampMs = System.currentTimeMillis(),
            label = label,
            strongConfidence = result.gantman.strongConfidence,
            sexyConfidence = result.gantman.sexyConfidence,
            falconsaiScore = result.falconsaiScore,
            nudenetScore = result.nudenetScore,
            overlayWasShowing = overlayWasShowing,
            mode = BlurModeScheduler.currentMode()
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
                append("%.4f".format(entry.falconsaiScore)).append(',')
                append("%.4f".format(entry.nudenetScore)).append(',')
                append(entry.overlayWasShowing).append(',')
                append(entry.mode.name).append('\n')
            }
        )
    }

    private fun isoTimestamp(timestampMs: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(timestampMs))
    }
}
