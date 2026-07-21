import AVFoundation
import Foundation

@MainActor
final class SpeechOutputService: NSObject, ObservableObject {
  private struct PendingSafetyAnnouncement {
    let text: String
    let severity: ProximitySeverity
    let rateMultiplier: Double
    let expiresAt: Date
  }

  private let synthesizer = AVSpeechSynthesizer()
  private var selectedVoiceIdentifier = ""
  private var activeSafetyUtterance: AVSpeechUtterance?
  private var activeSafetySeverity: ProximitySeverity?
  private var pendingSafetyAnnouncement: PendingSafetyAnnouncement?
  private var safetyAnnouncementsSuspended = false
  private var pendingUtteranceIDs: Set<ObjectIdentifier> = []

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

  /// Speaks a collision warning without allowing successive sensor updates to
  /// repeatedly cut one another off. There is never more than one pending
  /// warning, and that warning is discarded if it is no longer fresh.
  func announceSafety(
    _ text: String,
    severity: ProximitySeverity,
    rateMultiplier: Double,
    followUpText: String? = nil
  ) {
    guard !safetyAnnouncementsSuspended else { return }
    let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !clean.isEmpty else { return }

    let pending = PendingSafetyAnnouncement(
      text: clean,
      severity: severity,
      rateMultiplier: rateMultiplier,
      expiresAt: Date().addingTimeInterval(1.25)
    )
    let followUp = followUpText?
      .trimmingCharacters(in: .whitespacesAndNewlines)
      .nonEmpty
      .map {
        PendingSafetyAnnouncement(
          text: $0,
          severity: severity,
          rateMultiplier: rateMultiplier,
          expiresAt: Date().addingTimeInterval(2.0)
        )
      }

    if let activeSeverity = activeSafetySeverity {
      if severity.rawValue > activeSeverity.rawValue {
        // A true escalation may preempt a lower-priority Safety phrase.
        activeSafetyUtterance = nil
        activeSafetySeverity = nil
        pendingSafetyAnnouncement = nil
        synthesizer.stopSpeaking(at: .immediate)
        startSafetyAnnouncement(pending)
        pendingSafetyAnnouncement = followUp
      } else {
        // Protect the active phrase. The latest observation replaces any
        // older pending observation rather than growing a FIFO queue.
        pendingSafetyAnnouncement = pending
      }
      return
    }

    if severity == .info && synthesizer.isSpeaking {
      pendingSafetyAnnouncement = pending
      return
    }

    // Urgent and warning alerts preempt ordinary narration/status speech.
    if synthesizer.isSpeaking {
      synthesizer.stopSpeaking(at: .immediate)
    }
    startSafetyAnnouncement(pending)
    pendingSafetyAnnouncement = followUp
  }

  /// Temporarily silences Safety without retaining a backlog. Analysis uses
  /// this while its result is being generated and narrated.
  func setSafetyAnnouncementsSuspended(_ suspended: Bool) {
    safetyAnnouncementsSuspended = suspended
    guard suspended else { return }
    pendingSafetyAnnouncement = nil
    if let currentSafetyUtterance = activeSafetyUtterance {
      pendingUtteranceIDs.remove(ObjectIdentifier(currentSafetyUtterance))
      activeSafetyUtterance = nil
      activeSafetySeverity = nil
      synthesizer.stopSpeaking(at: .immediate)
    }
  }

  func stop() {
    activeSafetyUtterance = nil
    activeSafetySeverity = nil
    pendingSafetyAnnouncement = nil
    pendingUtteranceIDs.removeAll()
    synthesizer.stopSpeaking(at: .immediate)
  }

  func waitUntilFinished() async {
    // `isSpeaking` can remain false briefly after `speak(_:)` queues its first
    // utterance. Tracking the utterances themselves prevents cloud narration
    // from appearing finished during that enqueue/start window.
    while !pendingUtteranceIDs.isEmpty && !Task.isCancelled {
      try? await Task.sleep(for: .milliseconds(100))
    }
  }

  private func speak(_ text: String, interrupt: Bool) {
    let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !clean.isEmpty else { return }
    configureAudioSession()

    if interrupt {
      activeSafetyUtterance = nil
      activeSafetySeverity = nil
      pendingSafetyAnnouncement = nil
      pendingUtteranceIDs.removeAll()
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
    pendingUtteranceIDs.insert(ObjectIdentifier(utterance))
    synthesizer.speak(utterance)
  }

  private func startSafetyAnnouncement(_ announcement: PendingSafetyAnnouncement) {
    configureAudioSession()
    let utterance = makeUtterance(announcement.text)
    let requestedRate = Double(AVSpeechUtteranceDefaultSpeechRate) * announcement.rateMultiplier
    utterance.rate = Float(min(Double(AVSpeechUtteranceMaximumSpeechRate), requestedRate))
    activeSafetyUtterance = utterance
    activeSafetySeverity = announcement.severity
    pendingUtteranceIDs.insert(ObjectIdentifier(utterance))
    synthesizer.speak(utterance)
  }

  private func makeUtterance(_ text: String) -> AVSpeechUtterance {
    let utterance = AVSpeechUtterance(string: text)
    if !selectedVoiceIdentifier.isEmpty,
       let voice = AVSpeechSynthesisVoice(identifier: selectedVoiceIdentifier) {
      utterance.voice = voice
    } else {
      utterance.voice = AVSpeechSynthesisVoice(language: Locale.current.identifier)
    }
    utterance.volume = 1.0
    return utterance
  }

  private func safetyAnnouncementEnded(_ utterance: AVSpeechUtterance) {
    guard utterance === activeSafetyUtterance else { return }
    activeSafetyUtterance = nil
    activeSafetySeverity = nil
    guard let pending = pendingSafetyAnnouncement else { return }
    pendingSafetyAnnouncement = nil
    guard pending.expiresAt > Date() else { return }
    if synthesizer.isSpeaking {
      synthesizer.stopSpeaking(at: .immediate)
    }
    startSafetyAnnouncement(pending)
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

private extension String {
  var nonEmpty: String? { isEmpty ? nil : self }
}

extension SpeechOutputService: @preconcurrency AVSpeechSynthesizerDelegate {
  func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
    pendingUtteranceIDs.remove(ObjectIdentifier(utterance))
    safetyAnnouncementEnded(utterance)
  }

  func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
    pendingUtteranceIDs.remove(ObjectIdentifier(utterance))
    safetyAnnouncementEnded(utterance)
  }
}
