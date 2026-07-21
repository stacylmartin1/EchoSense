# EchoSense for Android

EchoSense is an accessibility-focused visual assistance app. It combines the camera, local computer vision, speech, OCR, and an on-device multimodal language model to describe surroundings and make printed information easier to access.

The Android application began as a customization of the open-source Google AI Edge Gallery project. It retains portions of that project's package structure and runtime integration, but its user experience, built-in tasks, model delivery, and settings are specific to EchoSense.

## Features

- **Navigate** describes the current scene and accepts optional voice commands such as “help me find my keys.”
- **Safety** combines object detection with ARCore metric depth and vertical-plane checks when supported, then falls back to camera-relative proximity estimates.
- **Currency** identifies visible bank notes.
- **Read** extracts document text from the camera or an imported file and reads it aloud.
- **Translate** extracts document text and translates it to English.
- **On-device analysis** uses a downloaded Gemma multimodal model without sending camera images to an AI provider.
- **Optional online analysis** supports user-provided Gemini or OpenAI API keys and configurable ask, fallback, or prefer-online behavior.

Safety warnings are experimental estimates based on camera detections. EchoSense is not a certified mobility aid and should not replace a cane, guide dog, attentive travel, or other established safety practices.

## Requirements

- Android Studio with an Android 15 / API 35 SDK installed
- JDK 17 for the Android Gradle Plugin
- Android 12 / API 31 or newer
- A physical ARM64 device for camera and local-model testing
- At least 6 GB of device memory is recommended for the current model
- Several gigabytes of free storage for the model download and temporary download parts

The app is currently built only for `arm64-v8a`. An emulator can be useful for basic UI work, but it is not representative of camera, GPU, speech, or LiteRT-LM behavior.

ARCore is optional rather than an installation requirement. On a Depth API-capable phone, turning on Safety temporarily switches Navigation from CameraX to an ARCore-owned preview so it can estimate metric range. Move the phone gently to improve depth-from-motion results. Low light, glass, mirrors, blank walls, and rapidly moving objects can reduce accuracy.

When Navigation submits an image for local or online analysis, it also attaches a compact sensor snapshot containing only recent depth regions and high-confidence object detections. Regional ranges and object labels remain separate so the language model can reconcile them without treating a coarse depth cell as an exact object measurement.

## Build and run

The Gradle project is in `Android/src`:

```bash
cd Android/src
./gradlew assembleDebug
```

Open `Android/src` in Android Studio to run the `app` configuration on a connected device, or install from the command line:

```bash
./gradlew installDebug
```

Run local unit tests with:

```bash
./gradlew testDebugUnitTest
```

The debug APK is written beneath `Android/src/app/build/outputs/apk/debug/`.

## On-device model setup

The large language model is deliberately **not bundled in the APK**. On first run, EchoSense offers a choice between Gemma 4 E2B (smaller and faster) and E4B (more capable) without requiring an account. The same controls remain available under **Settings → On-Device AI Model**.

Model metadata is pinned in the app to the public, commit-specific Hugging Face download URLs referenced by the Google AI Edge Gallery allowlist. EchoSense does not require its own catalog or model-hosting backend.

Model downloads use background work and parallel HTTPS range requests. Before installation, EchoSense verifies the expected file size and SHA-256 value. Users can pause, resume, retry, delete, switch between E2B and E4B, or manually import a compatible `.litertlm` model from Settings.

The `verifyNoBundledLlm` Gradle task is attached to `preBuild` and fails the build if a `.litertlm` file is accidentally added to application assets. Small object-detection assets such as `efficientdet_lite2.task` remain bundled because they are part of the real-time Safety feature.

## Optional online analysis

Online analysis is opt-in. A user can connect Gemini or OpenAI from **Settings → Online Analysis**, supply their own API key, and choose one of three modes:

- **Ask before use** keeps local analysis primary and exposes online analysis as an explicit action.
- **Automatic fallback** contacts the provider only when local analysis fails.
- **Prefer online** uses the provider first when a network connection is available.

The API key is encrypted with a non-exportable key in Android Keystore. Keys must never be committed to this repository. When online analysis runs, the current image and prompt are sent directly to the selected provider and the provider may charge the user's account.

## Permissions

EchoSense requests only the capabilities needed by its enabled features:

- Camera for scene, document, and currency capture
- Microphone for optional navigation voice commands
- Internet and network state for model downloads and optional online analysis
- Notifications and foreground data service access for long-running model downloads
- Wake lock so a verified model download can finish reliably

## Project layout

```text
Android/src/
├── app/src/main/java/com/google/ai/edge/gallery/
│   ├── data/                         Model catalog and persistence
│   ├── ui/echosense/                 EchoSense tasks and shared behavior
│   ├── ui/home/                      Home screen, settings, and credentials
│   └── worker/                       Background model download worker
├── app/src/main/assets/              Small bundled inference assets
├── app/src/test/                     Local unit tests
├── gradle/                           Gradle wrapper and version catalog
└── settings.gradle.kts               EchoSense Gradle project definition
```

Some internal namespaces still use `com.google.ai.edge.gallery` for compatibility with the original codebase. The installed application label and Gradle root project are EchoSense.

## Privacy and model licensing

On-device analysis keeps captured images on the device. Imported documents and camera frames should still be treated as sensitive data. Online analysis has different privacy characteristics because it transmits the selected image and prompt to the configured provider.

Downloaded models remain subject to the license linked by the model catalog. EchoSense displays model and open-source license information inside Settings. The source files inherited from Google AI Edge Gallery retain their original copyright and Apache 2.0 notices; EchoSense-specific files carry their applicable notices.

## Troubleshooting

- If functions remain disabled after downloading, confirm Settings reports **Downloaded and Ready**, then restart the app so LiteRT-LM can initialize the verified file.
- Keep Google Play services, Android System WebView, and device firmware current.
- Disable battery restrictions for EchoSense if the operating system repeatedly suspends background downloads.
- Use the debug overlay and Logcat when diagnosing initialization, download verification, inference, or TTS problems.
- Local multimodal generation is memory intensive. Test cancellation and restart behavior on physical devices, not only in an emulator.
