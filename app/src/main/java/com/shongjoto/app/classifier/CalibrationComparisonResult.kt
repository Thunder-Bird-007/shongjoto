package com.shongjoto.app.classifier

/**
 * One capture, classified by all three bundled models at once — what
 * [com.shongjoto.app.capture.ScreenCaptureService.requestCalibrationCapture] hands back to a
 * calibration tap. [gantman] is the live/production model (see [ExplicitContentClassifier]);
 * [falconsaiScore] and [nudenetScore] are comparison-only models never used for real blur
 * decisions (see [FalconsaiClassifier]/[NudeNetClassifier]).
 */
data class CalibrationComparisonResult(
    val gantman: FrameReading,
    val falconsaiScore: Float,
    val nudenetScore: Float
)
