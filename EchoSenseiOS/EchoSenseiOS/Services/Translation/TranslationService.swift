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

import Foundation
import MLKitLanguageID
import MLKitTranslate

protocol TranslationService {
  func translateToEnglish(_ text: String) async throws -> String
}

enum TranslationServiceError: LocalizedError {
  case offlineTranslationUnavailable
  case languageIdentificationFailed
  case modelDownloadFailed
  case unsupportedLanguage(String)

  var errorDescription: String? {
    switch self {
    case .offlineTranslationUnavailable:
      "On-device translation did not return any text."
    case .languageIdentificationFailed:
      "The source language could not be identified. Try a clearer image or more text."
    case .modelDownloadFailed:
      "The on-device translation model is not installed. Connect to Wi-Fi and try again to download it."
    case let .unsupportedLanguage(languageCode):
      "Offline translation currently supports English, Spanish, French, and German. "
        + "Use online translation for other languages. Detected language: \(languageCode)."
    }
  }
}

struct MLKitTranslationService: TranslationService {
  private let validatedLanguages: Set<String> = ["en", "es", "fr", "de"]

  func translateToEnglish(_ text: String) async throws -> String {
    try Task.checkCancellation()
    let languageCode = try await identifyLanguage(text)
    let baseLanguageCode = languageCode
      .split(separator: "-")
      .first
      .map(String.init)?
      .lowercased() ?? languageCode.lowercased()
    guard validatedLanguages.contains(baseLanguageCode) else {
      throw TranslationServiceError.unsupportedLanguage(languageCode)
    }
    if baseLanguageCode == "en" {
      return text
    }
    let sourceLanguage: TranslateLanguage
    switch baseLanguageCode {
    case "es": sourceLanguage = .spanish
    case "fr": sourceLanguage = .french
    case "de": sourceLanguage = .german
    default: throw TranslationServiceError.unsupportedLanguage(languageCode)
    }

    let options = TranslatorOptions(
      sourceLanguage: sourceLanguage,
      targetLanguage: .english
    )
    let translator = Translator.translator(options: options)
    let conditions = ModelDownloadConditions(
      allowsCellularAccess: false,
      allowsBackgroundDownloading: true
    )
    try await downloadModelIfNeeded(translator, conditions: conditions)
    try Task.checkCancellation()
    let translated = try await translate(text, with: translator)
      .trimmingCharacters(in: .whitespacesAndNewlines)
    guard !translated.isEmpty else {
      throw TranslationServiceError.offlineTranslationUnavailable
    }
    return translated
  }

  private func identifyLanguage(_ text: String) async throws -> String {
    try await withCheckedThrowingContinuation { continuation in
      LanguageIdentification.languageIdentification().identifyLanguage(for: text) { code, error in
        if error != nil {
          continuation.resume(throwing: TranslationServiceError.languageIdentificationFailed)
        } else if let code, code != "und" {
          continuation.resume(returning: code)
        } else {
          continuation.resume(throwing: TranslationServiceError.languageIdentificationFailed)
        }
      }
    }
  }

  private func downloadModelIfNeeded(
    _ translator: Translator,
    conditions: ModelDownloadConditions
  ) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      translator.downloadModelIfNeeded(with: conditions) { error in
        if error != nil {
          continuation.resume(throwing: TranslationServiceError.modelDownloadFailed)
        } else {
          continuation.resume(returning: ())
        }
      }
    }
  }

  private func translate(_ text: String, with translator: Translator) async throws -> String {
    try await withCheckedThrowingContinuation { continuation in
      translator.translate(text) { translatedText, error in
        if error != nil {
          continuation.resume(throwing: TranslationServiceError.offlineTranslationUnavailable)
        } else if let translatedText {
          continuation.resume(returning: translatedText)
        } else {
          continuation.resume(throwing: TranslationServiceError.offlineTranslationUnavailable)
        }
      }
    }
  }
}
