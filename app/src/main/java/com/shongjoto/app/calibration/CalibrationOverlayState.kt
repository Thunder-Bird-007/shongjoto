package com.shongjoto.app.calibration

import androidx.compose.runtime.mutableStateOf

/**
 * Whether [CalibrationOverlayService] is currently running. Set by the service itself in
 * onCreate/onDestroy (same pattern as [com.shongjoto.app.overlay.DebugOverlayState]) so
 * MainActivity's switch reflects reality even if the service is stopped by something other than
 * the switch (e.g. the system killing it under memory pressure).
 */
object CalibrationOverlayState {
    val isRunning = mutableStateOf(false)
}
