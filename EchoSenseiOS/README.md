# EchoSense for iOS

EchoSense is an accessibility-focused visual assistance app for iPhone. It combines the camera, Apple frameworks, bundled object detection, speech, OCR, and a downloaded multimodal language model to describe surroundings and make printed information easier to access.

The iOS app is written in SwiftUI and shares the EchoSense workflow and model catalog with the Android application while using native iOS services where appropriate.

## Features

- **Assistant** provides multi-turn text or voice chat and can analyze a live camera image, photo, PDF, or text document.
- **Navigate** describes the current scene and accepts optional voice commands such as “help me find my keys.”
- **Safety** combines object detection with synchronized LiDAR range data on supported iPhones, identifies broad wall-like surfaces, and falls back to camera-relative proximity estimates.
- **Currency** identifies visible bank notes.
- **Read** extracts text from the camera, photos, and imported documents, reads it aloud,
  and offers Guided Scan with spoken page-edge guidance, automatic stable-page capture,
  perspective correction, image enhancement, and a manual capture fallback. It also
  identifies the approximate center color, reports camera-relative light level, and
  scans common barcodes and QR codes entirely on-device. Its accessible Magnifier
  provides zoom, freeze, flashlight, contrast, inversion, and grayscale controls.
- **Translate** extracts document text and translates it to English.
- **On-device analysis** runs a downloaded Gemma multimodal model through LiteRT-LM.
- **Optional online analysis** supports user-provided Gemini or OpenAI API keys and configurable ask, fallback, or prefer-online behavior.

Safety warnings are experimental estimates based on camera detections. EchoSense is not a certified mobility aid and should not replace a cane, guide dog, attentive travel, or other established safety practices.

## Requirements

- macOS with a current Xcode release capable of building for iOS 17
- iOS 17 or newer
- CocoaPods
- A physical iPhone for camera, microphone, speech, object-detection, and local-model testing
- Several gigabytes of free storage for the model and temporary download parts

The current project uses the LiteRT-LM Swift package from Google's `v0.13.1` branch. Xcode resolves that package when the workspace is opened.

## Build and run

Install the CocoaPods workspace support from this directory:

```bash
cd EchoSenseiOS
pod install
open EchoSenseiOS.xcworkspace
```

Select the `EchoSenseiOS` scheme, choose a signing team, and run on a connected iPhone. Open the `.xcworkspace`, not the `.xcodeproj`, because the project retains CocoaPods-generated build configuration and framework references.

For a command-line build after signing has been configured:

```bash
xcodebuild \
  -workspace EchoSenseiOS.xcworkspace \
  -scheme EchoSenseiOS \
  -destination 'generic/platform=iOS' \
  build
```

Simulator builds can help with layout work, but they do not validate the physical camera pipeline, realistic memory limits, GPU inference, microphone input, or speech output.

On LiDAR-equipped devices, the existing AVFoundation camera session selects the rear LiDAR camera and synchronizes filtered depth with video. Safety reports metric distance in left, center, and right view regions without starting a competing ARKit camera session. Other iPhones continue using the original object-size estimate.

When Navigation submits an image for local or online analysis, it also attaches a compact sensor snapshot containing only recent depth regions and high-confidence object detections. Regional ranges and object labels remain separate so the language model can reconcile them without treating a coarse depth cell as an exact object measurement.

## On-device model setup

The large language model is deliberately **not included in the application bundle**. On first run, EchoSense offers a choice between Gemma 4 E2B (smaller and faster) and E4B (more capable) without requiring an account. Model controls are also available under **Settings → On-Device AI Model**.

Model metadata is pinned in the app to the public, commit-specific Hugging Face download URLs referenced by the Google AI Edge Gallery allowlist. EchoSense does not require its own catalog or model-hosting backend.

Downloads use background `URLSession` tasks and parallel HTTPS range requests. EchoSense checks available storage and verifies the completed model's expected size and SHA-256 value before moving it into Application Support. Users can pause, resume, retry, delete, switch between E2B and E4B, or import a compatible `.litertlm` model from Files.

Do not add `.litertlm` or other large LLM files to the app target. The small Core ML object-detection models under `EchoSenseiOS/Resources` are intentionally bundled for Safety processing.

## Optional online analysis

Online analysis is opt-in. A user can connect Gemini or OpenAI from **Settings → Online Analysis**, supply their own API key, and choose one of three modes:

- **Ask before use** keeps local analysis primary and exposes online analysis as an explicit action.
- **Automatic fallback** contacts the provider only when local analysis fails.
- **Prefer online** uses the provider first when a network connection is available.

The API key is stored in iOS Keychain and must never be committed to this repository. When online analysis runs, the current image and prompt are sent directly to the selected provider and the provider may charge the user's account.

## Architecture

```text
EchoSenseiOS/
├── App/                    SwiftUI application entry point
├── Features/               Shared feature-session state and orchestration
├── Models/                 Settings, prompts, manifest, and proximity types
├── Services/
│   ├── Camera/             AVFoundation frame capture
│   ├── Documents/          File and document text extraction
│   ├── Inference/          LiteRT-LM, cloud, model download, and detection
│   ├── OCR/                Apple Vision text recognition
│   ├── Speech/             TTS, streaming sentences, and voice commands
│   └── Translation/        Local translation workflow
├── Resources/              Bundled Core ML detection assets
└── Views/                  Home, feature, setup, settings, and legal UI
```

Key platform integrations include:

- SwiftUI for application UI and navigation
- AVFoundation for camera frames and audio capture
- Vision and Core ML for OCR and selectable YOLO object detectors
- Speech for navigation voice commands
- AVSpeechSynthesizer for spoken output
- LiteRT-LM for local multimodal generation, with GPU-first initialization and supported fallbacks
- Security/Keychain for user-provided online API credentials

## Permissions

The app requests:

- Camera access for scene, document, and currency capture
- Microphone and speech-recognition access for optional voice commands
- Photo/file access only when the user chooses content to import
- Network access for model downloads and optional online analysis

## Privacy and model licensing

On-device analysis keeps captured images on the iPhone. Imported documents and camera frames should still be treated as sensitive data. Online analysis has different privacy characteristics because it transmits the selected image and prompt to the configured provider.

Downloaded models remain subject to the license linked by the model catalog. EchoSense displays model and open-source license information in Settings.

## Troubleshooting

- If the workspace is missing or CocoaPods reports a lock mismatch, run `pod install` again and reopen `EchoSenseiOS.xcworkspace`.
- If the model is not recognized, confirm Settings reports it as installed, verify sufficient free storage, and restart the app.
- If local inference fails after cancellation, capture the `[generation …]` diagnostics from the Xcode console.
- If voice commands fail, confirm both Microphone and Speech Recognition permissions are enabled in iOS Settings.
- Local multimodal generation is memory intensive. Validate cancellation, backgrounding, and repeated analysis on a physical device.
