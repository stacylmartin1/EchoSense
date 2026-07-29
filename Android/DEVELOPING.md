# EchoSense Android Developer Guide

## Project overview

EchoSense is an accessibility-focused Android app written in Kotlin and Jetpack Compose. It opens
directly into the Navigate function and provides Assistant, Navigate/Safety, Currency, Read, and
Translate functions from a horizontally scrolling bottom function bar.

The app began as a fork of Google AI Edge Gallery. Shared model-management and LiteRT-LM runtime
code has been retained where it is still used, while the old Gallery home, model-list,
model-detail, benchmark, and generic chat pages have been removed. All active application source
now uses the `com.terranet.echosense.android` namespace.

The Kotlin namespace is `com.terranet.echosense.android`, while the installed application ID is
`com.terranettechnologies.echosense`. Do not reintroduce the legacy Gallery application ID in
Gradle configuration, manifest authorities, intents, or runtime lookups.

Large language models are not bundled. Users download Gemma 4 E2B or E4B from public pinned model
URLs or import a compatible `.litertlm` file from Settings.

## Build commands

Run commands from `Android`:

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew testDebugUnitTest
./gradlew connectedAndroidTest
```

The app targets Android 12 / API 31 or newer and is built for `arm64-v8a`.

## Application flow

- `MainActivity.kt` loads model metadata and renders `EchoSenseApp` immediately.
- `EchoSenseApp.kt` creates the terms/privacy view model and renders `EchoSenseScreen`.
- `ui/echosense/EchoSenseScreen.kt` owns the active function and bottom function bar.
- Settings owns model download, import, deletion, online-provider, speech, and display controls.
- There is no Compose navigation graph or model-detail deep-link flow.

## Core architecture

- `ModelManagerViewModel` coordinates model downloads, selection, initialization, and cleanup.
- `CustomTask` implementations are registered through Hilt `@IntoSet` bindings.
- `EchoSenseBaseViewModel` provides the shared camera, local/cloud inference, cancellation, and TTS
  pipeline for the specialized visual functions.
- `LlmChatModelHelper` is the shared LiteRT-LM engine/conversation adapter and must not be removed.
- `AppSettings` stores the current in-memory settings and persists them through `DataStoreRepository`.

## EchoSense functions

Task implementations live under `ui/echosense`:

- `assistant/` — multi-turn text/voice chat with image and document attachments
- `navigationassistance/` — navigation analysis and optional Safety detections/depth
- `currencymode/` — bank-note identification
- `documentreader/` — camera and imported-document reading
- `documenttranslator/` — OCR and translation to English

Each function has a task module, screen, and view model as appropriate. Add a new function by:

1. Adding a stable ID in `BuiltInTaskId`.
2. Implementing and registering a `CustomTask`.
3. Adding the function to `TASK_BANNER_ITEMS` in `EchoSenseScreen.kt`.
4. Reusing `createEchoSenseGemmaModels()` when the function requires the shared model.

## Model delivery

- `verifyNoBundledLlm` runs before every build and rejects `.litertlm` files in app assets.
- Public model definitions are in `ui/echosense/EchoSenseModels.kt`.
- Download/import controls are in `ui/home/SettingsDialog.kt`.
- Completed download notifications open the main EchoSense screen; legacy Gallery model deep links
  are not supported.

## Important testing notes

- Use physical ARM64 devices for camera, GPU, speech, ARCore, and realistic memory testing.
- ARCore is optional. Navigation falls back to camera-only Safety estimates when depth is
  unavailable.
- Test interruption and restart of local generation because native cancellation callbacks can
  arrive after the user presses Stop.
- Do not treat Safety output as a certified mobility aid.

## Code hygiene

- Preserve active EchoSense behavior when cleaning inherited package names or runtime code.
- Do not add large model files to the APK.
- Use Settings for model management rather than adding model controls to individual function pages.
- Keep privacy disclosures synchronized with the top-level `PRIVACY_POLICY.txt`.
