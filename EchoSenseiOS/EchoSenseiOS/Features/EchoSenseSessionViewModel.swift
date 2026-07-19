import AVFoundation
import Foundation
import OSLog
import UIKit

@MainActor
final class EchoSenseSessionViewModel: NSObject, ObservableObject {
  private static let logger = Logger(subsystem: "com.terranet.echosense.ios", category: "AnalysisLifecycle")

  @Published var feature: EchoSenseFeature
  @Published var isModelReady = false
  @Published var isAnalyzing = false
  @Published var isListeningForVoiceCommand = false
  @Published var analysisStage = ""
  @Published var voiceCommandText = ""
  @Published var transcript = ""
  @Published var activeModelName: String?
  @Published var errorMessage: String?
  @Published var proximityAlert: ProximityAlert?
  @Published var proximityBoxes: [DetectionBox] = []
  @Published var collisionAvoidanceEnabled = false
  @Published var collisionAvoidanceAvailable = false
  @Published var isLocalModelReady = false
  @Published var hasStagedLocalModel = false

  let camera = CameraFrameSource()

  private let speech = SpeechOutputService()
  private let voiceCommands = VoiceCommandService()
  private let ocrService: OCRService = VisionOCRService()
  private let localLLM: LocalLLMClient = LiteRTLMClient()
  private lazy var translationService: TranslationService = LocalTranslationService(localLLM: localLLM)
  private let documentTextExtractor = DocumentTextExtractor()
  private let objectDetector: ObjectDetectionService = MediaPipeObjectDetectionService.shared
  private let localModelStore = LocalModelStore()

  private var latestSampleBuffer: CMSampleBuffer?
  private var lastAlertTime: Date = .distantPast
  private var lastAlertBearing: Bearing?
  private var lastAlertSeverity: ProximitySeverity?
  private var lastDetectionSubmitTime: Date = .distantPast
  private var lastDetectionTimestampMilliseconds = 0
  private var hasAnnouncedStartupStatus = false
  private var isActive = false
  private var startupAnnouncementTask: Task<Void, Never>?
  private var detectorPrepareTask: Task<Void, Never>?
  private var analysisTask: Task<Void, Never>?
  private var localModelLoadTask: Task<Void, Never>?
  private var hasAttemptedPersistedModelLoad = false
  private var stagedLocalModelURL: URL?
  private var settings: AppSettings?

  init(feature: EchoSenseFeature) {
    self.feature = feature
    super.init()
    camera.delegate = self
    collisionAvoidanceAvailable = objectDetector.isAvailable
    isModelReady = feature != .navigation || objectDetector.isAvailable
  }

  func onAppear(settings: AppSettings) {
    self.settings = settings
    isActive = true
    localLLM.diagnosticHandler = { [weak self] stage in
      Task { @MainActor in
        guard self?.isActive == true else { return }
        self?.analysisStage = stage
      }
    }
    speech.setVoiceIdentifier(settings.selectedVoiceIdentifier)
    rememberPersistedLocalModelIfNeeded()
    objectDetector.onLiveDetections = { [weak self] boxes in
      Task { @MainActor in
        guard self?.isActive == true else { return }
        self?.handleLiveDetections(boxes)
      }
    }
    if feature.supportsCollisionAvoidance {
      collisionAvoidanceEnabled = objectDetector.isAvailable
      prepareObjectDetector()
      scheduleFallbackStartupAnnouncement()
    }
    camera.requestPermissionAndStart()
  }

  func selectFeature(_ newFeature: EchoSenseFeature) {
    guard feature != newFeature else { return }

    feature = newFeature
    voiceCommands.stop()
    isListeningForVoiceCommand = false
    voiceCommandText = ""
    transcript = ""
    errorMessage = nil
    proximityAlert = nil
    proximityBoxes = []
    activeModelName = nil
    lastAlertTime = .distantPast
    lastAlertBearing = nil
    lastAlertSeverity = nil
    hasAnnouncedStartupStatus = false
    collisionAvoidanceAvailable = objectDetector.isAvailable
    collisionAvoidanceEnabled = newFeature.supportsCollisionAvoidance && objectDetector.isAvailable
    isLocalModelReady = localLLM.isReady
    isModelReady = modelReady(for: newFeature)

    if newFeature.supportsCollisionAvoidance {
      prepareObjectDetector()
    } else {
      startupAnnouncementTask?.cancel()
    }
    announceAccessibility("\(newFeature.title) selected.")
  }

  func toggleCollisionAvoidance() {
    collisionAvoidanceEnabled.toggle()
    if !collisionAvoidanceEnabled {
      proximityBoxes = []
      proximityAlert = nil
    }
    announceAccessibility("Collision avoidance \(collisionAvoidanceEnabled ? "on" : "off").")
  }

  func onDisappear() {
    Self.logger.notice("View disappeared; cancelling active work")
    isActive = false
    startupAnnouncementTask?.cancel()
    detectorPrepareTask?.cancel()
    analysisTask?.cancel()
    voiceCommands.stop()
    isListeningForVoiceCommand = false
    localLLM.cancelGeneration()
    localLLM.diagnosticHandler = nil
    localModelLoadTask?.cancel()
    objectDetector.onLiveDetections = nil
    latestSampleBuffer = nil
    proximityBoxes = []
    proximityAlert = nil
    camera.stop()
    speech.stop()
  }

  func pauseCameraForModal() {
    cancelStartupAnnouncement()
    camera.stop()
  }

  func resumeCameraAfterModal() {
    camera.requestPermissionAndStart()
  }

  func analyze(settings: AppSettings, customPrompt: String? = nil, forceOnline: Bool = false) {
    Self.logger.notice("Analyze button pressed; isAnalyzing=\(self.isAnalyzing, privacy: .public)")
    guard !isAnalyzing else { return }
    beginUserInitiatedWork()
    analysisStage = "Capturing camera frame"
    Self.logger.notice("Converting latest camera frame to UIImage")
    guard let sampleBuffer = latestSampleBuffer,
          let image = camera.captureCurrentFrameImage(from: sampleBuffer) else {
      errorMessage = "Camera frame is not ready."
      announceAccessibility("Camera frame is not ready.")
      return
    }
    Self.logger.notice("Camera frame captured: \(Int(image.size.width), privacy: .public)x\(Int(image.size.height), privacy: .public)")
    analysisStage = "Starting analysis task"

    analysisTask = Task {
      await analyze(image: image, settings: settings, customPrompt: customPrompt, forceOnline: forceOnline)
    }
  }

  func toggleVoiceCommand(settings: AppSettings) {
    guard feature == .navigation, !isAnalyzing else { return }
    if isListeningForVoiceCommand {
      // A second tap means the user has finished speaking. Submit the latest
      // partial transcription instead of discarding it as a cancellation.
      analysisStage = "Finishing voice command"
      voiceCommands.finishListening()
      return
    }

    speech.stop()
    errorMessage = nil
    voiceCommandText = ""
    analysisStage = "Listening for voice command"
    isListeningForVoiceCommand = true
    announceAccessibility("Listening for voice command.")

    voiceCommands.start(
      onPartialResult: { [weak self] text in
        self?.voiceCommandText = text
        self?.analysisStage = "Listening: \(text)"
      },
      onResult: { [weak self] command in
        guard let self else { return }
        self.isListeningForVoiceCommand = false
        self.voiceCommandText = command
        self.analysisStage = "Voice command: \(command)"
        self.announceAccessibility("Command recognized. Analyzing scene.")
        self.analyze(settings: settings, customPrompt: command)
      },
      onError: { [weak self] error in
        guard let self else { return }
        self.isListeningForVoiceCommand = false
        self.analysisStage = ""
        self.errorMessage = error.localizedDescription
        self.speech.announce(error.localizedDescription)
        self.announceAccessibility(error.localizedDescription)
      }
    )
  }

  func stopCurrentAction() {
    Self.logger.notice("Stop button pressed at stage: \(self.analysisStage, privacy: .public)")
    if isListeningForVoiceCommand {
      if voiceCommandText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        voiceCommands.stop()
        isListeningForVoiceCommand = false
        analysisStage = ""
        announceAccessibility("Voice command cancelled.")
      } else {
        analysisStage = "Finishing voice command"
        voiceCommands.finishListening()
      }
      return
    }

    analysisTask?.cancel()
    voiceCommands.stop()
    isListeningForVoiceCommand = false
    localLLM.cancelGeneration()
    startupAnnouncementTask?.cancel()
    speech.stop()
    announceAccessibility("Stopped.")
  }

  func analyze(
    image: UIImage,
    settings: AppSettings,
    customPrompt: String? = nil,
    forceOnline: Bool = false
  ) async {
    Self.logger.notice("Analysis task entered for feature: \(self.feature.rawValue, privacy: .public)")
    isAnalyzing = true
    transcript = ""
    defer {
      Self.logger.notice("Analysis task exited")
      isAnalyzing = false
      analysisStage = ""
    }

    do {
      switch feature {
      case .documentReader:
        try Task.checkCancellation()
        activeModelName = "Vision OCR"
        let text = try await ocrService.recognizeText(in: image)
        try Task.checkCancellation()
        transcript = text
        speakResult(text.isEmpty ? "No text found." : text, accessibilitySummary: text.isEmpty ? "No text found." : "Document text recognized.")

      case .documentTranslator:
        try Task.checkCancellation()
        activeModelName = "Vision OCR"
        let text = try await ocrService.recognizeText(in: image)
        try Task.checkCancellation()
        guard !text.isEmpty else {
          transcript = ""
          speech.announce("No text found.")
          return
        }

        do {
          let translated = try await translationService.translateToEnglish(text)
          transcript = translated
          speakResult(
            translated.isEmpty ? "No translated text found." : translated,
            accessibilitySummary: translated.isEmpty ? "No translated text found." : "Translation complete."
          )
        } catch TranslationServiceError.offlineTranslationUnavailable {
          transcript = "Recognized text:\n\n\(text)\n\nTranslation is not available yet."
          speech.announce("Text recognized. Offline translation is not available yet.")
          announceAccessibility("Text recognized. Offline translation is not available yet.")
        }

      case .currency:
        try Task.checkCancellation()
        let prompt = Prompts.currency(style: settings.responseStyle)
        try await runVisionAnalysis(
          prompt: prompt,
          image: image,
          settings: settings,
          forceOnline: forceOnline,
          fallbackSpeech: "Unable to identify currency."
        )

      case .navigation:
        try Task.checkCancellation()
        let prompt = Prompts.navigation(customPrompt: customPrompt, style: settings.responseStyle)
        try await runVisionAnalysis(
          prompt: prompt,
          image: image,
          settings: settings,
          forceOnline: forceOnline,
          fallbackSpeech: "Unable to analyze scene."
        )
      }
    } catch is CancellationError {
    } catch where Task.isCancelled {
    } catch {
      errorMessage = error.localizedDescription
      speech.announce(error.localizedDescription)
      announceAccessibility(error.localizedDescription)
    }
  }

  func analyzeDocument(url: URL, settings: AppSettings) {
    guard !isAnalyzing else { return }
    beginUserInitiatedWork()

    analysisTask = Task {
      let didStartAccessing = url.startAccessingSecurityScopedResource()
      defer {
        if didStartAccessing {
          url.stopAccessingSecurityScopedResource()
        }
      }

      await analyzeDocumentFromAccessibleURL(url, settings: settings)
    }
  }

  private func analyzeDocumentFromAccessibleURL(_ url: URL, settings: AppSettings) async {
    isAnalyzing = true
    transcript = ""
    errorMessage = nil
    defer { isAnalyzing = false }

    do {
      activeModelName = "Vision OCR"
      let recognizedText = try await recognizedText(from: url)
      try Task.checkCancellation()
      guard !recognizedText.isEmpty else {
        transcript = ""
        speech.announce("No text found.")
        announceAccessibility("No text found.")
        return
      }

      switch feature {
      case .documentReader:
        transcript = recognizedText
        speakResult(recognizedText, accessibilitySummary: "Document text recognized.")
      case .documentTranslator:
        do {
          let translated = try await translationService.translateToEnglish(recognizedText)
          transcript = translated
          speakResult(
            translated.isEmpty ? "No translated text found." : translated,
            accessibilitySummary: translated.isEmpty ? "No translated text found." : "Translation complete."
          )
        } catch TranslationServiceError.offlineTranslationUnavailable {
          transcript = "Recognized text:\n\n\(recognizedText)\n\nTranslation is not available yet."
          speech.announce("Text recognized. Offline translation is not available yet.")
          announceAccessibility("Text recognized. Offline translation is not available yet.")
        }
      case .navigation, .currency:
        break
      }
    } catch is CancellationError {
    } catch {
      errorMessage = error.localizedDescription
      speech.announce(error.localizedDescription)
      announceAccessibility(error.localizedDescription)
    }
  }

  private func recognizedText(from url: URL) async throws -> String {
    let pathExtension = url.pathExtension.lowercased()
    if pathExtension == "pdf" {
      if let pages = documentTextExtractor.extractText(from: url) {
        return pages.joined(separator: "\n\n")
      }

      let pageImages = documentTextExtractor.renderPages(from: url)
      let pageTexts = try await pageImages.asyncMap { image in
        try Task.checkCancellation()
        return try await ocrService.recognizeText(in: image)
      }
      return pageTexts.joined(separator: "\n\n").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    if let image = UIImage(contentsOfFile: url.path) {
      return try await ocrService.recognizeText(in: image)
    }

    return try String(contentsOf: url, encoding: .utf8)
      .trimmingCharacters(in: .whitespacesAndNewlines)
  }

  func importModel(url: URL) {
    importLocalModel(url: url)
  }

  func importLocalModel(url: URL) {
    beginUserInitiatedWork()
    isAnalyzing = true
    transcript = "Importing local model..."
    localModelLoadTask?.cancel()
    localModelLoadTask = Task {
      do {
        let storedURL = try localModelStore.importModel(from: url)
        try Task.checkCancellation()
        stagedLocalModelURL = storedURL
        hasStagedLocalModel = true
        try await loadLocalModel(from: storedURL)
      } catch is CancellationError {
      } catch {
        errorMessage = error.localizedDescription
        transcript = ""
        speech.announce(error.localizedDescription)
        announceAccessibility(error.localizedDescription)
      }
      isAnalyzing = false
    }
  }

  func useInstalledModel(at url: URL) {
    guard stagedLocalModelURL != url || !localLLM.isReady else { return }
    stagedLocalModelURL = url
    hasStagedLocalModel = true
    activeModelName = url.lastPathComponent
    localModelLoadTask?.cancel()
    localModelLoadTask = Task {
      do {
        try await loadLocalModel(from: url)
      } catch is CancellationError {
      } catch {
        errorMessage = error.localizedDescription
        announceAccessibility(error.localizedDescription)
      }
    }
  }

  func loadStagedLocalModel() {
    guard let stagedLocalModelURL else {
      transcript = "No imported local model is available."
      speech.announce(transcript)
      announceAccessibility(transcript)
      return
    }

    beginUserInitiatedWork()
    isAnalyzing = true
    transcript = "Loading local model..."
    localModelLoadTask?.cancel()
    localModelLoadTask = Task {
      do {
        try await loadLocalModel(from: stagedLocalModelURL)
      } catch is CancellationError {
      } catch {
        errorMessage = error.localizedDescription
        transcript = ""
        speech.announce(error.localizedDescription)
        announceAccessibility(error.localizedDescription)
      }
      isAnalyzing = false
    }
  }

  private func runLocalLLM(prompt: String, image: UIImage, fallbackSpeech: String) async throws {
    try Task.checkCancellation()
    if !localLLM.isReady {
      throw LocalLLMError.modelNotLoaded
    }

    let chunker = StreamingSentenceChunker()
    let stream = try await localLLM.generate(prompt: prompt, image: image)
    for try await partial in stream {
      try Task.checkCancellation()
      transcript += partial
      
      let sentences = chunker.onToken(partial)
      for sentence in sentences {
        speech.queue(sentence)
      }
    }

    let remaining = chunker.onDone()
    for sentence in remaining {
      speech.queue(sentence)
    }

    let spoken = transcript.trimmingCharacters(in: .whitespacesAndNewlines)
    if spoken.isEmpty {
      speech.announce(fallbackSpeech)
    }
    announceAccessibility(spoken.isEmpty ? fallbackSpeech : "Analysis complete.")
  }

  private func runVisionAnalysis(
    prompt: String,
    image: UIImage,
    settings: AppSettings,
    forceOnline: Bool,
    fallbackSpeech: String
  ) async throws {
    let canUseCloud = settings.isCloudConnected && settings.cloudConsentGranted
    if forceOnline || (canUseCloud && settings.cloudUsageMode == .preferOnline) {
      try await runCloudAnalysis(prompt: prompt, image: image, settings: settings)
      return
    }

    do {
      try await runLocalLLM(prompt: prompt, image: image, fallbackSpeech: fallbackSpeech)
      await speech.waitUntilFinished()
      try Task.checkCancellation()
      settings.recordSuccessfulLocalAnalysis()
    } catch is CancellationError {
      throw CancellationError()
    } catch {
      if canUseCloud && settings.cloudUsageMode == .automaticFallback {
        try await runCloudAnalysis(prompt: prompt, image: image, settings: settings)
      } else {
        throw error
      }
    }
  }

  private func runCloudAnalysis(prompt: String, image: UIImage, settings: AppSettings) async throws {
    guard settings.isCloudConnected else { throw CloudVisionError.invalidKey }
    try Task.checkCancellation()
    analysisStage = "Sending image for online analysis"
    activeModelName = settings.cloudProvider.displayName
    let result = try await CloudVisionClient(
      provider: settings.cloudProvider,
      apiKey: settings.cloudAPIKey
    ).analyze(image: image, prompt: prompt)
    try Task.checkCancellation()
    transcript = result
    speakResult(result, accessibilitySummary: "Online analysis complete.")
  }

  private func beginUserInitiatedWork() {
    cancelStartupAnnouncement()
    speech.stop()
    analysisTask?.cancel()
    localLLM.cancelGeneration()
    errorMessage = nil
  }

  private func cancelStartupAnnouncement() {
    startupAnnouncementTask?.cancel()
    startupAnnouncementTask = nil
    hasAnnouncedStartupStatus = true
  }

  private func announceStartupStatus() {
    guard isActive else { return }
    guard !hasAnnouncedStartupStatus else { return }
    hasAnnouncedStartupStatus = true
    if isModelReady {
      speech.announce("Ready.")
      announceAccessibility("Ready.")
    } else {
      speech.queue("Model loading. Wait for ready.")
      announceAccessibility("Model loading. Wait for ready.")
    }
  }

  private func scheduleFallbackStartupAnnouncement() {
    startupAnnouncementTask?.cancel()
    startupAnnouncementTask = Task { @MainActor in
      try? await Task.sleep(nanoseconds: 1_500_000_000)
      guard !Task.isCancelled else { return }
      announceStartupStatus()
    }
  }

  func reloadDetector(model: ObjectDetectorModel) {
    prepareObjectDetector()
  }

  private func prepareObjectDetector() {
    detectorPrepareTask?.cancel()
    detectorPrepareTask = Task {
      await objectDetector.prepare()
      await MainActor.run {
        guard isActive, !Task.isCancelled else { return }
        collisionAvoidanceAvailable = objectDetector.isAvailable
        collisionAvoidanceEnabled = feature.supportsCollisionAvoidance && objectDetector.isAvailable
        isLocalModelReady = localLLM.isReady
        isModelReady = modelReady(for: feature)
        if objectDetector.isAvailable {
          activeModelName = objectDetector.modelName
          if !hasAnnouncedStartupStatus {
            announceStartupStatus()
          }
        } else {
          activeModelName = "GenAI-only build"
          isModelReady = modelReady(for: feature)
        }
      }
    }
  }

  private func rememberPersistedLocalModelIfNeeded() {
    guard !hasAttemptedPersistedModelLoad else { return }
    hasAttemptedPersistedModelLoad = true
    guard let modelURL = localModelStore.importedModelURL else { return }
    stagedLocalModelURL = modelURL
    hasStagedLocalModel = true
    activeModelName = modelURL.lastPathComponent
  }

  private func loadLocalModel(from modelURL: URL) async throws {
    try await localLLM.load(modelURL: modelURL)
    try Task.checkCancellation()
    isLocalModelReady = true
    isModelReady = modelReady(for: feature)
    activeModelName = modelURL.lastPathComponent
    transcript = "Local model ready."
    speech.announce("Ready.")
    announceAccessibility("Model ready.")
  }

  private func modelReady(for feature: EchoSenseFeature) -> Bool {
    switch feature {
    case .navigation:
      return localLLM.isReady || objectDetector.isAvailable
    case .currency:
      return localLLM.isReady
    case .documentReader, .documentTranslator:
      return true
    }
  }

  private func speakResult(_ text: String, accessibilitySummary: String? = nil) {
    let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !clean.isEmpty else { return }

    if clean.count <= 800 {
      speech.announce(clean)
      announceAccessibility(accessibilitySummary ?? clean)
      return
    }

    speech.announce(String(clean.prefix(800)))
    announceAccessibility(accessibilitySummary ?? "Long result available on screen.")
    transcript = clean
  }

  private func announceAccessibility(_ text: String) {
    guard UIAccessibility.isVoiceOverRunning || UIAccessibility.isSwitchControlRunning else { return }
    UIAccessibility.post(notification: .announcement, argument: text)
  }

  private func handleLiveDetections(_ boxes: [DetectionBox]) {
    guard collisionAvoidanceAvailable, collisionAvoidanceEnabled else {
      proximityBoxes = []
      proximityAlert = nil
      return
    }
    proximityBoxes = boxes
    guard let best = boxes.max(by: { $0.height < $1.height }) else {
      proximityAlert = nil
      return
    }

    let relativeDepth = 1 - min(1, Float(best.height) / Float(max(1, best.imageHeight)))
    let alert = ProximityAlert(
      label: best.label.isEmpty ? "Obstacle" : best.label,
      severity: severity(forRelativeDepth: relativeDepth),
      bearing: bearing(fromCenterXNormalized: best.centerXNormalized),
      relativeDepth: relativeDepth
    )

    proximityAlert = alert
    maybeSpeakProximity(alert)
  }

  private func maybeSpeakProximity(_ alert: ProximityAlert) {
    let elapsed = Date().timeIntervalSince(lastAlertTime)
    let sameBearing = alert.bearing == lastAlertBearing
    let sameSeverity = alert.severity == lastAlertSeverity
    let escalated = alert.severity.rawValue > (lastAlertSeverity?.rawValue ?? -1)

    if !escalated {
      if sameBearing && sameSeverity && elapsed < 2.5 { return }
      if !sameBearing && elapsed < 1.2 { return }
    }

    let label = alert.label.isEmpty ? "Obstacle" : alert.label
    let phrase: String
    switch (alert.severity, alert.bearing) {
    case (.urgent, .left): phrase = "Stop. \(label) left."
    case (.urgent, .center): phrase = "Stop. \(label) ahead."
    case (.urgent, .right): phrase = "Stop. \(label) right."
    case (.warning, .left): phrase = "Caution, \(label) left."
    case (.warning, .center): phrase = "Caution, \(label) ahead."
    case (.warning, .right): phrase = "Caution, \(label) right."
    case (.info, .left): phrase = "\(label) left."
    case (.info, .center): phrase = "\(label) ahead."
    case (.info, .right): phrase = "\(label) right."
    }

    speech.announce(phrase)
    announceAccessibility(phrase)
    lastAlertTime = Date()
    lastAlertBearing = alert.bearing
    lastAlertSeverity = alert.severity
  }
}

private extension Array {
  func asyncMap<T>(_ transform: (Element) async throws -> T) async throws -> [T] {
    var values: [T] = []
    values.reserveCapacity(count)
    for element in self {
      values.append(try await transform(element))
    }
    return values
  }
}

extension EchoSenseSessionViewModel: CameraFrameSourceDelegate {
  nonisolated func cameraFrameSource(_ source: CameraFrameSource, authorizationDidChange status: AVAuthorizationStatus) {
    Task { @MainActor in
      switch status {
      case .authorized:
        break
      case .denied, .restricted:
        errorMessage = "Camera permission is required."
        speech.announce("Camera permission is required.")
        announceAccessibility("Camera permission is required.")
      case .notDetermined:
        break
      @unknown default:
        break
      }
    }
  }

  nonisolated func cameraFrameSourceDidStart(_ source: CameraFrameSource) {
    Task { @MainActor in
      try? await Task.sleep(nanoseconds: 350_000_000)
      guard isActive, feature.supportsCollisionAvoidance else { return }
      announceStartupStatus()
    }
  }

  nonisolated func cameraFrameSource(_ source: CameraFrameSource, didOutput sampleBuffer: CMSampleBuffer) {
    Task { @MainActor in
      guard isActive else { return }
      latestSampleBuffer = sampleBuffer
      if feature.supportsCollisionAvoidance && collisionAvoidanceAvailable && collisionAvoidanceEnabled {
        guard shouldSubmitDetectionFrame() else { return }
        let timestamp = nextDetectionTimestampMilliseconds(for: sampleBuffer)
        objectDetector.detectLive(sampleBuffer: sampleBuffer, timestampInMilliseconds: timestamp)
      }
    }
  }

  private func shouldSubmitDetectionFrame() -> Bool {
    let now = Date()
    guard now.timeIntervalSince(lastDetectionSubmitTime) >= 0.15 else { return false }
    lastDetectionSubmitTime = now
    return true
  }

  private func nextDetectionTimestampMilliseconds(for sampleBuffer: CMSampleBuffer) -> Int {
    let presentationTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
    let candidate: Int
    if presentationTime.isValid && !presentationTime.seconds.isNaN {
      candidate = Int(presentationTime.seconds * 1000)
    } else {
      candidate = Int(Date().timeIntervalSince1970 * 1000)
    }

    if candidate <= lastDetectionTimestampMilliseconds {
      lastDetectionTimestampMilliseconds += 1
    } else {
      lastDetectionTimestampMilliseconds = candidate
    }
    return lastDetectionTimestampMilliseconds
  }
}
