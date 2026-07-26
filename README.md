# EchoSense

EchoSense is an accessibility-focused visual assistance app for Android and iOS. It
combines camera input, OCR, speech, object and code detection, depth sensing, and
multimodal language models to help users understand surroundings and printed
information.

EchoSense can run visual analysis with a downloaded Gemma model, keeping images on
the device and preserving core functionality without an internet connection. Users
may also connect their own Gemini or OpenAI API key for optional online analysis.

## Functions

- **Navigate** describes scenes and accepts spoken requests such as “help me find my
  keys.”
- **Currency** identifies visible bank notes.
- **Read** provides OCR, instant reading, guided document capture, color and
  brightness information, barcode and QR scanning, and an accessible magnifier.
- **Assistant** supports multi-turn text and voice chat with camera images and
  imported images or documents.
- **Translate** extracts and translates visible or imported text.
- **Safety** adds object and proximity warnings, including LiDAR depth on supported
  iPhones and ARCore depth on supported Android devices.

Safety and proximity information is experimental. EchoSense is not a certified
mobility aid and should not replace a cane, guide dog, attentive travel, or other
established safety practices.

## Projects

- [Android application and build instructions](Android/README.md)
- [iOS application and build instructions](EchoSenseiOS/README.md)
- [Competitive feature roadmap](COMPETITIVE_FEATURE_PARITY_ROADMAP.md)
- [Privacy policy](PRIVACY_POLICY.txt)
- [Android bug-report guide](Bug_Reporting_Guide.md)

The large language models are not bundled with either application. Users can
download a supported model from Settings or import a compatible local model.

## Repository layout

```text
EchoSense/
├── Android/            Android Studio and Gradle project
├── EchoSenseiOS/       Xcode workspace and iOS source
├── PRIVACY_POLICY.txt
└── COMPETITIVE_FEATURE_PARITY_ROADMAP.md
```

## Development

Build each platform from its own project root:

```bash
cd Android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

For iOS:

```bash
cd EchoSenseiOS
pod install
open EchoSenseiOS.xcworkspace
```

See the platform README files for device requirements, model setup, architecture,
permissions, and troubleshooting.

## Attribution and licensing

The Android application began as a customization of the open-source
[Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) project and
retains portions of its model-management and LiteRT-LM integration. Inherited
source files retain their original notices.

Source code is licensed under the Apache License 2.0 unless a file states otherwise.
Downloaded models and third-party components remain subject to their own licenses.
See [LICENSE](LICENSE) and the license information shown in the apps.
