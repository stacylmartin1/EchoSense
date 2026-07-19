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
