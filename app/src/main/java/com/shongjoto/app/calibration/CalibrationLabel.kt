package com.shongjoto.app.calibration

/**
 * Ground-truth label a human taps on the floating calibration overlay while looking at whatever
 * is currently on screen. [FALSE_POSITIVE]/[FALSE_NEGATIVE] are shortcuts for the two cases that
 * actually matter for tuning ("the app got this specific frame wrong"); [EXPLICIT]/[NOT_EXPLICIT]
 * are for building a broader labeled dataset by tagging content regardless of whether the app
 * currently agrees with you. Nothing in code enforces the distinction — CalibrationLog just
 * records whichever button was tapped alongside the classifier's live reading at that moment.
 */
enum class CalibrationLabel(val csvValue: String, val buttonText: String) {
    EXPLICIT("explicit", "Explicit"),
    NOT_EXPLICIT("not_explicit", "Not Explicit"),
    FALSE_POSITIVE("false_positive", "False Positive\n(blurred, shouldn't be)"),
    FALSE_NEGATIVE("false_negative", "False Negative\n(not blurred, should be)")
}
