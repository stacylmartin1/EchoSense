import Foundation
import Security

enum ResponseStyle: String, CaseIterable, Identifiable {
  case concise
  case verbose

  var id: String { rawValue }
}

enum ObjectDetectorModel: String, CaseIterable, Identifiable {
  case yolo = "YOLOv3 Tiny"
  case yolov8 = "YOLOv8 Nano"

  var id: String { rawValue }
}

enum CloudProvider: String, CaseIterable, Identifiable {
  case none
  case gemini
  case openAICompatible

  var id: String { rawValue }

  var displayName: String {
    switch self {
    case .none: "Off"
    case .gemini: "Gemini"
    case .openAICompatible: "OpenAI"
    }
  }

  var keyCreationURL: URL? {
    switch self {
    case .none: nil
    case .gemini: URL(string: "https://aistudio.google.com/app/apikey")
    case .openAICompatible: URL(string: "https://platform.openai.com/api-keys")
    }
  }
}

enum CloudUsageMode: String, CaseIterable, Identifiable {
  case askBeforeUse
  case automaticFallback
  case preferOnline

  var id: String { rawValue }

  var displayName: String {
    switch self {
    case .askBeforeUse: "Ask before use"
    case .automaticFallback: "Automatic fallback"
    case .preferOnline: "Prefer online"
    }
  }

  var explanation: String {
    switch self {
    case .askBeforeUse: "On-device analysis stays primary. Use the cloud button when you want online analysis."
    case .automaticFallback: "Use online analysis only if on-device analysis fails."
    case .preferOnline: "Use online analysis first whenever a network connection is available."
    }
  }
}

@MainActor
final class AppSettings: ObservableObject {
  @Published var responseStyle: ResponseStyle {
    didSet { UserDefaults.standard.set(responseStyle.rawValue, forKey: Keys.responseStyle) }
  }

  @Published var videoPreviewEnabled: Bool {
    didSet { UserDefaults.standard.set(videoPreviewEnabled, forKey: Keys.videoPreviewEnabled) }
  }

  @Published var textOverlayEnabled: Bool {
    didSet { UserDefaults.standard.set(textOverlayEnabled, forKey: Keys.textOverlayEnabled) }
  }

  @Published var selectedVoiceIdentifier: String {
    didSet { UserDefaults.standard.set(selectedVoiceIdentifier, forKey: Keys.selectedVoiceIdentifier) }
  }

  @Published var safetySpeechRate: Double {
    didSet { UserDefaults.standard.set(safetySpeechRate, forKey: Keys.safetySpeechRate) }
  }

  @Published var cloudProvider: CloudProvider {
    didSet { UserDefaults.standard.set(cloudProvider.rawValue, forKey: Keys.cloudProvider) }
  }

  @Published private(set) var cloudAPIKey: String

  @Published var cloudUsageMode: CloudUsageMode {
    didSet { UserDefaults.standard.set(cloudUsageMode.rawValue, forKey: Keys.cloudUsageMode) }
  }

  @Published var cloudConsentGranted: Bool {
    didSet { UserDefaults.standard.set(cloudConsentGranted, forKey: Keys.cloudConsentGranted) }
  }

  @Published var cloudPromptRequested = false

  @Published var selectedDetector: ObjectDetectorModel {
    didSet { UserDefaults.standard.set(selectedDetector.rawValue, forKey: Keys.selectedDetector) }
  }

  init() {
    let defaults = UserDefaults.standard
    responseStyle = ResponseStyle(rawValue: defaults.string(forKey: Keys.responseStyle) ?? "") ?? .concise
    videoPreviewEnabled = defaults.object(forKey: Keys.videoPreviewEnabled) as? Bool ?? true
    textOverlayEnabled = defaults.object(forKey: Keys.textOverlayEnabled) as? Bool ?? true
    selectedVoiceIdentifier = defaults.string(forKey: Keys.selectedVoiceIdentifier) ?? ""
    safetySpeechRate = defaults.object(forKey: Keys.safetySpeechRate) as? Double ?? 1.1
    let savedProvider = CloudProvider(rawValue: defaults.string(forKey: Keys.cloudProvider) ?? "") ?? .none
    let legacyKey = defaults.string(forKey: Keys.cloudAPIKey) ?? ""
    var secureKey = (try? CloudKeychain.load()) ?? ""
    if secureKey.isEmpty, !legacyKey.isEmpty {
      try? CloudKeychain.save(legacyKey)
      secureKey = legacyKey
    }
    defaults.removeObject(forKey: Keys.cloudAPIKey)
    cloudAPIKey = secureKey
    cloudProvider = secureKey.isEmpty ? .none : savedProvider
    cloudUsageMode = CloudUsageMode(rawValue: defaults.string(forKey: Keys.cloudUsageMode) ?? "") ?? .askBeforeUse
    cloudConsentGranted = defaults.bool(forKey: Keys.cloudConsentGranted)
    selectedDetector = ObjectDetectorModel(rawValue: defaults.string(forKey: Keys.selectedDetector) ?? "") ?? .yolo
  }

  var isCloudConnected: Bool { cloudProvider != .none && !cloudAPIKey.isEmpty }

  var maskedCloudKey: String {
    guard !cloudAPIKey.isEmpty else { return "" }
    return "••••\(cloudAPIKey.suffix(4))"
  }

  func saveCloudConnection(provider: CloudProvider, apiKey: String, usageMode: CloudUsageMode) throws {
    let normalized = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
    guard provider != .none, !normalized.isEmpty else { return }
    try CloudKeychain.save(normalized)
    cloudAPIKey = normalized
    cloudProvider = provider
    cloudUsageMode = usageMode
    if usageMode != .askBeforeUse { cloudConsentGranted = true }
    dismissCloudPrompt()
  }

  func removeCloudConnection() {
    try? CloudKeychain.delete()
    cloudAPIKey = ""
    cloudProvider = .none
    cloudUsageMode = .askBeforeUse
    cloudConsentGranted = false
  }

  func recordSuccessfulLocalAnalysis() {
    guard !isCloudConnected,
          !UserDefaults.standard.bool(forKey: Keys.cloudPromptDismissed) else { return }
    cloudPromptRequested = true
  }

  func beginCloudSetup() {
    cloudPromptRequested = false
  }

  func dismissCloudPrompt() {
    cloudPromptRequested = false
    UserDefaults.standard.set(true, forKey: Keys.cloudPromptDismissed)
  }

  private enum Keys {
    static let responseStyle = "echosense.responseStyle"
    static let videoPreviewEnabled = "echosense.videoPreviewEnabled"
    static let textOverlayEnabled = "echosense.textOverlayEnabled"
    static let selectedVoiceIdentifier = "echosense.selectedVoiceIdentifier"
    static let safetySpeechRate = "echosense.safetySpeechRate"
    static let cloudProvider = "echosense.cloudProvider"
    static let cloudAPIKey = "echosense.cloudAPIKey"
    static let cloudUsageMode = "echosense.cloudUsageMode"
    static let cloudConsentGranted = "echosense.cloudConsentGranted"
    static let cloudPromptDismissed = "echosense.cloudPromptDismissed"
    static let selectedDetector = "echosense.selectedDetector"
  }
}

private enum CloudKeychain {
  private static let service = "com.terranet.echosense.cloud-credentials"
  private static let account = "provider-api-key"

  static func load() throws -> String? {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
      kSecAttrAccount as String: account,
      kSecReturnData as String: true,
      kSecMatchLimit as String: kSecMatchLimitOne,
    ]
    var result: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &result)
    if status == errSecItemNotFound { return nil }
    guard status == errSecSuccess,
          let data = result as? Data,
          let value = String(data: data, encoding: .utf8) else {
      throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
    }
    return value
  }

  static func save(_ value: String) throws {
    let data = Data(value.utf8)
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
      kSecAttrAccount as String: account,
    ]
    let attributes: [String: Any] = [
      kSecValueData as String: data,
      kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
    ]
    let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
    if updateStatus == errSecItemNotFound {
      var item = query
      attributes.forEach { item[$0.key] = $0.value }
      let addStatus = SecItemAdd(item as CFDictionary, nil)
      guard addStatus == errSecSuccess else {
        throw NSError(domain: NSOSStatusErrorDomain, code: Int(addStatus))
      }
    } else if updateStatus != errSecSuccess {
      throw NSError(domain: NSOSStatusErrorDomain, code: Int(updateStatus))
    }
  }

  static func delete() throws {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
      kSecAttrAccount as String: account,
    ]
    let status = SecItemDelete(query as CFDictionary)
    guard status == errSecSuccess || status == errSecItemNotFound else {
      throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
    }
  }
}
