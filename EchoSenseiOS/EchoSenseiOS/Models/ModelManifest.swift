import Foundation

struct ModelManifest: Codable {
  let schemaVersion: Int
  let models: [RemoteModelDescriptor]
}

struct RemoteModelDescriptor: Codable, Equatable, Identifiable {
  let id: String
  let displayName: String
  let version: String
  let filename: String
  let url: URL
  let licenseURL: URL
  let sizeBytes: Int64
  let sha256: String
  let supportedPlatforms: [String]
  let minimumIOSVersion: String?
  let minimumAndroidSDK: Int?
  let minimumMemoryGB: Int?
  let capabilities: [String]
  let recommended: Bool

  var normalizedSHA256: String { sha256.lowercased() }

  func validateForIOS() throws {
    guard !id.isEmpty, !version.isEmpty, !filename.isEmpty else {
      throw ModelCatalogError.invalidManifest("A model identifier, version, or filename is missing.")
    }
    guard url.scheme == "https", licenseURL.scheme == "https" else {
      throw ModelCatalogError.invalidManifest("Model and license URLs must use HTTPS.")
    }
    guard sizeBytes > 0, supportedPlatforms.contains("ios") else {
      throw ModelCatalogError.noCompatibleModel
    }
    let hash = normalizedSHA256
    guard hash.count == 64, hash.allSatisfy(\.isHexDigit), hash != String(repeating: "0", count: 64) else {
      throw ModelCatalogError.invalidManifest("The model SHA-256 checksum is missing or invalid.")
    }
  }
}

enum ModelCatalogError: LocalizedError {
  case invalidManifest(String)
  case noCompatibleModel
  case unavailable

  var errorDescription: String? {
    switch self {
    case .invalidManifest(let reason): "The model catalog is invalid. \(reason)"
    case .noCompatibleModel: "No compatible iOS model is available."
    case .unavailable: "The model catalog is currently unavailable. Please try again."
    }
  }
}
