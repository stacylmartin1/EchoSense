import AVFoundation
import Foundation
import Speech

enum VoiceCommandError: LocalizedError {
  case speechRecognitionUnavailable
  case speechPermissionDenied
  case microphonePermissionDenied
  case invalidAudioFormat
  case noSpeechRecognized
  case recognitionFailed(String)

  var errorDescription: String? {
    switch self {
    case .speechRecognitionUnavailable:
      "Speech recognition is currently unavailable."
    case .speechPermissionDenied:
      "Speech recognition permission is required for voice commands."
    case .microphonePermissionDenied:
      "Microphone permission is required for voice commands."
    case .invalidAudioFormat:
      "The microphone audio format is unavailable."
    case .noSpeechRecognized:
      "No voice command was recognized. Please try again."
    case .recognitionFailed(let message):
      "Voice command failed: \(message)"
    }
  }
}

@MainActor
final class VoiceCommandService {
  private let audioEngine = AVAudioEngine()
  private let recognizer = SFSpeechRecognizer(locale: .current)
  private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
  private var recognitionTask: SFSpeechRecognitionTask?
  private var timeoutTask: Task<Void, Never>?
  private var silenceTask: Task<Void, Never>?
  private var resultHandler: ((String) -> Void)?
  private var partialResultHandler: ((String) -> Void)?
  private var errorHandler: ((Error) -> Void)?
  private var didInstallTap = false
  private var latestTranscript = ""

  private(set) var isListening = false
  var supportsOfflineRecognition: Bool {
    recognizer?.supportsOnDeviceRecognition == true
  }

  func start(
    onPartialResult: @escaping (String) -> Void,
    onResult: @escaping (String) -> Void,
    onError: @escaping (Error) -> Void
  ) {
    stop()
    partialResultHandler = onPartialResult
    resultHandler = onResult
    errorHandler = onError

    Task { @MainActor in
      guard await requestSpeechPermission() else {
        finish(error: VoiceCommandError.speechPermissionDenied)
        return
      }
      guard await requestMicrophonePermission() else {
        finish(error: VoiceCommandError.microphonePermissionDenied)
        return
      }
      guard recognizer?.isAvailable == true else {
        finish(error: VoiceCommandError.speechRecognitionUnavailable)
        return
      }

      do {
        try beginRecognition()
      } catch {
        finish(error: error)
      }
    }
  }

  func stop() {
    let wasCapturingAudio = isListening || audioEngine.isRunning || didInstallTap || recognitionRequest != nil
    timeoutTask?.cancel()
    timeoutTask = nil
    silenceTask?.cancel()
    silenceTask = nil
    if audioEngine.isRunning {
      audioEngine.stop()
    }
    if didInstallTap {
      audioEngine.inputNode.removeTap(onBus: 0)
      didInstallTap = false
    }
    recognitionRequest?.endAudio()
    recognitionTask?.cancel()
    recognitionTask = nil
    recognitionRequest = nil
    isListening = false
    resultHandler = nil
    partialResultHandler = nil
    errorHandler = nil
    latestTranscript = ""
    if wasCapturingAudio {
      try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
  }

  /// Ends recording and accepts the best transcription received so far.
  /// Speech recognition commonly leaves short commands as partial results,
  /// even after the user has clearly stopped speaking.
  func finishListening() {
    guard isListening else { return }
    let text = latestTranscript.trimmingCharacters(in: .whitespacesAndNewlines)
    if text.isEmpty {
      finish(error: VoiceCommandError.noSpeechRecognized)
    } else {
      finish(result: text)
    }
  }

  private func beginRecognition() throws {
    let audioSession = AVAudioSession.sharedInstance()
    try audioSession.setCategory(.record, mode: .measurement, options: [.duckOthers])
    try audioSession.setActive(true, options: .notifyOthersOnDeactivation)

    let request = SFSpeechAudioBufferRecognitionRequest()
    request.shouldReportPartialResults = true
    request.taskHint = .search
    if recognizer?.supportsOnDeviceRecognition == true {
      request.requiresOnDeviceRecognition = true
    }
    recognitionRequest = request

    let inputNode = audioEngine.inputNode
    let format = inputNode.outputFormat(forBus: 0)
    guard format.sampleRate > 0, format.channelCount > 0 else {
      throw VoiceCommandError.invalidAudioFormat
    }

    inputNode.installTap(onBus: 0, bufferSize: 1_024, format: format) { [weak request] buffer, _ in
      guard buffer.frameLength > 0 else { return }
      request?.append(buffer)
    }
    didInstallTap = true
    audioEngine.prepare()
    try audioEngine.start()
    isListening = true

    recognitionTask = recognizer?.recognitionTask(with: request) { [weak self] result, error in
      Task { @MainActor in
        guard let self, self.isListening else { return }
        if let result {
          let text = result.bestTranscription.formattedString
            .trimmingCharacters(in: .whitespacesAndNewlines)
          if !text.isEmpty {
            self.latestTranscript = text
            self.partialResultHandler?(text)
            self.scheduleSilenceCompletion()
          }
          if result.isFinal {
            if text.isEmpty {
              self.finish(error: VoiceCommandError.noSpeechRecognized)
            } else {
              self.finish(result: text)
            }
            return
          }
        }
        if let error {
          self.finish(error: VoiceCommandError.recognitionFailed(error.localizedDescription))
        }
      }
    }

    timeoutTask = Task { @MainActor in
      try? await Task.sleep(for: .seconds(12))
      guard !Task.isCancelled, isListening else { return }
      finishListening()
    }
  }

  private func scheduleSilenceCompletion() {
    silenceTask?.cancel()
    silenceTask = Task { @MainActor in
      // SFSpeechRecognizer does not reliably produce isFinal for short commands.
      // A pause after a non-empty partial result is therefore our utterance end.
      try? await Task.sleep(for: .seconds(1.5))
      guard !Task.isCancelled, isListening, !latestTranscript.isEmpty else { return }
      finishListening()
    }
  }

  private func finish(result: String) {
    let handler = resultHandler
    stop()
    handler?(result)
  }

  private func finish(error: Error) {
    let handler = errorHandler
    stop()
    handler?(error)
  }

  private func requestSpeechPermission() async -> Bool {
    switch SFSpeechRecognizer.authorizationStatus() {
    case .authorized:
      return true
    case .denied, .restricted:
      return false
    case .notDetermined:
      return await withCheckedContinuation { continuation in
        SFSpeechRecognizer.requestAuthorization { status in
          continuation.resume(returning: status == .authorized)
        }
      }
    @unknown default:
      return false
    }
  }

  private func requestMicrophonePermission() async -> Bool {
    switch AVAudioSession.sharedInstance().recordPermission {
    case .granted:
      return true
    case .denied:
      return false
    case .undetermined:
      return await withCheckedContinuation { continuation in
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
          continuation.resume(returning: granted)
        }
      }
    @unknown default:
      return false
    }
  }
}
