import Foundation

enum EchoSenseFeature: String, CaseIterable, Identifiable {
  case navigation
  case currency
  case documentReader
  case assistant
  case documentTranslator

  var id: String { rawValue }

  var title: String {
    switch self {
    case .assistant: "Assistant"
    case .navigation: "Navigation"
    case .documentReader: "Document Reader"
    case .documentTranslator: "Translator"
    case .currency: "Currency"
    }
  }

  var subtitle: String {
    switch self {
    case .assistant: "Ask questions using text, voice, images, or documents."
    case .navigation: "Describe scenes and announce nearby obstacles."
    case .documentReader: "Read documents aloud from camera or files."
    case .documentTranslator: "Extract and translate document text."
    case .currency: "Identify bank notes with confidence."
    }
  }

  var menuTitle: String {
    switch self {
    case .assistant: "Assistant"
    case .navigation: "Navigate"
    case .documentReader: "Read"
    case .documentTranslator: "Translate"
    case .currency: "Currency"
    }
  }

  var actionTitle: String {
    switch self {
    case .assistant: "Send"
    case .navigation: "Analyze"
    case .documentReader: "Read"
    case .documentTranslator: "Translate"
    case .currency: "Identify"
    }
  }

  var supportsCollisionAvoidance: Bool {
    self == .navigation
  }

  var supportsDocumentImport: Bool {
    self == .documentReader || self == .documentTranslator
  }

  var usesLocalLLM: Bool {
    self == .assistant || self == .navigation || self == .currency
  }

  var defaultActionTitle: String {
    switch self {
    case .assistant: "Send Message"
    case .navigation: "Analyze Scene"
    case .documentReader: "Read Document"
    case .documentTranslator: "Translate"
    case .currency: "Identify Currency"
    }
  }

  var systemImageName: String {
    switch self {
    case .assistant: "bubble.left.and.bubble.right.fill"
    case .navigation: "location.north.fill"
    case .documentReader: "doc.text.fill"
    case .documentTranslator: "translate"
    case .currency: "dollarsign"
    }
  }
}
