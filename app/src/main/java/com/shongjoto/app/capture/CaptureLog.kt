package com.shongjoto.app.capture

import androidx.compose.runtime.mutableStateListOf

data class CaptureLogEntry(val timestampMs: Long, val outcome: String)

/**
 * In-memory, most-recent-first record of capture attempts, so cadence can be eyeballed
 * directly in the app's UI without needing logcat access (third-party logcat-viewer apps
 * can't read another app's log on a non-rooted device without an adb grant).
 */
object CaptureLog {
    val entries = mutableStateListOf<CaptureLogEntry>()

    fun record(outcome: String) {
        entries.add(0, CaptureLogEntry(System.currentTimeMillis(), outcome))
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(entries.lastIndex)
        }
    }

    private const val MAX_ENTRIES = 10
}
