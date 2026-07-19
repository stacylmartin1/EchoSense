# Model Assets

Place local model assets here when they should be bundled into the app.

Expected assets:

- `efficientdet_lite2.task` for MediaPipe object detection.
- A compatible iOS local LLM model file for a runtime that does not load MediaPipe GenAI into the app process.

Do not add `.litertlm` LLM files here. EchoSense downloads verified, versioned LLMs after installation and stores them outside the app bundle.
