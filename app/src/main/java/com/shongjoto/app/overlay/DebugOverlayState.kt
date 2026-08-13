package com.shongjoto.app.overlay

import androidx.compose.runtime.mutableStateOf

/**
 * Whether the manual debug overlay ([OverlayService]) is currently showing. MainActivity's
 * switch reads this directly rather than tracking its own local state: the overlay can be
 * dismissed by a tap or the auto-timeout, neither of which triggers an Activity lifecycle
 * callback (a WindowManager overlay is a separate window, not a screen in the app — the
 * Activity stays "resumed" underneath it the whole time), so anything not reading the real
 * state directly goes stale until the user leaves and reopens the app.
 */
object DebugOverlayState {
    val isShowing = mutableStateOf(false)
}
