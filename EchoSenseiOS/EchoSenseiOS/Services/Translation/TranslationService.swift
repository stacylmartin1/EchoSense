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

protocol TranslationService {
  func translateToEnglish(_ text: String) async throws -> String
}

enum TranslationServiceError: LocalizedError {
  case offlineTranslationUnavailable
  case modelNotLoaded

  var errorDescription: String? {
    switch self {
    case .offlineTranslationUnavailable:
      "Offline translation is not available yet."
    case .modelNotLoaded:
      "Local model is not loaded. Import a model to translate offline."
    }
  }
}

struct LocalTranslationService: TranslationService {
  let localLLM: LocalLLMClient

  func translateToEnglish(_ text: String) async throws -> String {
    guard localLLM.isReady else {
      throw TranslationServiceError.modelNotLoaded
    }

    let prompt = """
    You are an expert translator. Translate the following text into clear, fluent English. 
    Do not add any explanations, intros, warnings, or notes. Return ONLY the translated English text.
    
    Text to translate:
    \(text)
    """

    let stream = try await localLLM.generate(prompt: prompt, image: nil)
    var result = ""
    for try await chunk in stream {
      result += chunk
    }
    
    let trimmed = result.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty {
      throw TranslationServiceError.offlineTranslationUnavailable
    }
    return trimmed
  }
}
