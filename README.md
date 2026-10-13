# AutoEdit AI — Android Shorts Maker

AutoEdit AI **v0.2** is an offline, early-stage Android auto-editor for short videos.

## What's new in 0.2
- **Smart shot picker:** locally samples short video frames and favors clear, visually detailed, correctly exposed parts (heuristics, **not** object recognition or trained AI).
- **Three quick styles:** CINEMATIC, PHONK, SMOOTH — all settings still editable afterward.
- **Music preview** with stop control; displays audio file name; can remove chosen track.
- **Beat analysis at chosen song start** (previously only analyzed the beginning); manual BPM, beat shift, track offset and music volume remain adjustable.
- Output format, FPS, zoom, speed, saturation, brightness and source audio controls.
- Fixes in GitHub Actions APK compilation.

## APK
Navigate to [Android APK workflow](https://github.com/din820553-debug/AutoEdit-AI/actions/workflows/android.yml), open the newest **green** build, and download artifact `AutoEditAI-debug-apk`. Extract the ZIP and install `app-debug.apk`. This is a **debug** build; no Play Store distribution or auto-update yet.

## Build
JDK 17, Android SDK 36, Gradle 8.14.3; `gradle testDebugUnitTest assembleDebug`. See GitHub Actions for logs.

## Technical architecture
Kotlin + Media3 Transformer (local MP4 export), MediaExtractor/MediaCodec (RMS-based beat analysis), MediaMetadataRetriever (frame sampling), MediaStore (Movies/AutoEditAI).
Minimum Android 10 (API 29). Source videos do not leave the phone; no network permission.

## Limits
Smart selection uses image contrast/sharpness/exposure **only**; it does not detect cars, faces, movement quality or story. BPM can be inaccurate in irregular songs; switch off auto-BPM to override. There is no real motion blur, crossfade, flash or optical flow. Export performance and supported codecs depend on the device. Video decoding, clips with corrupt codecs and very short tracks may fail.

## License
Not licensed for redistribution yet.
