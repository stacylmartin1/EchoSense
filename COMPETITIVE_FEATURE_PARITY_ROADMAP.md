# EchoSense Competitive Feature Parity Roadmap

Last updated: July 24, 2026

Status legend: **Complete**, **Implemented; device validation pending**, **In progress**,
**Planned**, **Research**, **Deferred**

This is a living document. Update the date, implementation status, platform notes, and
known limitations whenever EchoSense capabilities change.

## Product direction

EchoSense should combine the reliable, single-purpose assistive tools found in Seeing AI,
Envision AI, and Sullivan+ with its existing differentiator: private multimodal LLM
intelligence that remains useful offline.

The intended hierarchy is:

1. Use deterministic on-device perception for text, barcodes, objects, faces, colors,
   light, and depth.
2. Use the on-device LLM to explain and reason over those observations.
3. Offer cloud intelligence only when it is available and the user consents.
4. Coordinate speech and haptics centrally so urgent and user-requested information does
   not overlap.

EchoSense is an assistive information application. Safety and depth features must not be
presented as certified navigation, collision-avoidance, medical, or financial verification.

## Competitor review

### Microsoft Seeing AI

Seeing AI is the strongest overall phone-app benchmark. Its useful feature categories are:

- Instant short-text reading
- Guided document alignment and capture
- Document formatting and document question answering
- Scene and photograph descriptions
- Touch exploration of photographs to hear object locations
- Barcode and accessible QR scanning
- Product information when a database result exists
- Person detection and recognition of enrolled faces
- Age, gender, and expression estimates
- Currency recognition
- Color identification
- Light-level feedback
- Processing media shared from other applications

Strengths include mature accessibility, low-friction single-purpose tools, a strong
document workflow, and broad language support. Limitations include reliance on available
product data, probabilistic face/color/currency results, and no advertised depth-based
ranging or continuous hazard awareness. Rich generative functions should be assumed to
need a service connection unless current product testing proves otherwise.

### Envision AI

The Envision mobile app includes:

- Instant, long-form, and handwritten text recognition
- Automatic document capture, layout recognition, and batch scanning
- A structured Reader, saved Library, and export
- Scene and color descriptions
- Object and person finding
- Enrollment and recognition of familiar faces
- Barcode and accessible QR scanning
- Voice or text questions over text, images, and imported files
- OCR across more than 60 languages
- User-customizable feature ordering

Envision has the strongest document workflow. Its Ask Envision capability uses an online
OpenAI model. Envision documents that online OCR improves accuracy and layout detection,
and that Android needs connectivity for general operation. Envision Glasses capabilities
are separate from the phone app and are not required for initial phone-app parity.

### Sullivan+

Sullivan+ includes:

- Automatic scene descriptions and AI image analysis
- OCR and document/PDF reading
- Face detection and enrolled-face recognition
- Object finding with speech and vibration
- Currency and color recognition
- Magnification, inversion, and contrast tools
- Light detection
- Shared-image processing
- Community assistance and some regional appliance integrations

Useful ideas include automatic descriptions, tactile object-finding feedback, and
low-vision display tools. Limitations include ads/in-app purchases, a quota mechanism for
some PDF usage, limited documented currency coverage, regional integrations, and unclear
offline guarantees for multimodal analysis.

## Current EchoSense position

### Existing strengths

- **Complete — iOS and Android:** Navigate, Currency, Read, Assistant, Translate, and
  Settings in a consistent order, opening to Navigate.
- **Complete — iOS and Android:** Downloadable Gemma 4 E2B/E4B LiteRT-LM models rather
  than a bundled LLM.
- **Complete — iOS and Android:** Offline multimodal analysis with optional user-provided
  online providers and consent/fallback behavior.
- **Complete — iOS and Android:** Voice-directed visual questions in Navigate.
- **Complete — iOS and Android:** Multi-turn Assistant with text, voice, image, camera,
  PDF, and text-document inputs.
- **Complete — iOS and Android:** OCR-based Read and Translate workflows.
- **Complete — iOS and Android:** Real-time Safety object detection and coordinated TTS.
- **Complete — iOS:** LiDAR/ARKit depth observations where supported.
- **Complete — Android:** ARCore depth observations where supported.
- **Complete — iOS and Android:** Structured object/range observations can inform
  Navigate LLM prompts.

### Important gaps

- Instant continuous text
- Guided page alignment and automatic capture
- Crop, deskew, enhancement, and multi-page scanning
- Structured document navigation, library, search, and export
- Barcode and QR scanning
- Product lookup
- Magnification and low-vision display filters
- Color and light utilities
- Purpose-built object finder and frozen-image spatial exploration
- Share extensions/intents
- Dedicated currency model packs
- Optional familiar-face enrollment
- Robust handwriting, columns, tables, and forms
- Broader downloadable OCR, translation, and TTS language support
- User-customizable tool ordering and first-use tutorials

## Feasibility

Approximately 70–80 percent of meaningful phone-app parity can work entirely or
substantially offline using Apple Vision/VisionKit, Android ML Kit, the existing object
detector, platform camera APIs, and the current on-device LLM.

The most difficult capabilities are not primarily UI work:

- Product identification requires a maintained regional or global product database.
- Reliable currency identification requires curated, licensed, and extensively tested
  denomination datasets.
- Familiar-face recognition requires a custom embedding model, conservative thresholds,
  enrollment UX, encrypted local storage, and explicit privacy controls.
- High-quality document layout, tables, forms, and handwriting require substantial
  platform-specific recognition and reading-order work.

Broad phone-app parity is feasible. Matching the competitors' accumulated data coverage,
localization, recognition quality, and accessibility refinement is a longer-term quality
program rather than a single release.

## Phase 1 — Essential daily utilities

### 1A. Instant offline utilities

- **Implemented; device validation pending — iOS and Android:** Center-color identification
  from the live camera.
- **Implemented; device validation pending — iOS and Android:** Light-level measurement
  from the live camera.
- **Planned:** Continuous optional light tone and haptic feedback.
- **Planned:** Magnifier with zoom, freeze, focus lock, flashlight, inversion, contrast,
  and grayscale.
- **Planned:** Offline barcode and QR recognition with alignment feedback.
- **Planned:** Optional online product lookup with source and freshness disclosure.
- **Planned:** Share-to-EchoSense from Photos, Files, browsers, email, and messaging apps.

Color results describe camera-observed color, not a calibrated physical measurement.
Lighting, reflections, white balance, and camera exposure can change the result. Light
level is relative to the current camera exposure and must not be presented as a calibrated
lux reading.

Color uses a small center target and reports the dominant sampled color instead of averaging
contrasting pixels together. This avoids turning black text and white paper into an artificial
gray result, while still smoothing individual noisy pixels.

### 1B. Instant Text

- **Planned:** Continuous on-device OCR with stability filtering and duplicate suppression.
- **Planned:** Spoken focus/alignment guidance.
- **Planned:** Pause/freeze and explore recognized text.
- **Planned:** Language selection and automatic language/script reporting.

### 1C. Guided documents

- **Planned:** Spoken page-edge and stability guidance.
- **Planned:** Automatic capture when a complete page is stable.
- **Planned:** Crop, rotate, deskew, shadow cleanup, and contrast enhancement.
- **Planned:** Multi-page sessions with page reorder/delete/rescan.
- **Planned:** Structured navigation by page, heading, paragraph, sentence, and word.
- **Planned:** Accessible local document library, search, rename, delete confirmation,
  and export.
- **Planned:** Offline and optional online questions over saved documents.

## Phase 2 — Find and explore

- **Planned:** Selectable supported-object finder.
- **Planned:** Left/center/right speech, stereo tone, and haptic direction.
- **Planned:** Depth-assisted range where hardware and AR tracking support it.
- **Planned:** Person count and approximate direction.
- **Planned:** Frozen-image touch exploration.
- **Planned:** Event-triggered ambient scene descriptions.

The real-time loop should use deterministic detection. The LLM should run on user request
or a material scene event rather than continuously, to control heat, battery use, latency,
and speech congestion.

## Phase 3 — Recognition quality and personalization

- **Research:** Downloadable regional currency packs with the LLM as fallback.
- **Research:** Better handwriting, columns, tables, forms, and reading order.
- **Planned:** Downloadable OCR, translation, and TTS language packs.
- **Planned:** Customizable tool ordering, shortcuts, and accessible tutorials.
- **Research:** Opt-in familiar-face enrollment and local recognition.

Age and gender estimation are intentionally deferred because they provide limited
assistive value while introducing accuracy, bias, privacy, and reputational risks.

## Phase 4 — Service-dependent capabilities

- **Research:** Product database providers and regional offline caches.
- **Deferred:** Cross-device document synchronization.
- **Deferred:** Community or remote-human assistance.
- **Deferred:** Wearable and headset integrations.

## Shared architecture work

All new perception features should converge on a shared observation snapshot:

```text
Camera or imported file
        |
OCR | barcode | objects | faces | color | luminance | depth
        |
Structured observations with source, confidence, time, and bounds
        |
Instant tool output | on-device LLM | consented cloud LLM
        |
Prioritized speech, haptic, and visual output coordinator
```

Required cross-cutting work:

- One capability registry for installed models, downloaded language packs, sensors, and
  offline availability.
- One prioritized speech/haptic coordinator. User-requested results take precedence over
  ambient announcements; urgent Safety output must be concise and must not destroy a
  requested result.
- Observation confidence and provenance in prompts and UI.
- Graceful behavior for denied permissions, missing Play services, unsupported sensors,
  offline providers, low memory, thermal pressure, and app interruption.
- Local encryption for saved documents, provider keys, and any future face embeddings.
- Opt-in diagnostics that never collect images, documents, recognized text, or prompts by
  default.

## Accessibility acceptance criteria

Every feature must be tested with VoiceOver and TalkBack and must provide:

- A unique spoken label, current value/state, and useful action hint.
- No dependence on color, animation, or visual geometry alone.
- Logical focus order and stable focus during live updates.
- Support for large text, display scaling, and horizontal scrolling where controls do not
  fit.
- Actionable alignment guidance such as “move left,” “top edge missing,” and “hold
  steady.”
- Predictable Stop behavior for speech, capture, recognition, and generation.
- Recovery after calls, audio interruptions, backgrounding, camera denial, and network
  loss.
- Accessible confirmation before destructive actions.

## Delivery milestones

1. **Phase 1A foundation:** Color and Light on both platforms, then Magnifier.
2. **Phase 1A scanning:** Barcode/QR and share extensions/intents.
3. **Phase 1B:** Instant Text with stability and focus guidance.
4. **Phase 1C:** Guided capture, batch documents, Reader, Library, and export.
5. **Phase 2:** Finder and spatial Explore.
6. **Phase 3:** Currency packs, document intelligence, languages, and personalization.

## Maintenance checklist

Whenever a capability changes:

1. Update the `Last updated` date.
2. Change its platform status above.
3. Record meaningful offline, hardware, privacy, and accuracy limitations.
4. Update both platform README files if the user-visible capability changed.
5. Add or update accessibility semantics and acceptance tests.
6. Run platform builds and relevant tests before marking the capability **Complete**.
