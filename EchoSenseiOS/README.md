# EchoSense iOS

EchoSense iOS is a from-scratch SwiftUI port of the Android EchoSense feature set. The app is offline-first and uses native iOS frameworks plus the official Google MediaPipe Tasks iOS SDK where appropriate.

## Current Scaffold

This project intentionally separates UI, camera, OCR, object detection, speech, and local LLM inference behind protocols so multiple agents can implement work streams in parallel.

Core choices:

- SwiftUI app shell with observable feature state.
- AVFoundation camera frames.
- Vision OCR for local document text recognition.
- AVSpeechSynthesizer for local TTS.
- MediaPipe GenAI/GenAIC loaded directly by the app target for the current GenAI-only experiment.
- MediaPipe Tasks Vision is disabled in the app target for this experiment, so collision detection is unavailable until a non-conflicting detector path is selected.
- Small bundled vision assets under `EchoSenseiOS/Assets/Models`; LLMs are downloaded after installation.

## MediaPipe iOS Rules Mapped From Official Docs

Object detection:

- The current iOS app target intentionally does not add `pod 'MediaPipeTasksVision'`.
- Earlier builds showed MediaPipe Vision and GenAI register overlapping Objective-C classes and MediaPipe calculators when both are loaded in one app process.
- Collision detection currently falls back to unavailable when `MediaPipeTasksVision` cannot be imported.
- Use `ObjectDetectorOptions`.
- Set `options.baseOptions.modelAssetPath` to a bundle model path.
- Use `runningMode = .liveStream` for camera streams.
- Provide an `ObjectDetectorLiveStreamDelegate` to receive asynchronous results.
- Convert camera frames to `MPImage` using `MPImage(sampleBuffer:)`.

LLM inference:

- The official MediaPipe LLM docs use `MediaPipeTasksGenAI` and `MediaPipeTasksGenAIC`.
- The app target now includes these pods directly and excludes `MediaPipeTasksVision`, matching the Android-side experiment where Vision was removed to avoid conflicts.
- `LocalLLMClient` imports GenAI conditionally; the concrete `LlmInference` bridge still needs to be implemented.
- Initialize with a local model path.
- Run text generation on a background task.
- Prefer streaming results to mirror Android’s sentence chunking/TTS pipeline.

Sources:

- MediaPipe Object Detection iOS guide: https://developers.google.com/edge/mediapipe/solutions/vision/object_detector/ios
- MediaPipe iOS setup guide: https://developers.google.com/edge/mediapipe/solutions/setup_ios
- MediaPipe LLM Inference iOS guide: https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/ios

## Setup

1. Install CocoaPods 1.12.1 or newer.
2. From `EchoSenseiOS/`, run:

   ```bash
   /usr/local/lib/ruby/gems/4.0.0/bin/pod install
   ```

   If `pod` is on your shell PATH, `pod install` is equivalent.

3. Open `EchoSenseiOS.xcworkspace`, not the `.xcodeproj`.
4. On first launch, download the compatible LLM from the EchoSense model catalog, or import one manually as an advanced fallback.
5. Build on a physical device for camera and MediaPipe live-stream testing.

Command-line verification in this sandbox used explicit project builds because this shell's `xcodebuild` rejected the generated workspace package even though the project and Pods build correctly:

```bash
xcodebuild -project Pods/Pods.xcodeproj -scheme Pods-EchoSenseiOS -configuration Debug -destination 'generic/platform=iOS Simulator' -derivedDataPath DerivedData CONFIGURATION_BUILD_DIR="$PWD/DerivedData/Build/Products/Debug-iphonesimulator" CODE_SIGNING_ALLOWED=NO build
xcodebuild -quiet -project EchoSenseiOS.xcodeproj -scheme EchoSenseiOS -configuration Debug -destination 'generic/platform=iOS Simulator' -derivedDataPath DerivedData CODE_SIGNING_ALLOWED=NO build
```

## Assets

The small `efficientdet_lite2.task` object detector can be bundled. `.litertlm` LLM files must not be added to the app bundle; they are downloaded into Application Support after installation.

## Status

This is a functional foundation, not a finished app. It includes:

- App shell and task navigation.
- Shared EchoSense state model.
- Camera frame source.
- Vision OCR service.
- TTS service.
- Translation placeholder.
- Cloud provider placeholder.
- MediaPipe object detector wrapper compiled as a no-op when Vision is absent.
- GenAI-only app target experiment with `MediaPipeTasksGenAI` and `MediaPipeTasksGenAIC`.
- Local LLM protocol with placeholder generation until the concrete GenAI inference bridge is implemented.
- Terms/privacy and licenses views.
- Orchestration plan for parallel agents.
