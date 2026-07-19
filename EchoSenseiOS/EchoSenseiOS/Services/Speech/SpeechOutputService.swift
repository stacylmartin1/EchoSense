import AVFoundation
import Foundation

@MainActor
final class SpeechOutputService: NSObject, ObservableObject {
  private let synthesizer = AVSpeechSynthesizer()
  private var selectedVoiceIdentifier = ""

  override init() {
    super.init()
    synthesizer.delegate = self
  }

  var availableVoices: [AVSpeechSynthesisVoice] {
    AVSpeechSynthesisVoice.speechVoices().sorted {
      ($0.language, $0.name) < ($1.language, $1.name)
    }
  }

  func setVoiceIdentifier(_ identifier: String) {
    selectedVoiceIdentifier = identifier
  }

  func announce(_ text: String) {
    speak(text, interrupt: true)
  }

  func queue(_ text: String) {
    speak(text, interrupt: false)
  }

  func stop() {
    synthesizer.stopSpeaking(at: .immediate)
  }

  func waitUntilFinished() async {
    while synthesizer.isSpeaking && !Task.isCancelled {
      try? await Task.sleep(for: .milliseconds(100))
    }
  }

  private func speak(_ text: String, interrupt: Bool) {
    let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !clean.isEmpty else { return }
    configureAudioSession()

    if interrupt {
      synthesizer.stopSpeaking(at: .immediate)
    }

    let utterance = AVSpeechUtterance(string: clean)
    if !selectedVoiceIdentifier.isEmpty,
       let voice = AVSpeechSynthesisVoice(identifier: selectedVoiceIdentifier) {
      utterance.voice = voice
    } else {
      utterance.voice = AVSpeechSynthesisVoice(language: Locale.current.identifier)
    }
    utterance.rate = AVSpeechUtteranceDefaultSpeechRate
    utterance.volume = 1.0
    synthesizer.speak(utterance)
  }

  private func configureAudioSession() {
    let session = AVAudioSession.sharedInstance()
    do {
      try session.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
      try session.setActive(true)
    } catch {
      // Speech can still work with the current system session; keep trying.
    }
  }
}

extension SpeechOutputService: AVSpeechSynthesizerDelegate {}
