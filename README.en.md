<p align="center"><img src="brand/tingo-icon.svg" width="112" alt="Tingo"></p>

<h1 align="center">Tingo for Android</h1>

<p align="center"><b>Every meeting, heard.</b><br>Record meetings on Android, transcribe them on device, and trace each conclusion back to the original words.</p>

<p align="center">
  <a href="https://github.com/zdfb/tingo-android/releases/tag/v0.5.2"><img src="https://img.shields.io/badge/version-v0.5.2-2457ff" alt="v0.5.2"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/license-MIT-34c6f4" alt="MIT license">
  <a href="README.md"><img src="https://img.shields.io/badge/README-%E4%B8%AD%E6%96%87-555" alt="中文 README"></a>
</p>

<p align="center"><a href="docs/android/demo.mp4"><img src="docs/android/demo.gif" width="480" alt="Tingo Android v0.5.2 screen recording preview"></a></p>
<p align="center"><a href="docs/android/demo.mp4">Watch the Android screen recording (MP4, about 34 seconds)</a></p>

The screenshots and recording were captured from v0.5.2 running in an Android API 36 emulator. The meeting is Tingo's built-in sample, not a mockup or a user's recording. The GIF was converted from that recording.

## Project origin

This Android app is based on the open-source [tancheng33/tingo](https://github.com/tancheng33/tingo) project. Thanks to the original author and contributors. This repository is maintained as an independent project, with its Android client implemented separately.

## Android screenshots

<table>
  <tr>
    <td align="center"><b>Record</b><br><img src="docs/android/record.png" alt="Recording screen" width="100%"></td>
    <td align="center"><b>Meetings</b><br><img src="docs/android/library.png" alt="Meeting library" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><b>Meeting details</b><br><img src="docs/android/detail.png" alt="Meeting minutes" width="100%"></td>
    <td align="center"><b>Settings</b><br><img src="docs/android/settings.png" alt="App settings" width="100%"></td>
  </tr>
</table>

## Features

- **Audio and transcription stay on your device.** Record the microphone and optionally try to capture meeting app audio. Paraformer speech recognition, punctuation, and speaker separation run locally; transcription works offline.
- **Trace minutes back to the source.** Connect your own LLM service to create minutes from the transcript. Timestamps jump to the matching audio. Tingo does not upload recordings to the minutes service.
- **Manage meetings.** Search meeting content, archive or restore deleted meetings, export or back up data, and switch between Chinese and English.
- **Keep control of local data.** Recordings and meetings live in Android's app-specific directory. Uninstalling the app deletes that directory, so export or back up meetings you want to keep.

System audio capture uses Android MediaProjection / Audio Playback Capture and requires confirmation during recording. Some meeting apps block playback capture, depending on the app and device.

## Download and install

1. Download `Tingo-Android-v0.5.2-debug.apk` from [v0.5.2 Releases](https://github.com/zdfb/tingo-android/releases/tag/v0.5.2).
2. When Android prompts you, allow your browser or file manager to install apps, then continue.
3. Grant microphone access when you start recording. Follow the system prompt if you want to capture meeting app audio.

This is a test build distributed as an installable debug APK. The package is about 412 MiB. Minimum supported version: Android 8.0 (API 26).

### Minutes service and privacy

Speech recognition and speaker separation run on device. To generate minutes, the **transcript text** is sent to the provider configured in Settings; audio files are not sent. Your API key is stored in app-private settings. Recording, transcription, and playback remain available without an LLM service.

## Build from source

Requires JDK 21, Android SDK 37, NDK 30, Rust, and `cargo-ndk`. Speech models are bundled in the APK; a full build is about 412 MiB.

```bash
cd app/android
./gradlew :app:assembleDebug
```

See the [Android development guide](app/android/README.md) for model licenses, configuration, and test details. The [feature parity table](docs/android-parity.md) compares Android and desktop.

## License

[MIT](LICENSE)
