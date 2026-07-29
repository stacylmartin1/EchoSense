/*
 * Copyright 2026 TerraNet Technologies LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import AVFoundation
import Foundation
import Network
import OSLog
import UIKit
import UniformTypeIdentifiers

struct AssistantChatMessage: Identifiable, Equatable {
  enum Role: String {
    case user
    case assistant
  }

  let id: UUID
  let role: Role
  var text: String

  init(id: UUID = UUID(), role: Role, text: String) {
    self.id = id
    self.role = role
    self.text = text
  }
}

enum AssistantModelMode: String {
  case onDevice
  case online
}

enum AssistantChatError: LocalizedError {
  case onlineProviderNotConnected
  case internetUnavailable

  var errorDescription: String? {
    switch self {
    case .onlineProviderNotConnected:
      "Connect an online provider before using online chat."
    case .internetUnavailable:
      "Online chat is unavailable while this device is offline. Choose On-device to continue."
    }
  }
}

@MainActor
final class EchoSenseSessionViewModel: NSObject, ObservableObject {
  private static let logger = Logger(subsystem: "com.terranet.echosense.ios", category: "AnalysisLifecycle")

  @Published var feature: EchoSenseFeature
  @Published var isModelReady = false
  @Published var isAnalyzing = false
  @Published var isListeningForVoiceCommand = false
  @Published var pendingNavigationVoiceCommand: String?
  @Published var analysisStage = ""
  @Published var voiceCommandText = ""
  @Published var transcript = ""
  @Published var activeModelName: String?
  @Published var errorMessage: String?
  @Published var proximityAlert: ProximityAlert?
  @Published var proximityBoxes: [DetectionBox] = []
  @Published var collisionAvoidanceEnabled = false
  @Published var collisionAvoidanceAvailable = false
  @Published var depthSensingDescription = "Camera estimate"
  @Published var analysisSensorContext = ""
  @Published var isLocalModelReady = false
  @Published var hasStagedLocalModel = false
  @Published var assistantMessages: [AssistantChatMessage] = []
  @Published var assistantDraft = ""
  @Published var assistantAttachmentName: String?
  @Published var assistantAttachmentImage: UIImage?
  @Published var isListeningForAssistant = false
  @Published var assistantModelMode: AssistantModelMode = .onDevice
  @Published var instantTextEnabled = false
  @Published var instantTextPaused = false
  @Published var instantTextStatus = ""
  @Published var instantTextLanguage: OCRLanguageOption = .automatic
  @Published var guidedDocumentEnabled = false
  @Published var guidedDocumentStatus = ""
  @Published private(set) var isNetworkAvailable = false

  let camera = CameraFrameSource()

  private let speech = SpeechOutputService()
  private let voiceCommands = VoiceCommandService()
  private let ocrService: OCRService = VisionOCRService()
  private let documentCaptureService = VisionDocumentCaptureService()
  private let barcodeScanner: BarcodeScanning = VisionBarcodeScanner()
  private let localLLM: LocalLLMClient = LiteRTLMClient()
  private lazy var translationService: TranslationService = LocalTranslationService(localLLM: localLLM)
  private let documentTextExtractor = DocumentTextExtractor()
  private let objectDetector: ObjectDetectionService = MediaPipeObjectDetectionService.shared
  private let localModelStore = LocalModelStore()
  private let networkMonitor = NWPathMonitor()
  private let networkMonitorQueue = DispatchQueue(label: "com.terranet.echosense.network-monitor")

  private var latestSampleBuffer: CMSampleBuffer?
  private var lastAlertTime: Date = .distantPast
  private var lastAlertBearing: Bearing?
  private var lastAlertSeverity: ProximitySeverity?
  private var lastAlertDistanceMeters: Float?
  private var candidateAlert: ProximityAlert?
  private var candidateAlertSince: Date?
  private var lastDetectionSubmitTime: Date = .distantPast
  private var lastDetectionResultsTime: Date = .distantPast
  private var lastDetectionTimestampMilliseconds = 0
  private var latestDepthObservations: [DepthObservation] = []
  private var hasAnnouncedStartupStatus = false
  private var isActive = false
  private var startupAnnouncementTask: Task<Void, Never>?
  private var detectorPrepareTask: Task<Void, Never>?
  private var analysisTask: Task<Void, Never>?
  private var instantTextTask: Task<Void, Never>?
  private var guidedDocumentTask: Task<Void, Never>?
  private var localModelLoadTask: Task<Void, Never>?
  private var hasAttemptedPersistedModelLoad = false
  private var stagedLocalModelURL: URL?
  private var settings: AppSettings?
  private var safetyAnnouncementsSuspendedForAnalysis = false
  private var assistantDocumentText: String?
  private var hasInitializedAssistantModelMode = false
  private var lastInstantTextFrameTime: Date = .distantPast
  private var instantTextCandidate = ""
  private var instantTextCandidateCount = 0
  private var lastSpokenInstantText = ""
  private var lastInstantGuidanceTime: Date = .distantPast
  private var instantGuidanceCandidate = ""
  private var instantGuidanceCandidateCount = 0
  private var instantEmptyResultCount = 0
  private var lastGuidedDocumentFrameTime: Date = .distantPast
  private var guidedDocumentCandidate: DocumentQuadrilateral?
  private var guidedDocumentStableCount = 0
  private var guidedDocumentGuidanceCandidate = ""
  private var guidedDocumentGuidanceCount = 0
  private var lastGuidedDocumentAnnouncementTime: Date = .distantPast
  private var guidedDocumentManualCapturePending = false

  init(feature: EchoSenseFeature) {
    self.feature = feature
    super.init()
    camera.delegate = self
    collisionAvoidanceAvailable = objectDetector.isAvailable
    isModelReady = feature != .navigation || objectDetector.isAvailable
    networkMonitor.pathUpdateHandler = { [weak self] path in
      Task { @MainActor [weak self] in
        self?.isNetworkAvailable = path.status == .satisfied
      }
    }
    networkMonitor.start(queue: networkMonitorQueue)
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
    if feature == .assistant, !hasInitializedAssistantModelMode {
      hasInitializedAssistantModelMode = true
      assistantModelMode =
        settings.isCloudConnected && settings.cloudUsageMode == .preferOnline
        ? .online
        : .onDevice
    }
    rememberPersistedLocalModelIfNeeded()
    objectDetector.onLiveDetections = { [weak self] boxes in
      Task { @MainActor in
        guard self?.isActive == true else { return }
        self?.handleLiveDetections(boxes)
      }
    }
    if feature.supportsCollisionAvoidance {
      prepareObjectDetector()
    }
    if feature.usesLocalLLM {
      scheduleFallbackStartupAnnouncement()
    }
    camera.requestPermissionAndStart()
  }

  func selectFeature(_ newFeature: EchoSenseFeature) {
    guard feature != newFeature else { return }

    stopInstantText(announce: false)
    stopGuidedDocumentCapture(announce: false)
    feature = newFeature
    voiceCommands.stop()
    isListeningForVoiceCommand = false
    isListeningForAssistant = false
    voiceCommandText = ""
    pendingNavigationVoiceCommand = nil
    transcript = ""
    errorMessage = nil
    proximityAlert = nil
    proximityBoxes = []
    activeModelName = nil
    lastAlertTime = .distantPast
    lastAlertBearing = nil
    lastAlertSeverity = nil
    lastAlertDistanceMeters = nil
    candidateAlert = nil
    candidateAlertSince = nil
    hasAnnouncedStartupStatus = false
    collisionAvoidanceAvailable = objectDetector.isAvailable
    collisionAvoidanceEnabled = false
    isLocalModelReady = localLLM.isReady
    isModelReady = modelReady(for: newFeature)

    if newFeature.supportsCollisionAvoidance {
      prepareObjectDetector()
    }
    if newFeature.usesLocalLLM {
      scheduleFallbackStartupAnnouncement()
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
      latestDepthObservations = []
      lastDetectionResultsTime = .distantPast
      resetSafetyAnnouncementState()
    }
    announceAccessibility("Collision avoidance \(collisionAvoidanceEnabled ? "on" : "off").")
  }

  func onDisappear() {
    Self.logger.notice("View disappeared; cancelling active work")
    isActive = false
    startupAnnouncementTask?.cancel()
    detectorPrepareTask?.cancel()
    analysisTask?.cancel()
    stopInstantText(announce: false)
    stopGuidedDocumentCapture(announce: false)
    voiceCommands.stop()
    isListeningForVoiceCommand = false
    pendingNavigationVoiceCommand = nil
    isListeningForAssistant = false
    localLLM.cancelGeneration()
    localLLM.diagnosticHandler = nil
    localModelLoadTask?.cancel()
    objectDetector.onLiveDetections = nil
    latestSampleBuffer = nil
    proximityBoxes = []
    proximityAlert = nil
    latestDepthObservations = []
    lastDetectionResultsTime = .distantPast
    resetSafetyAnnouncementState()
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

  func analyze(
    settings: AppSettings,
    customPrompt: String? = nil,
    forceOnline: Bool = false,
    forceLocal: Bool = false
  ) {
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
      await analyze(
        image: image,
        settings: settings,
        customPrompt: customPrompt,
        forceOnline: forceOnline,
        forceLocal: forceLocal
      )
    }
  }

  func identifyCenterColor() {
    runVisualUtility(
      stage: "Identifying center color",
      analyze: VisualUtilityAnalyzer.centerColor,
      describe: \.spokenDescription
    )
  }

  func measureLightLevel() {
    runVisualUtility(
      stage: "Measuring light level",
      analyze: VisualUtilityAnalyzer.lightLevel,
      describe: \.spokenDescription
    )
  }

  func scanBarcodeOrQRCode() {
    guard feature == .documentReader, !isAnalyzing else { return }
    beginUserInitiatedWork()
    isAnalyzing = true
    analysisStage = "Focusing camera"
    activeModelName = "Vision barcode scanner"
    camera.requestCenterFocus()

    analysisTask = Task {
      defer {
        isAnalyzing = false
        analysisStage = ""
      }
      do {
        try await Task.sleep(for: .milliseconds(450))
        try Task.checkCancellation()
        guard let sampleBuffer = latestSampleBuffer,
              let image = camera.captureCurrentFrameImage(from: sampleBuffer) else {
          let message = "Camera frame is not ready."
          errorMessage = message
          speech.announce(message)
          announceAccessibility(message)
          return
        }
        analysisStage = "Scanning barcode or QR code"
        let codes = try await barcodeScanner.scan(image: image)
        try Task.checkCancellation()
        guard !codes.isEmpty else {
          let message = VisualUtilityAnalyzer.codeCaptureGuidance(in: image)
          transcript = message
          speech.announce(message)
          announceAccessibility(message)
          return
        }
        transcript = codes.map(\.displayDescription).joined(separator: "\n\n")
        let spoken = codes.map(\.spokenDescription).joined(separator: " ")
        speech.announce(spoken)
        announceAccessibility(
          codes.count == 1 ? "Code recognized." : "\(codes.count) codes recognized."
        )
      } catch is CancellationError {
      } catch {
        let message = "Unable to scan the code."
        errorMessage = message
        speech.announce(message)
        announceAccessibility(message)
      }
    }
  }

  func captureMagnifierFrame() -> UIImage? {
    guard let sampleBuffer = latestSampleBuffer else { return nil }
    return camera.captureCurrentFrameImage(from: sampleBuffer)
  }

  func setMagnifierTorch(enabled: Bool) {
    camera.setTorch(enabled: enabled)
  }

  func setMagnifierMode(enabled: Bool) {
    camera.setMagnifierMode(enabled: enabled)
  }

  func setMagnifierZoom(_ factor: Double) {
    camera.setMagnifierZoom(CGFloat(factor))
  }

  private func runVisualUtility<Result>(
    stage: String,
    analyze: (UIImage) -> Result?,
    describe: KeyPath<Result, String>
  ) {
    guard feature == .documentReader, !isAnalyzing else { return }
    beginUserInitiatedWork()
    analysisStage = stage
    activeModelName = "On-device camera"
    guard let sampleBuffer = latestSampleBuffer,
          let image = camera.captureCurrentFrameImage(from: sampleBuffer),
          let result = analyze(image) else {
      let message = "Camera frame is not ready."
      analysisStage = ""
      errorMessage = message
      speech.announce(message)
      announceAccessibility(message)
      return
    }

    let description = result[keyPath: describe]
    transcript = description
    analysisStage = ""
    speech.announce(description)
    announceAccessibility(description)
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
        self.pendingNavigationVoiceCommand = command
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

  func clearPendingNavigationVoiceCommand() {
    pendingNavigationVoiceCommand = nil
  }

  func toggleAssistantVoice(settings: AppSettings) {
    guard feature == .assistant, !isAnalyzing else { return }
    if isListeningForAssistant {
      voiceCommands.finishListening()
      return
    }

    guard isNetworkAvailable || voiceCommands.supportsOfflineRecognition else {
      let message = "Speech recognition for \(Locale.current.localizedString(forIdentifier: Locale.current.identifier) ?? "the current language") requires an internet connection."
      errorMessage = message
      speech.announce(message)
      announceAccessibility(message)
      return
    }

    speech.stop()
    errorMessage = nil
    isListeningForAssistant = true
    analysisStage = "Listening"
    announceAccessibility("Listening for an Assistant message.")
    voiceCommands.start(
      onPartialResult: { [weak self] text in
        self?.assistantDraft = text
        self?.analysisStage = "Listening: \(text)"
      },
      onResult: { [weak self] text in
        guard let self else { return }
        self.isListeningForAssistant = false
        self.assistantDraft = text
        self.analysisStage = ""
        self.sendAssistantMessage(settings: settings)
      },
      onError: { [weak self] error in
        guard let self else { return }
        self.isListeningForAssistant = false
        self.analysisStage = ""
        self.errorMessage = error.localizedDescription
        self.speech.announce(error.localizedDescription)
      }
    )
  }

  var assistantVoiceInputStatus: String {
    voiceCommands.supportsOfflineRecognition
      ? "Voice input: On-device"
      : "Voice input: Internet may be required"
  }

  func setAssistantModelMode(_ mode: AssistantModelMode) {
    guard !isAnalyzing else { return }
    assistantModelMode = mode
    activeModelName = mode == .online ? settings?.cloudProvider.displayName : "On-device model"
    errorMessage = nil
  }

  func captureAssistantCameraImage() {
    guard let sampleBuffer = latestSampleBuffer,
          let image = camera.captureCurrentFrameImage(from: sampleBuffer) else {
      errorMessage = "Camera frame is not ready."
      announceAccessibility("Camera frame is not ready.")
      return
    }
    assistantAttachmentImage = image
    assistantDocumentText = nil
    assistantAttachmentName = "Camera image"
    announceAccessibility("Camera image attached.")
  }

  func attachAssistantPhoto(data: Data, name: String = "Photo") {
    guard let image = UIImage(data: data) else {
      errorMessage = "The selected image could not be opened."
      return
    }
    assistantAttachmentImage = image
    assistantDocumentText = nil
    assistantAttachmentName = name
    announceAccessibility("\(name) attached.")
  }

  func attachAssistantFile(url: URL) {
    let didStartAccessing = url.startAccessingSecurityScopedResource()
    defer { if didStartAccessing { url.stopAccessingSecurityScopedResource() } }

    do {
      let type = UTType(filenameExtension: url.pathExtension.lowercased())
      if type?.conforms(to: .image) == true {
        attachAssistantPhoto(data: try Data(contentsOf: url), name: url.lastPathComponent)
      } else if type?.conforms(to: .pdf) == true {
        if let pages = documentTextExtractor.extractText(from: url) {
          assistantDocumentText = String(pages.joined(separator: "\n\n").prefix(16_000))
          assistantAttachmentImage = nil
          assistantAttachmentName = url.lastPathComponent
        } else if let firstPage = documentTextExtractor.renderPages(from: url, maxPages: 1).first {
          assistantAttachmentImage = firstPage
          assistantDocumentText = nil
          assistantAttachmentName = "\(url.lastPathComponent), page 1"
        } else {
          throw CocoaError(.fileReadCorruptFile)
        }
      } else {
        let text = try String(contentsOf: url, encoding: .utf8)
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
          throw CocoaError(.fileReadCorruptFile)
        }
        assistantDocumentText = String(text.prefix(16_000))
        assistantAttachmentImage = nil
        assistantAttachmentName = url.lastPathComponent
      }
      errorMessage = nil
      announceAccessibility("\(assistantAttachmentName ?? "Document") attached.")
    } catch {
      errorMessage = "The selected file could not be opened."
      announceAccessibility("The selected file could not be opened.")
    }
  }

  func removeAssistantAttachment() {
    assistantAttachmentName = nil
    assistantAttachmentImage = nil
    assistantDocumentText = nil
  }

  func newAssistantChat() {
    analysisTask?.cancel()
    localLLM.cancelGeneration()
    voiceCommands.stop()
    speech.stop()
    isAnalyzing = false
    isListeningForAssistant = false
    assistantMessages = []
    assistantDraft = ""
    removeAssistantAttachment()
    errorMessage = nil
    analysisStage = ""
    announceAccessibility("New Assistant chat.")
  }

  func sendAssistantMessage(settings: AppSettings) {
    let message = assistantDraft.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !message.isEmpty, !isAnalyzing else { return }
    beginUserInitiatedWork()
    assistantDraft = ""
    assistantMessages.append(.init(role: .user, text: message))
    isAnalyzing = true
    analysisStage = "Preparing response"

    analysisTask = Task { [weak self] in
      await self?.runAssistantTurn(settings: settings)
    }
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

    if instantTextEnabled {
      stopInstantText()
      speech.stop()
      return
    }
    if guidedDocumentEnabled || guidedDocumentTask != nil {
      stopGuidedDocumentCapture()
      speech.stop()
      return
    }

    analysisTask?.cancel()
    voiceCommands.stop()
    isListeningForVoiceCommand = false
    pendingNavigationVoiceCommand = nil
    isListeningForAssistant = false
    localLLM.cancelGeneration()
    startupAnnouncementTask?.cancel()
    speech.stop()
    announceAccessibility("Stopped.")
  }

  func toggleInstantText() {
    if instantTextEnabled {
      stopInstantText()
      return
    }
    guard feature == .documentReader else { return }
    stopGuidedDocumentCapture(announce: false)
    instantTextEnabled = true
    instantTextPaused = false
    instantTextStatus = "Scanning for text"
    instantTextCandidate = ""
    instantTextCandidateCount = 0
    lastSpokenInstantText = ""
    lastInstantGuidanceTime = .distantPast
    instantGuidanceCandidate = ""
    instantGuidanceCandidateCount = 0
    instantEmptyResultCount = 0
    lastInstantTextFrameTime = .distantPast
    speech.announce("Instant text on. Point the camera at text.")
    announceAccessibility("Instant text on. Point the camera at text.")
  }

  func toggleInstantTextPause() {
    guard instantTextEnabled else { return }
    instantTextPaused.toggle()
    instantTextStatus = instantTextPaused ? "Paused on recognized text" : "Scanning for text"
    let message = instantTextPaused ? "Instant text paused." : "Instant text resumed."
    speech.announce(message)
    announceAccessibility(message)
  }

  func setInstantTextLanguage(_ language: OCRLanguageOption) {
    instantTextLanguage = language
    instantTextCandidate = ""
    instantTextCandidateCount = 0
    lastSpokenInstantText = ""
    let message = "Instant Text language \(language.title)."
    speech.announce(message)
    announceAccessibility(message)
  }

  private func stopInstantText(announce: Bool = true) {
    guard instantTextEnabled || instantTextTask != nil else { return }
    instantTextTask?.cancel()
    instantTextTask = nil
    instantTextEnabled = false
    instantTextPaused = false
    instantTextStatus = ""
    instantTextCandidate = ""
    instantTextCandidateCount = 0
    instantEmptyResultCount = 0
    if announce {
      speech.announce("Instant text off.")
      announceAccessibility("Instant text off.")
    }
  }

  func toggleGuidedDocumentCapture() {
    if guidedDocumentEnabled {
      stopGuidedDocumentCapture()
      return
    }
    guard feature == .documentReader, !isAnalyzing else { return }
    stopInstantText(announce: false)
    guidedDocumentEnabled = true
    guidedDocumentStatus = "Looking for a complete page"
    guidedDocumentCandidate = nil
    guidedDocumentStableCount = 0
    guidedDocumentGuidanceCandidate = ""
    guidedDocumentGuidanceCount = 0
    guidedDocumentManualCapturePending = false
    lastGuidedDocumentAnnouncementTime = .distantPast
    lastGuidedDocumentFrameTime = .distantPast
    let message = "Guided scan on. Center one complete page in the camera view."
    speech.announce(message)
    announceAccessibility(message)
  }

  func captureGuidedDocumentManually() {
    guard guidedDocumentEnabled else { return }
    guard guidedDocumentTask == nil else {
      guidedDocumentManualCapturePending = true
      guidedDocumentStatus = "Manual capture requested. Hold steady"
      return
    }
    guidedDocumentManualCapturePending = false
    guidedDocumentTask = Task { [weak self] in
      guard let self else { return }
      defer { finishGuidedDocumentTask() }
      await captureGuidedDocument(manual: true)
    }
  }

  private func stopGuidedDocumentCapture(announce: Bool = true) {
    let wasActive = guidedDocumentEnabled || guidedDocumentTask != nil
    guidedDocumentTask?.cancel()
    guidedDocumentTask = nil
    guidedDocumentEnabled = false
    guidedDocumentStatus = ""
    guidedDocumentCandidate = nil
    guidedDocumentStableCount = 0
    guidedDocumentGuidanceCandidate = ""
    guidedDocumentGuidanceCount = 0
    guidedDocumentManualCapturePending = false
    if announce, wasActive {
      speech.announce("Guided scan off.")
      announceAccessibility("Guided scan off.")
    }
  }

  private func runAssistantTurn(settings: AppSettings) async {
    let responseID = UUID()
    assistantMessages.append(.init(id: responseID, role: .assistant, text: ""))
    defer {
      isAnalyzing = false
      analysisStage = ""
      if let index = assistantMessages.firstIndex(where: { $0.id == responseID }),
         assistantMessages[index].text.isEmpty {
        assistantMessages.remove(at: index)
      }
    }

    let prompt = assistantPrompt(excluding: responseID)
    let canUseCloud = settings.isCloudConnected && settings.cloudConsentGranted

    do {
      let response: String
      var responseWasSpokenWhileStreaming = false
      if assistantModelMode == .online {
        guard settings.isCloudConnected else { throw AssistantChatError.onlineProviderNotConnected }
        guard isNetworkAvailable else { throw AssistantChatError.internetUnavailable }
        response = try await runAssistantCloud(prompt: prompt, settings: settings)
      } else {
        do {
          response = try await runAssistantLocal(prompt: prompt, responseID: responseID)
          responseWasSpokenWhileStreaming = true
          settings.recordSuccessfulLocalAnalysis()
        } catch is CancellationError {
          throw CancellationError()
        } catch {
          if canUseCloud && settings.cloudUsageMode == .automaticFallback {
            guard isNetworkAvailable else { throw AssistantChatError.internetUnavailable }
            speech.stop()
            response = try await runAssistantCloud(prompt: prompt, settings: settings)
          } else {
            throw error
          }
        }
      }

      try Task.checkCancellation()
      if let index = assistantMessages.firstIndex(where: { $0.id == responseID }) {
        assistantMessages[index].text = response
      }
      transcript = response
      if !responseWasSpokenWhileStreaming {
        speech.announce(String(response.prefix(1_500)))
      }
      announceAccessibility("Assistant response received.")
      await speech.waitUntilFinished()
    } catch is CancellationError {
    } catch where Task.isCancelled {
    } catch {
      errorMessage = error.localizedDescription
      speech.announce(error.localizedDescription)
      announceAccessibility(error.localizedDescription)
    }
  }

  private func runAssistantLocal(prompt: String, responseID: UUID) async throws -> String {
    guard localLLM.isReady else { throw LocalLLMError.modelNotLoaded }
    activeModelName = "On-device model"
    analysisStage = "Generating on device"
    let stream = try await localLLM.generate(prompt: prompt, image: assistantAttachmentImage)
    var response = ""
    let chunker = StreamingSentenceChunker()
    for try await token in stream {
      try Task.checkCancellation()
      response += token
      if let index = assistantMessages.firstIndex(where: { $0.id == responseID }) {
        assistantMessages[index].text = response
      }
      for sentence in chunker.onToken(token) {
        speech.queue(sentence)
      }
    }
    for sentence in chunker.onDone() {
      speech.queue(sentence)
    }
    let clean = response.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !clean.isEmpty else { throw LocalLLMError.emptyResponse }
    return clean
  }

  private func runAssistantCloud(prompt: String, settings: AppSettings) async throws -> String {
    guard settings.isCloudConnected else { throw CloudVisionError.invalidKey }
    guard isNetworkAvailable else { throw AssistantChatError.internetUnavailable }
    activeModelName = settings.cloudProvider.displayName
    analysisStage = "Generating online"
    return try await CloudVisionClient(
      provider: settings.cloudProvider,
      apiKey: settings.cloudAPIKey
    ).generate(prompt: prompt, image: assistantAttachmentImage)
  }

  private func assistantPrompt(excluding responseID: UUID) -> String {
    let history = assistantMessages
      .filter { $0.id != responseID && !$0.text.isEmpty }
      .suffix(12)
      .map { "\($0.role == .user ? "User" : "Assistant"): \($0.text)" }
      .joined(separator: "\n\n")
    let documentContext = assistantDocumentText.map {
      "\n\n<ATTACHED_DOCUMENT>\n\($0)\n</ATTACHED_DOCUMENT>"
    } ?? ""
    let imageContext = assistantAttachmentImage == nil ? "" :
      "\nAn image is attached. Use it when answering relevant questions."
    return """
    You are EchoSense-AI Assistant, a concise, helpful assistant designed to work well with screen readers. Answer the user's latest request while using the conversation history and any attachment. Clearly state uncertainty. Do not claim that visual or document analysis is perfectly reliable. Use plain text and complete sentences.
    \(imageContext)\(documentContext)

    <CONVERSATION>
    \(history)
    </CONVERSATION>
    """
  }

  func analyze(
    image: UIImage,
    settings: AppSettings,
    customPrompt: String? = nil,
    forceOnline: Bool = false,
    forceLocal: Bool = false
  ) async {
    Self.logger.notice("Analysis task entered for feature: \(self.feature.rawValue, privacy: .public)")
    suspendSafetyAnnouncementsForAnalysis()
    isAnalyzing = true
    transcript = ""
    defer {
      Self.logger.notice("Analysis task exited")
      resumeSafetyAnnouncementsAfterAnalysis()
      isAnalyzing = false
      analysisStage = ""
    }

    do {
      switch feature {
      case .assistant:
        return
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
          forceLocal: forceLocal,
          fallbackSpeech: "Unable to identify currency."
        )

      case .navigation:
        try Task.checkCancellation()
        let sensorSnapshot = sensorPromptSnapshot()
        analysisSensorContext = sensorContextSummary(for: sensorSnapshot)
        let prompt = Prompts.navigation(customPrompt: customPrompt, style: settings.responseStyle)
          + sensorSnapshot
        try await runVisionAnalysis(
          prompt: prompt,
          image: image,
          settings: settings,
          forceOnline: forceOnline,
          forceLocal: forceLocal,
          fallbackSpeech: "Unable to analyze scene."
        )
      }
    } catch is CancellationError {
    } catch where Task.isCancelled {
    } catch {
      errorMessage = error.localizedDescription
      speech.announce(error.localizedDescription)
      announceAccessibility(error.localizedDescription)
      await speech.waitUntilFinished()
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
      case .assistant, .navigation, .currency:
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
    forceLocal: Bool,
    fallbackSpeech: String
  ) async throws {
    let canUseCloud = settings.isCloudConnected && settings.cloudConsentGranted
    if !forceLocal && (forceOnline || (canUseCloud && settings.cloudUsageMode == .preferOnline)) {
      do {
        try await runCloudAnalysis(prompt: prompt, image: image, settings: settings)
        return
      } catch is CancellationError {
        throw CancellationError()
      } catch {
        analysisStage = "Online analysis unavailable. Using on-device model"
        try await runLocalLLM(prompt: prompt, image: image, fallbackSpeech: fallbackSpeech)
        await speech.waitUntilFinished()
        try Task.checkCancellation()
        settings.recordSuccessfulLocalAnalysis()
        return
      }
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
    await speech.waitUntilFinished()
    try Task.checkCancellation()
  }

  private func suspendSafetyAnnouncementsForAnalysis() {
    guard feature == .navigation else { return }
    safetyAnnouncementsSuspendedForAnalysis = true
    speech.setSafetyAnnouncementsSuspended(true)
  }

  private func resumeSafetyAnnouncementsAfterAnalysis() {
    guard safetyAnnouncementsSuspendedForAnalysis else { return }
    safetyAnnouncementsSuspendedForAnalysis = false
    speech.setSafetyAnnouncementsSuspended(false)
    resetSafetyAnnouncementState()
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
    guard feature.usesLocalLLM else { return }
    hasAnnouncedStartupStatus = true
    if localLLM.isReady {
      speech.announce("Ready.")
      announceAccessibility("Ready.")
    } else if hasStagedLocalModel {
      speech.queue("On-device model loading. Wait for ready.")
      announceAccessibility("On-device model loading. Wait for ready.")
    } else {
      let message = "Download an on-device model in Settings to enable offline analysis."
      speech.queue(message)
      announceAccessibility(message)
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
    hasAnnouncedStartupStatus = true
    speech.announce("Ready.")
    announceAccessibility("Model ready.")
  }

  var modelStatusText: String {
    if isAnalyzing { return "Analyzing" }
    if feature.usesLocalLLM {
      if localLLM.isReady { return "Ready" }
      return hasStagedLocalModel ? "Model loading" : "On-device model not downloaded"
    }
    return isModelReady ? "Ready" : "Preparing"
  }

  private func modelReady(for feature: EchoSenseFeature) -> Bool {
    switch feature {
    case .assistant:
      return localLLM.isReady
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
    lastDetectionResultsTime = Date()
    if let metricAlert = metricAlert(from: latestDepthObservations, boxes: boxes) {
      proximityAlert = metricAlert
      maybeSpeakProximity(metricAlert)
      return
    }
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

  private func handleDepthObservations(_ observations: [DepthObservation]) {
    guard collisionAvoidanceAvailable, collisionAvoidanceEnabled else { return }
    latestDepthObservations = observations
    guard let alert = metricAlert(from: observations, boxes: proximityBoxes) else { return }
    proximityAlert = alert
    maybeSpeakProximity(alert)
  }

  private func metricAlert(
    from observations: [DepthObservation],
    boxes: [DetectionBox]
  ) -> ProximityAlert? {
    let recent = observations.filter { Date().timeIntervalSince($0.timestamp) < 1.0 && $0.confidence >= 0.20 }
    guard let nearest = recent.min(by: { $0.distanceMeters < $1.distanceMeters }) else { return nil }
    let matchingBox = boxes
      .filter { bearing(fromCenterXNormalized: $0.centerXNormalized) == nearest.bearing }
      .max(by: { $0.height < $1.height })
    let label: String
    if nearest.surface == .wall {
      label = "Wall"
    } else if let matchingBox, !matchingBox.label.isEmpty {
      label = matchingBox.label
    } else {
      label = "Obstacle"
    }
    return ProximityAlert(
      label: label,
      severity: severity(forDistanceMeters: nearest.distanceMeters),
      bearing: nearest.bearing,
      distanceMeters: nearest.distanceMeters
    )
  }

  /// Freezes a compact, recent sensor summary at the same boundary as the submitted image.
  /// Regional depth and object detections stay separate because a coarse range cell cannot
  /// prove that a particular detection produced that depth value.
  private func sensorPromptSnapshot(now: Date = Date()) -> String {
    guard feature == .navigation, collisionAvoidanceEnabled else { return "" }
    let recentDepth = latestDepthObservations
      .filter { now.timeIntervalSince($0.timestamp) < 1.2 && $0.confidence >= 0.20 }
    let objects = (now.timeIntervalSince(lastDetectionResultsTime) < 1.2 ? proximityBoxes : [])
      .filter { $0.score >= 0.35 }
      .sorted {
        let leftArea = $0.width * $0.height
        let rightArea = $1.width * $1.height
        return leftArea == rightArea ? $0.score > $1.score : leftArea > rightArea
      }
      .prefix(5)

    guard !recentDepth.isEmpty || !objects.isEmpty else { return "" }
    var lines = [
      "",
      "<SENSOR_SNAPSHOT>",
      "Captured near the image timestamp. Advisory only; reconcile it with visible evidence and do not invent precision.",
    ]
    if !recentDepth.isEmpty {
      let regions = recentDepth.map { observation in
        let surface = observation.surface == .wall ? ", wall-like surface" : ""
        return String(
          format: "%@ %.1f m (confidence %.0f%%%@)",
          observation.bearing.rawValue,
          observation.distanceMeters,
          observation.confidence * 100,
          surface
        )
      }
      lines.append("Depth source: \(depthSensingDescription). Regions: \(regions.joined(separator: "; ")).")
    }
    if !objects.isEmpty {
      let detections = objects.map { box in
        let bearing = bearing(fromCenterXNormalized: box.centerXNormalized).rawValue
        let apparentDepth = 1 - min(1, Float(box.height) / Float(max(1, box.imageHeight)))
        let proximity = apparentDepth <= 0.20 ? "near" : apparentDepth <= 0.40 ? "mid-range" : "farther"
        return String(format: "%@ %@ (%@, %.0f%%)", box.label.isEmpty ? "obstacle" : box.label, bearing, proximity, box.score * 100)
      }
      lines.append("Object detector: \(detections.joined(separator: "; ")). Apparent proximity is image-size based, not metric range.")
    }
    lines.append("Use sensor data to improve obstacle, wall, pathway, and distance guidance. If sensor and image disagree, state uncertainty briefly.")
    lines.append("</SENSOR_SNAPSHOT>")
    return lines.joined(separator: "\n")
  }

  private func sensorContextSummary(for snapshot: String) -> String {
    guard !snapshot.isEmpty else {
      return "Analysis context: image only — turn on Safety for range and object context"
    }
    var sources: [String] = []
    if snapshot.contains("Depth source:") { sources.append("metric range") }
    if snapshot.contains("Object detector:") { sources.append("detected objects") }
    return "Analysis context: " + sources.joined(separator: " + ")
  }

  private func maybeSpeakProximity(_ alert: ProximityAlert) {
    guard !safetyAnnouncementsSuspendedForAnalysis else { return }
    let now = Date()
    let elapsed = now.timeIntervalSince(lastAlertTime)
    let sameBearing = alert.bearing == lastAlertBearing
    let sameSeverity = alert.severity == lastAlertSeverity
    let escalated = alert.severity.rawValue > (lastAlertSeverity?.rawValue ?? -1)
    let materiallyCloser = {
      guard let previous = lastAlertDistanceMeters,
            let current = alert.distanceMeters else { return false }
      return previous - current >= 0.5
    }()

    if !escalated {
      if !sameBearing || !sameSeverity {
        let sameCandidate = candidateAlert?.bearing == alert.bearing &&
          candidateAlert?.severity == alert.severity
        if !sameCandidate {
          candidateAlert = alert
          candidateAlertSince = now
          return
        }
        guard let candidateAlertSince,
              now.timeIntervalSince(candidateAlertSince) >= 0.4,
              elapsed >= 1.0 else { return }
      } else if materiallyCloser {
        candidateAlert = nil
        candidateAlertSince = nil
        guard elapsed >= 1.2 else { return }
      } else if elapsed < 4.5 {
        candidateAlert = nil
        candidateAlertSince = nil
        return
      } else {
        candidateAlert = nil
        candidateAlertSince = nil
      }
    }
    candidateAlert = nil
    candidateAlertSince = nil

    let label = alert.label.isEmpty ? "Obstacle" : alert.label
    let roundedDistance = alert.distanceMeters.map { (Double($0) * 2).rounded() / 2 }
    let range = roundedDistance.map { String(format: " %.1f meters", $0) } ?? ""
    let phrase: String
    switch (alert.severity, alert.bearing) {
    case (.urgent, .left): phrase = "Stop. \(label) left."
    case (.urgent, .center): phrase = "Stop. \(label) ahead."
    case (.urgent, .right): phrase = "Stop. \(label) right."
    case (.warning, .left): phrase = "Caution, \(label)\(range) left."
    case (.warning, .center): phrase = "Caution, \(label)\(range) ahead."
    case (.warning, .right): phrase = "Caution, \(label)\(range) right."
    case (.info, .left): phrase = "\(label)\(range) left."
    case (.info, .center): phrase = "\(label)\(range) ahead."
    case (.info, .right): phrase = "\(label)\(range) right."
    }

    playSafetyHaptic(for: alert.severity)
    speech.announceSafety(
      phrase,
      severity: alert.severity,
      rateMultiplier: settings?.safetySpeechRate ?? 1.1,
      followUpText: alert.severity == .urgent
        ? roundedDistance.map { String(format: "About %.1f meters.", $0) }
        : nil
    )
    // Do not also post a VoiceOver announcement here: that would run a
    // second speech engine over the dedicated Safety voice output.
    lastAlertTime = now
    lastAlertBearing = alert.bearing
    lastAlertSeverity = alert.severity
    lastAlertDistanceMeters = alert.distanceMeters
  }

  private func playSafetyHaptic(for severity: ProximitySeverity) {
    guard severity != .info else { return }
    let generator = UINotificationFeedbackGenerator()
    generator.prepare()
    generator.notificationOccurred(severity == .urgent ? .error : .warning)
  }

  private func resetSafetyAnnouncementState() {
    lastAlertTime = .distantPast
    lastAlertBearing = nil
    lastAlertSeverity = nil
    lastAlertDistanceMeters = nil
    candidateAlert = nil
    candidateAlertSince = nil
  }

  private func submitInstantTextFrameIfNeeded() {
    guard feature == .documentReader,
          instantTextEnabled,
          !instantTextPaused,
          instantTextTask == nil,
          Date().timeIntervalSince(lastInstantTextFrameTime) >= 0.85,
          let sampleBuffer = latestSampleBuffer,
          let image = camera.captureCurrentFrameImage(from: sampleBuffer) else { return }

    lastInstantTextFrameTime = Date()
    instantTextTask = Task { [weak self] in
      guard let self else { return }
      defer { instantTextTask = nil }
      do {
        let result = try await ocrService.recognize(in: image, language: instantTextLanguage)
        try Task.checkCancellation()
        await applyInstantTextResult(result, image: image)
      } catch is CancellationError {
      } catch {
        instantTextStatus = "Waiting for a clear image"
      }
    }
  }

  private func submitGuidedDocumentFrameIfNeeded() {
    guard feature == .documentReader,
          guidedDocumentEnabled,
          guidedDocumentTask == nil,
          Date().timeIntervalSince(lastGuidedDocumentFrameTime) >= 0.55,
          let sampleBuffer = latestSampleBuffer,
          let image = camera.captureCurrentFrameImage(from: sampleBuffer) else { return }

    lastGuidedDocumentFrameTime = Date()
    guidedDocumentTask = Task { [weak self] in
      guard let self else { return }
      defer { finishGuidedDocumentTask() }
      do {
        let observation = try await documentCaptureService.observe(in: image)
        try Task.checkCancellation()
        await applyGuidedDocumentObservation(observation, image: image)
      } catch is CancellationError {
      } catch {
        guidedDocumentStatus = "Looking for a complete page"
      }
    }
  }

  private func finishGuidedDocumentTask() {
    guidedDocumentTask = nil
    if guidedDocumentManualCapturePending, guidedDocumentEnabled {
      captureGuidedDocumentManually()
    }
  }

  private func applyGuidedDocumentObservation(
    _ observation: DocumentFrameObservation,
    image: UIImage
  ) async {
    guard guidedDocumentEnabled else { return }

    let imageIssue = VisualUtilityAnalyzer.textCaptureIssue(in: image)
    let guidance = imageIssue ?? observation.guidance
    guidedDocumentStatus = guidance

    guard let quadrilateral = observation.quadrilateral else {
      guidedDocumentCandidate = nil
      guidedDocumentStableCount = 0
      await announcePersistentGuidedDocumentGuidance(guidance)
      return
    }

    if let candidate = guidedDocumentCandidate,
       quadrilateral.maximumCornerDistance(from: candidate) < 0.025 {
      guidedDocumentStableCount += 1
    } else {
      guidedDocumentCandidate = quadrilateral
      guidedDocumentStableCount = 1
    }

    guard guidance == "Hold steady" else {
      await announcePersistentGuidedDocumentGuidance(guidance)
      return
    }
    guidedDocumentGuidanceCandidate = ""
    guidedDocumentGuidanceCount = 0
    guard guidedDocumentStableCount >= 3 else { return }
    await captureGuidedDocument(manual: false)
  }

  private func announcePersistentGuidedDocumentGuidance(_ guidance: String) async {
    if guidance == guidedDocumentGuidanceCandidate {
      guidedDocumentGuidanceCount += 1
    } else {
      guidedDocumentGuidanceCandidate = guidance
      guidedDocumentGuidanceCount = 1
    }
    guard guidedDocumentGuidanceCount >= 2,
          Date().timeIntervalSince(lastGuidedDocumentAnnouncementTime) >= 6 else { return }
    lastGuidedDocumentAnnouncementTime = Date()
    guidedDocumentGuidanceCount = 0
    speech.announce(guidance)
    announceAccessibility(guidance)
    await speech.waitUntilFinished()
    try? await Task.sleep(for: .milliseconds(500))
  }

  private func captureGuidedDocument(manual: Bool) async {
    guard guidedDocumentEnabled, !Task.isCancelled else { return }
    speech.stop()
    isAnalyzing = true
    guidedDocumentStatus = manual ? "Capturing page manually" : "Page stable. Capturing"
    analysisStage = "Focusing on document"
    activeModelName = "Vision document scanner"
    camera.requestCenterFocus()
    defer {
      isAnalyzing = false
      analysisStage = ""
    }

    do {
      try await Task.sleep(for: .milliseconds(450))
      try Task.checkCancellation()
      let photo = try await camera.captureHighQualityPhoto()
      guidedDocumentStatus = "Correcting page"
      let corrected = try await documentCaptureService.correctAndEnhance(photo)
      try Task.checkCancellation()
      guidedDocumentStatus = "Recognizing document text"
      let result = try await ocrService.recognize(in: corrected, language: instantTextLanguage)
      try Task.checkCancellation()
      let text = result.text.trimmingCharacters(in: .whitespacesAndNewlines)
      guard !text.isEmpty else {
        guidedDocumentStatus = "No text found. Adjust the page and try Capture"
        guidedDocumentCandidate = nil
        guidedDocumentStableCount = 0
        let message = "No text found. Adjust the page and try Capture."
        speech.announce(message)
        announceAccessibility(message)
        return
      }

      transcript = text
      guidedDocumentEnabled = false
      guidedDocumentStatus = "Document captured"
      guidedDocumentCandidate = nil
      guidedDocumentStableCount = 0
      speech.announce(text)
      announceAccessibility("Document captured and text recognized.")
    } catch is CancellationError {
    } catch {
      let message = "Unable to capture the document. Guided scan is still active."
      guidedDocumentStatus = message
      errorMessage = error.localizedDescription
      speech.announce(message)
      announceAccessibility(message)
    }
  }

  private func applyInstantTextResult(_ result: OCRResult, image: UIImage) async {
    guard instantTextEnabled, !instantTextPaused else { return }
    let text = result.text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard text.count >= 2 else {
      instantEmptyResultCount += 1
      instantTextCandidate = ""
      instantTextCandidateCount = 0
      instantTextStatus = "Looking for text"
      let issue = VisualUtilityAnalyzer.textCaptureIssue(in: image) ??
        (instantEmptyResultCount >= 3
          ? "No text detected. Center printed text in the view and hold the phone steady."
          : nil)
      await announcePersistentInstantGuidanceIfNeeded(issue)
      return
    }

    instantEmptyResultCount = 0
    instantGuidanceCandidate = ""
    instantGuidanceCandidateCount = 0
    let normalized = normalizedInstantText(text)
    if textSimilarity(normalized, instantTextCandidate) >= 0.78 {
      instantTextCandidateCount += 1
    } else {
      instantTextCandidate = normalized
      instantTextCandidateCount = 1
    }
    let scriptSuffix = result.script.map { ", \($0)" } ?? ""
    instantTextStatus = instantTextCandidateCount >= 2 ? "Text recognized\(scriptSuffix)" : "Hold steady"

    guard instantTextCandidateCount >= 2,
          textSimilarity(normalized, normalizedInstantText(lastSpokenInstantText)) < 0.88 else { return }
    await captureAndReadStableInstantText(fallbackText: text)
  }

  private func captureAndReadStableInstantText(fallbackText: String) async {
    instantTextStatus = "Hold steady. Capturing text"
    camera.requestCenterFocus()
    try? await Task.sleep(for: .milliseconds(450))
    guard instantTextEnabled, !instantTextPaused, !Task.isCancelled else { return }

    var capturedText = ""
    do {
      let photo = try await camera.captureHighQualityPhoto()
      let result = try await ocrService.recognize(in: photo, language: instantTextLanguage)
      capturedText = result.text.trimmingCharacters(in: .whitespacesAndNewlines)
    } catch {
      // Fall back to the stable live result if still capture is interrupted.
    }

    let text = capturedText.count >= 2 ? capturedText : fallbackText
    guard textSimilarity(
      normalizedInstantText(text),
      normalizedInstantText(lastSpokenInstantText)
    ) < 0.88 else {
      instantTextStatus = "Scanning for new text"
      return
    }

    transcript = text
    lastSpokenInstantText = text
    instantTextStatus = "Reading captured text"
    speech.announce(text)
    announceAccessibility("New text captured and recognized.")
    await speech.waitUntilFinished()
    try? await Task.sleep(for: .milliseconds(900))
    guard instantTextEnabled, !Task.isCancelled else { return }
    instantTextCandidate = ""
    instantTextCandidateCount = 0
    instantTextStatus = instantTextPaused ? "Paused on recognized text" : "Scanning for new text"
  }

  private func announcePersistentInstantGuidanceIfNeeded(_ issue: String?) async {
    guard let issue else {
      instantGuidanceCandidate = ""
      instantGuidanceCandidateCount = 0
      return
    }
    if issue == instantGuidanceCandidate {
      instantGuidanceCandidateCount += 1
    } else {
      instantGuidanceCandidate = issue
      instantGuidanceCandidateCount = 1
    }
    guard instantGuidanceCandidateCount >= 3,
          Date().timeIntervalSince(lastInstantGuidanceTime) >= 12 else { return }
    lastInstantGuidanceTime = Date()
    instantGuidanceCandidateCount = 0
    instantTextStatus = issue
    speech.announce(issue)
    announceAccessibility(issue)
    await speech.waitUntilFinished()
    try? await Task.sleep(for: .milliseconds(700))
  }

  private func normalizedInstantText(_ text: String) -> String {
    text.lowercased()
      .components(separatedBy: CharacterSet.alphanumerics.inverted)
      .filter { !$0.isEmpty }
      .joined(separator: " ")
  }

  private func textSimilarity(_ lhs: String, _ rhs: String) -> Double {
    guard !lhs.isEmpty, !rhs.isEmpty else { return lhs == rhs && !lhs.isEmpty ? 1 : 0 }
    if lhs == rhs { return 1 }
    let left = Set(lhs.split(separator: " "))
    let right = Set(rhs.split(separator: " "))
    let union = left.union(right)
    guard !union.isEmpty else { return 0 }
    return Double(left.intersection(right).count) / Double(union.count)
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
  nonisolated func cameraFrameSource(_ source: CameraFrameSource, depthAvailabilityDidChange isAvailable: Bool) {
    Task { @MainActor in
      depthSensingDescription = isAvailable ? "LiDAR depth" : "Camera estimate"
    }
  }

  nonisolated func cameraFrameSource(_ source: CameraFrameSource, didUpdateDepth observations: [DepthObservation]) {
    Task { @MainActor in
      guard isActive else { return }
      handleDepthObservations(observations)
    }
  }

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
      submitInstantTextFrameIfNeeded()
      submitGuidedDocumentFrameIfNeeded()
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
