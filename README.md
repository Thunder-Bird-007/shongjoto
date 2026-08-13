# Shongjoto

Personal, fully offline Android app. Uses an `AccessibilityService` with
`takeScreenshot()` (API 30+) to sample the screen, classifies frames locally
with TensorFlow Lite, and shows a full-screen blur overlay over explicit
content. No network permissions, no cloud calls, no analytics.

- **minSdk**: 30 (required for `AccessibilityService.takeScreenshot()`)
- **targetSdk / compileSdk**: 36
- **Primary device**: Samsung Galaxy A35, Android 16 (API 36)

## Building

CI builds a debug APK on every push (see `.github/workflows/build.yml`);
download it from the Actions run's artifacts and sideload it.

To build locally: `./gradlew :app:assembleDebug` (requires the Android SDK
with platform 36 / build-tools 36.0.0 installed).
