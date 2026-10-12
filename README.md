# AutoEdit AI — Android Shorts Maker

An early Android MVP for **automatic short edits from your own video clips**. Import videos and optional music; set tempo, duration, speed, filters, zoom, output quality and audio volume. Edit planning runs on the phone. MP4 files are saved under `Movies/AutoEditAI`.

## How to install
Open [GitHub Actions](https://github.com/din820553-debug/AutoEdit-AI/actions/workflows/android.yml), choose a **successful** Android APK run, open **Artifacts**, and download `AutoEditAI-debug-apk`. Unzip and install `app-debug.apk`. This is an unsigned release-style *debug* APK, not a Play Store distribution.

## Tech
Kotlin, Android Media3 Transformer, MediaExtractor + MediaCodec for local music-energy analysis, MediaStore. Min Android 10. JDK 17, Android SDK 36, Gradle 8.14.3.

## Scope and caveats
This first version is a functional baseline, **not** a model-trained AI director. Rhythm detection is approximate; manually override BPM/beat offset if needed. It does not identify cars or judge shots semantically, and does not implement professional motion blur, crossfades, or optical flow. Hardware codec compatibility varies by device. No media is uploaded.

## Build
`gradle testDebugUnitTest assembleDebug` (with JDK 17 / Android SDK).

## Licensing
No software license has yet been granted.
