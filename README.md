# 4riders Next

A new, independent Android app project. It does not modify or depend on the existing `4riders` or `scan4riders` repositories.

## Current foundation (0.1.0)
- Native Android app built with Kotlin + Jetpack Compose.
- Screenshot/image picker.
- On-device OCR using Google ML Kit Text Recognition.
- Conservative first-pass extraction of fare (RM/MYR), distance (km), and duration (minutes).
- Derived RM/km and estimated RM/hour when the required values are detected.
- Explicit review notes when values cannot be confidently identified; no fabricated values.
- GitHub Actions workflow builds a debug APK on pushes to `main`/ `master` and manual dispatch.

## Build
Requires JDK 17 and Gradle 8.9:

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Important scope notes
This is the first working foundation, not a finished AI product. OCR and parsing are currently on-device and rule-based. App-specific layouts, pickup/drop-off interpretation, trip scoring, camera capture, history, configurable operating costs, and richer universal visual understanding are planned next. Per-hour figures currently use only the detected offer duration and do not include travel to pickup, waiting, tolls, or operating costs.
