# AutoEdit AI

Android short-video editing app. First development build: import clips and music, automatically plan cuts to a detected rhythm, customize editing parameters, and export vertical MP4 on-device.

## Download APK
Open **Actions → Android APK → newest successful run → Artifacts → AutoEditAI-debug-apk** (sign in to GitHub if required). Install `app-debug.apk`.

This project is an early **MVP**, not an autonomous semantic AI video director. It uses local signal processing to estimate tempo, automatic cut planning, and Media3 Transformer. No videos are uploaded to an external server.

## Build
JDK 17 + Android SDK 35. `gradle testDebugUnitTest assembleDebug`. The workflow configures the build environment on GitHub Actions.

## Planned
Shot quality scoring, camera motion detection, motion blur, speed ramps, more transitions, preview editing, background export queue, and advanced AI selection.

## Privacy
Source media and exports stay on the device. The Android system file picker supplies temporary URI access.

## License
No license declared yet.
