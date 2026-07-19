# EchoSense iOS Multi-Agent Orchestration Plan

This document breaks the iOS port into parallel work streams guided by one orchestrator agent.

## Orchestrator Agent

Responsibilities:

- Own `main` project shape, target settings, build verification, and integration order.
- Keep `README.md`, `Podfile`, and this plan current.
- Enforce the offline-first requirement.
- Merge agent work only after `xcodebuild` succeeds.
- Ensure safety disclaimers, privacy language, and license notices stay consistent with Android.
- Maintain a compatibility matrix for iOS version, device class, model format, and runtime.

Integration cadence:

1. Verify project opens and builds without pods.
2. Install CocoaPods and verify the current GenAI-only app-target build.
3. Integrate detection agent.
4. Integrate OCR/document agent.
5. Integrate TTS/voice agent.
6. Complete the app-target MediaPipe GenAI experiment with Vision disabled.
7. Integrate feature UI agents one mode at a time.

## Agent A: Project Infrastructure

Scope:

- Xcode project settings, signing placeholders, app icons, asset bundling.
- CocoaPods installation path and `EchoSenseiOS.xcworkspace`.
- CI/build scripts.
- Model asset import UX and file storage.

Files:

- `EchoSenseiOS.xcodeproj`
- `Podfile`
- `EchoSenseiOS/Assets`
- `README.md`

Acceptance:

- `pod install` succeeds.
- The app target builds with `MediaPipeTasksGenAI`/`MediaPipeTasksGenAIC` and without `MediaPipeTasksVision`.
- `xcodebuild` succeeds for the app target after Pods are built.
- Model assets are copied or imported without duplicating multi-GB files unnecessarily.

## Agent B: MediaPipe Object Detection

Scope:

- Complete `MediaPipeObjectDetectionService`.
- Use `MediaPipeTasksVision`.
- Bundle `efficientdet_lite2.task`.
- Implement live camera `MPImage(sampleBuffer:)` flow and result mapping.
- Preserve Android collision alert behavior.

Official rules:

- Add `pod 'MediaPipeTasksVision'`.
- Set `ObjectDetectorOptions.baseOptions.modelAssetPath`.
- Use `.liveStream` for camera.
- Set `objectDetectorLiveStreamDelegate`.
- Convert `CMSampleBuffer` to `MPImage`.

Files:

- `Services/Inference/MediaPipeObjectDetectionService.swift`
- `Models/ProximityTypes.swift`
- `Features/EchoSenseSessionViewModel.swift`
- `Assets/Models/efficientdet_lite2.task`

Acceptance:

- Live camera detection returns boxes on device.
- Collision announcements flush stale speech and obey cooldowns.
- Object labels and box geometry are correct for the preview orientation.

## Agent C: Local LLM Runtime

Scope:

- Replace the placeholder with a local runtime that does not collide with MediaPipe Vision.
- Do not link, embed, import, or load `EchoSenseGenAI.framework` from `EchoSenseiOS.app`.
- Support image + text prompts if the selected runtime/model supports multimodal input.
- Stream partial tokens into UI and TTS.

Official rules from MediaPipe LLM docs:

- Add `MediaPipeTasksGenAI` and `MediaPipeTasksGenAIC` pods for MediaPipe LLM Inference.
- Initialize options with a local model path.
- Run generation off the main thread.
- Prefer `generateResponseAsync`-style streaming where available.

Current integration note:

- The Vision pod and GenAI C pod both force-load static graph libraries with overlapping symbols. Moving GenAI into a dynamic framework avoids some link-time collisions but still crashes at runtime when both modules are loaded because Objective-C classes and MediaPipe calculator registrations are process-global.
- Current experiment: `EchoSenseiOS.app` links GenAI/GenAIC directly and excludes Vision. Collision detection is disabled until a non-conflicting detection runtime is selected.

Files:

- `EchoSenseGenAI/EchoSenseGenAI.swift`
- `Services/Inference/LocalLLMClient.swift`
- `Models/Prompts.swift`
- `Features/EchoSenseSessionViewModel.swift`

Acceptance:

- Local model loads from bundle or app documents using the selected non-conflicting runtime.
- Navigation and currency prompts run offline.
- Streaming partials are visible and spoken in sentence chunks.
- Model loading and ready announcements are reliable.

## Agent D: Camera and Frame Pipeline

Scope:

- Harden `CameraFrameSource`.
- Orientation handling.
- Still capture snapshots.
- Preview/analysis frame throttling.
- Permission and interruption handling.

Files:

- `Services/Camera/CameraFrameSource.swift`
- `Views/CameraPreviewView.swift`
- `Features/EchoSenseSessionViewModel.swift`

Acceptance:

- Camera preview appears on device.
- Captured image orientation matches user-facing left/right prompt semantics.
- Frame analysis does not back up under load.

## Agent E: OCR and Documents

Scope:

- Complete Vision OCR quality gates.
- PDF text extraction with `PDFKit`.
- Scanned PDF page rendering to image for OCR.
- Image/file import.
- Thai and multilingual OCR evaluation.

Files:

- `Services/OCR/VisionOCRService.swift`
- `Services/Documents/DocumentTextExtractor.swift`
- `Features/EchoSenseSessionViewModel.swift`

Acceptance:

- Text PDFs read without LLM.
- Scanned PDFs/images OCR locally.
- OCR falls back to local LLM only when local OCR is insufficient.

## Agent F: Translation

Scope:

- Choose offline translation path.
- If Apple Translation framework is available for the target iOS version, integrate it.
- Otherwise implement provider-neutral local/optional cloud fallback.

Files:

- `Services/Translation/TranslationService.swift`
- `Views/SettingsView.swift`

Acceptance:

- Translation mode works offline for supported languages or clearly announces unsupported language state.
- No cloud use unless enabled and configured.

## Agent G: Speech, Voice Commands, and Accessibility

Scope:

- AVSpeechSynthesizer queue behavior mirroring Android.
- Voice selection UI.
- Speech framework voice commands.
- VoiceOver labels and focus order.

Files:

- `Services/Speech/SpeechOutputService.swift`
- `Views/SettingsView.swift`
- all feature views

Acceptance:

- Startup, loading, ready, analyzing, and obstacle announcements are reliable.
- Speech queues do not lag behind collision alerts.
- VoiceOver navigation is usable without visual inspection.

## Agent H: BYOK Cloud Fallback

Scope:

- Provider-neutral cloud fallback service.
- Gemini implementation first.
- Add OpenAI-compatible later without touching feature view models.
- Terms/privacy updates for configured provider.

Files:

- `Services/Inference/CloudVisionClient.swift`
- `Models/AppSettings.swift`
- `Views/SettingsView.swift`
- `Views/TermsPrivacyView.swift`

Acceptance:

- Cloud provider is off by default.
- API keys stay local in app storage/keychain.
- Feature code calls only `CloudVisionClient`, never provider-specific helpers.

## Agent I: Legal, Privacy, and Licenses

Scope:

- App-specific terms and safety disclaimers.
- License notices for MediaPipe, model assets, app code, Apple frameworks, and optional cloud providers.
- Import Android language where appropriate.

Files:

- `Views/TermsPrivacyView.swift`
- `Views/LicensesView.swift`
- `README.md`

Acceptance:

- No Google-generic terms/privacy language.
- Vision assistance disclaimers are prominent.
- Model/runtime licenses are visible in Settings.
