# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

EchoSense — a specialized fork of Google AI Edge Gallery, an Android app for running on-device Generative AI using LiteRT models. Provides 6 visual assistance tasks for visually impaired users plus AI Chat, all powered by an embedded Gemma 3n 2B model. Fully offline-capable (bundled model works without internet; online model allowlist fetch is non-blocking). Written entirely in Kotlin with Jetpack Compose (no XML layouts). Targets Android 12+ (minSdk=31, targetSdk=35, compileSdk=35). Version 1.0.9 (versionCode 17).

## Build Commands

All commands run from the `Android/src/` directory:

```bash
./gradlew assembleDebug          # Debug build
./gradlew assembleRelease        # Release build
./gradlew test                   # Unit tests
./gradlew connectedAndroidTest   # Instrumented tests (requires device/emulator)
./gradlew testDebugUnitTest      # Run only debug unit tests
```

Run a single test class:
```bash
./gradlew test --tests "com.google.ai.edge.gallery.ui.echosense.StreamingSentenceChunkerTest"
```

Run a single test method:
```bash
./gradlew test --tests "com.google.ai.edge.gallery.ui.echosense.StreamingSentenceChunkerTest.token at a time produces correct sentences"
```

Only arm64-v8a ABI is included (`ndk.abiFilters`). No linting or formatting tools (ktlint, detekt, spotless) are configured; only `kotlin.code.style=official` in `gradle.properties`.

## Required Setup Before Building

You must configure HuggingFace OAuth credentials (required for model downloads):

1. Create an OAuth app at https://huggingface.co/docs/hub/oauth#creating-an-oauth-app
2. Set `clientId` and `redirectUri` in `app/src/main/java/com/google/ai/edge/gallery/common/ProjectConfig.kt`
3. Set `manifestPlaceholders["appAuthRedirectScheme"]` in `app/build.gradle.kts` (line ~45) to match your redirect URL scheme

## Architecture

**Pattern**: MVVM with unidirectional data flow. Single-activity (`MainActivity`) architecture.

**Dependency Injection**: Dagger Hilt. App-wide singletons configured in `di/AppModule.kt`. ViewModels use `@HiltViewModel`. `kapt` used for Hilt annotation processing.

**Navigation**: Compose Navigation via `GalleryNavGraph.kt`. Three routes: home screen → model list → model page. Deep links supported (`com.google.ai.edge.gallery://model/{taskId}/{modelName}`).

**Data Persistence**: Protocol Buffers via DataStore — `settings.proto` defines `Settings`, `UserData`, and `EchoSenseSettings` messages. Serialized to `settings.pb` and `user_data.pb`. Encrypted with `androidx.security:security-crypto`.

**Categories**: All tasks are in the single `ECHOSENSE` category (defined in `Categories.kt`).

**Offline Resilience**: `ModelManagerViewModel.loadModelAllowlist()` immediately registers all CustomTasks with bundled models, then fetches the model allowlist in the background. Network failures produce a non-blocking Snackbar, not a blocking dialog.

### Key Components

- **`ModelManagerViewModel`** — Central coordinator for model lifecycle: downloading, initialization, cleanup, allowlist loading, and HuggingFace OAuth token management. Also loads/saves EchoSense settings via `AppSettings`.
- **`Task`** (in `data/Tasks.kt`) — Represents a feature/use case with associated models. 7 built-in task IDs: `NAVIGATION_ASSISTANCE`, `COLLISION_AVOIDANCE`, `CURRENCY_MODE`, `DOCUMENT_READER`, `DOCUMENT_TRANSLATOR`, `LLM_CHAT`.
- **`Model`** (in `data/Model.kt`) — Represents an AI model with config, download status, and data files.
- **`CustomTask`** interface (in `customtasks/common/CustomTask.kt`) — Extension point for adding new AI-powered features. Requires implementing `task`, `initializeModelFn`, `cleanUpModelFn`, and `MainScreen` composable. Registered via Hilt `@IntoSet` binding.
- **`BundledModelHelper`** — Copies bundled asset models (e.g., `gemma-3n-E2B-it-int4.litertlm`) from APK assets to external storage for runtime use.
- **`AppSettings`** (in `ui/home/AppSettings.kt`) — Global in-memory settings (video preview, text overlay, response style) persisted to DataStore via `EchoSenseSettings` proto.

### EchoSense Tasks (under `ui/echosense/`)

All visual assistance tasks share a common base:

- **`EchoSenseBaseViewModel.kt`** — Abstract base ViewModel with camera→bitmap→LLM inference→streaming TTS pipeline. Subclasses implement `getAnalysisPrompt(customPrompt, isVerbose)`.
- **`BundledModels.kt`** — Shared `createBundledGemma3nForChat()` model factory used by all tasks.
- **`DetectionHelper.kt`** / **`ProximityTypes.kt`** — Object detection via MediaPipe Tasks Vision (`object_detector.task` asset).
- **`VoiceCommandHelper.kt`** — Voice command processing (start/stop/custom prompts).
- **`StreamingSentenceChunker.kt`** — Breaks streaming LLM tokens into complete sentences for natural TTS.
- **`NativeTtsQueuePlayer.kt`** — Text-to-speech queue for continuous audio output.

**Task directories** (each has `*ViewModel.kt`, `*Screen.kt`, `*TaskModule.kt`):
- `navigationassistance/` — Scene description for safe navigation
- `collisionavoidance/` — Real-time object detection with proximity alerts (MediaPipe + TTS)
- `currencymode/` — Currency/banknote identification
- `documentreader/` — Read text files, PDFs (rendered to bitmap + LLM OCR), and images
- `documenttranslator/` — Same as reader but translates to English via LLM

### AI Chat (under `ui/llmchat/`)

- **`LlmChatTaskModule.kt`** — CustomTask for multi-turn LLM chat with bundled model.
- **`LlmChatViewModel.kt`** — Chat ViewModel with optional TTS support (toggle via `ttsEnabled` flow; streaming tokens fed to `StreamingSentenceChunker` → `NativeTtsQueuePlayer`).
- **`LlmChatScreen.kt`** — Chat UI with TTS toggle FAB.

### Bundled Assets

- `gemma-3n-E2B-it-int4.litertlm` (~3.4GB) — Bundled LLM model
- `object_detector.task` (~4.6MB) — MediaPipe object detection model
- `.task` and `.litertlm` files are stored uncompressed in APK for fast FileDescriptor access

### Custom Task Extension Pattern

To add a new task:
1. Create a ViewModel extending `EchoSenseBaseViewModel` (or `ChatViewModel` for chat-based tasks)
2. Create a Screen composable
3. Create a class implementing `CustomTask` with a Hilt `@Module` using `@Provides @IntoSet`
4. Add a task ID constant to `BuiltInTaskId` in `data/Tasks.kt`
5. Add the task ID to `allLegacyTaskIds` and to `PREDEFINED_ECHOSENSE_TASK_ORDER` in `HomeScreen.kt`

### Package Structure (under `com.google.ai.edge.gallery`)

- `common/` — Shared utilities, `ProjectConfig` (OAuth config), `BundledModelHelper`
- `customtasks/common/` — `CustomTask` interface and data types
- `data/` — Domain models (`Task`, `Model`, `Config`), `DataStoreRepository`, `Categories`
- `di/` — Hilt module (`AppModule`)
- `ui/common/chat/` — Reusable chat UI components shared across task screens
- `ui/common/modelitem/` — Model card and download UI
- `ui/common/textandvoiceinput/` — Voice input via `HoldToDictateViewModel`
- `ui/echosense/` — Base ViewModel, shared utilities, and 5 task subdirectories
- `ui/home/` — Home screen, `AppSettings`, `SettingsDialog`
- `ui/llmchat/` — Multi-turn LLM chat with TTS
- `ui/modelmanager/` — Model management and initialization
- `ui/navigation/` — `GalleryNavGraph` (Compose Navigation)
- `ui/theme/` — Material 3 theming with runtime theme switching

## Build Configuration Notes

- AGP 8.13.2, Kotlin 2.1.0, Compose BOM 2025.11.01
- Kotlin compiler arg `-Xcontext-receivers` is enabled
- JVM target: Java 11
- Compose and BuildConfig features enabled
- Proto compilation uses `protoc` with Java Lite plugin
- Google Services plugin is present but `apply false` by default (requires `google-services.json` to enable Firebase)
- `android:largeHeap="true"` in AndroidManifest (needed for large model handling)
- Permissions: CAMERA, RECORD_AUDIO, WAKE_LOCK, FOREGROUND_SERVICE_DATA_SYNC, INTERNET, ACCESS_NETWORK_STATE

## CI

GitHub Actions workflow (`.github/workflows/build_android.yaml`): runs `./gradlew assembleRelease` with Java 21 (Temurin) on push/PR to main affecting `Android/**`.
