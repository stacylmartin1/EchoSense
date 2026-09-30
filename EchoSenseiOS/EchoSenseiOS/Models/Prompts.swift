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

enum Prompts {
  static func navigation(customPrompt: String?, style: ResponseStyle) -> String {
    let detail: String
    if style == .verbose {
      detail = """
      Provide a highly detailed, comprehensive navigation guide of the environment. Describe the scene extensively to help a visually impaired person navigate with high awareness of their surroundings. Detail the floor surface, wall layouts, pathway clearings, and all detectable obstacles. Provide precise descriptions of objects (size, orientation) and state their exact relative directions and approximate distances. Highlight potential safety hazards, floor level changes, and safe passage routes. Avoid generic summaries; be descriptive and thorough.
      """
    } else {
      detail = """
      Provide a clear, navigation-focused description of the environment in two concise paragraphs. Focus strictly on helping a visually impaired person navigate safely. In the first paragraph, describe the general layout, the main path ahead, and any immediate obstacles or hazards. In the second paragraph, list key objects of interest (such as doors, furniture, or stairs) and their relative positions (left, right, or center). Avoid flowery language or non-essential visual details.
      """
    }

    let leftRightDirection = "For this description, left refers strictly to the left side of the image as displayed on the screen, and right refers strictly to the right side of the image. Nothing in the image is behind the user."

    if let customPrompt, !customPrompt.isEmpty {
      let customDetail: String
      if style == .verbose {
        customDetail = "Answer the user's request thoroughly using complete sentences. Include precise locations, approximate distances, and any relevant obstacles or safety hazards that would help a visually impaired person act on the answer."
      } else {
        customDetail = "Focus on exactly what the user requested. Give enough location and safety detail for a visually impaired person to act on the answer, but keep it concise, natural, and conversational."
      }
      return "You are an assistant for visually impaired people, helping them navigate indoor or outdoor environments safely. The user's request is: \"\(customPrompt)\". Analyze only the visible image to answer that request. \(leftRightDirection) \(customDetail) None of these instructions should be repeated in the response."
    }

    return "You are an assistant for visually impaired people, helping them navigate indoor or outdoor environments safely. Describe what you see in this image for a visually impaired person who needs to navigate safely. \(leftRightDirection) \(detail) None of these instructions should be repeated in the response."
  }

  static func documentOCR() -> String {
    "Extract all text from this image exactly as written. Preserve the original language and formatting. Return only the extracted text."
  }

  static func currency(evidence: CurrencyOCREvidence) -> String {
    let supported = CurrencyCatalog.currencies.map { definition in
      "\(definition.code): \(definition.denominations.sorted().map(String.init).joined(separator: ", "))"
    }.joined(separator: "; ")
    return """
    Inspect the bank note image using the OCR evidence below. Supported bank notes are limited to USD, CAD, EUR, GBP, and CHF.
    Valid denominations are: \(supported).

    \(evidence.promptSummary)

    Return exactly one line in this format and no other text:
    CURRENCY=USD; DENOMINATION=20; IMAGE_USABLE=YES

    Use CURRENCY=UNSUPPORTED when the note is clearly another currency. Use CURRENCY=UNKNOWN and DENOMINATION=0 when uncertain. IMAGE_USABLE must be NO for blur, glare, severe cropping, or when no bank note is visible. Do not invent a denomination from serial numbers or series years.
    """
  }
}

struct CurrencyDefinition {
  let code: String
  let name: String
  let denominations: Set<Int>
  let issuerPhrases: [String]
  let denominationWords: [String: Int]
}

enum CurrencyCatalog {
  static let currencies: [CurrencyDefinition] = [
    .init(
      code: "USD", name: "US Dollars", denominations: [1, 2, 5, 10, 20, 50, 100],
      issuerPhrases: [
        "FEDERAL RESERVE", "FEDERAL RESERVE NOTE", "UNITED STATES",
        "UNITED STATES OF AMERICA", "THE UNITED STATES OF AMERICA"
      ],
      denominationWords: ["ONE HUNDRED": 100, "FIFTY": 50, "TWENTY": 20, "TEN": 10, "FIVE": 5, "TWO": 2, "ONE": 1]
    ),
    .init(
      code: "CAD", name: "Canadian Dollars", denominations: [5, 10, 20, 50, 100],
      issuerPhrases: ["BANK OF CANADA", "BANQUE DU CANADA"],
      denominationWords: ["ONE HUNDRED": 100, "FIFTY": 50, "TWENTY": 20, "TEN": 10, "FIVE": 5]
    ),
    .init(
      code: "EUR", name: "Euros", denominations: [5, 10, 20, 50, 100, 200, 500],
      issuerPhrases: ["EURO"], denominationWords: [:]
    ),
    .init(
      code: "GBP", name: "British Pounds", denominations: [5, 10, 20, 50],
      issuerPhrases: ["BANK OF ENGLAND"],
      denominationWords: ["FIFTY POUNDS": 50, "TWENTY POUNDS": 20, "TEN POUNDS": 10, "FIVE POUNDS": 5]
    ),
    .init(
      code: "CHF", name: "Swiss Francs", denominations: [10, 20, 50, 100, 200, 1000],
      issuerPhrases: ["SCHWEIZERISCHE NATIONALBANK", "BANQUE NATIONALE SUISSE", "BANCA NAZIONALE SVIZZERA", "BANCA NAZIUNALA SVIZRA"],
      denominationWords: [:]
    ),
  ]

  static func definition(for code: String) -> CurrencyDefinition? {
    currencies.first { $0.code == code.uppercased() }
  }
}

struct CurrencyOCREvidence {
  let rawText: String
  let countryCodes: Set<String>
  let denominations: Set<Int>
  let confidence: Float

  init(result: OCRResult) {
    rawText = result.text.trimmingCharacters(in: .whitespacesAndNewlines)
    let normalized = rawText.uppercased().replacingOccurrences(
      of: "\\s+", with: " ", options: .regularExpression
    )
    func phrasePattern(_ phrase: String) -> String {
      "(?<![A-Z0-9])\(NSRegularExpression.escapedPattern(for: phrase))(?![A-Z0-9])"
    }
    let detectedCountryCodes = Set(CurrencyCatalog.currencies.compactMap { definition in
      definition.issuerPhrases.contains {
        normalized.range(of: phrasePattern($0), options: .regularExpression) != nil
      } ? definition.code : nil
    })
    countryCodes = detectedCountryCodes
    let applicable = detectedCountryCodes.isEmpty
      ? CurrencyCatalog.currencies
      : CurrencyCatalog.currencies.filter { detectedCountryCodes.contains($0.code) }
    let allowed = Set(applicable.flatMap(\.denominations))
    var found = Set(
      normalized.components(separatedBy: CharacterSet.alphanumerics.inverted)
        .compactMap(Int.init)
        .filter(allowed.contains)
    )
    var remainingWords = normalized
    let wordEvidence = applicable
      .flatMap(\.denominationWords)
      .sorted { $0.key.count > $1.key.count }
    for (words, value) in wordEvidence {
      let pattern = phrasePattern(words)
      guard remainingWords.range(of: pattern, options: .regularExpression) != nil else { continue }
      found.insert(value)
      remainingWords = remainingWords.replacingOccurrences(
        of: pattern, with: " ", options: .regularExpression
      )
    }
    denominations = found
    let lineConfidences = result.lines.map(\.confidence).filter { $0 > 0 }
    confidence = lineConfidences.isEmpty
      ? 0
      : lineConfidences.reduce(0, +) / Float(lineConfidences.count)
  }

  var promptSummary: String {
    """
    OCR text:
    \(String(rawText.prefix(1_200)).isEmpty ? "(none)" : String(rawText.prefix(1_200)))
    Detected supported currency codes: \(countryCodes.sorted().joined(separator: ", ").isEmpty ? "none" : countryCodes.sorted().joined(separator: ", "))
    Detected valid denominations: \(denominations.sorted().map(String.init).joined(separator: ", ").isEmpty ? "none" : denominations.sorted().map(String.init).joined(separator: ", "))
    OCR confidence: \(String(format: "%.2f", confidence))
    """
  }
}

struct CurrencyVisualAssessment {
  let code: String?
  let denomination: Int?
  let imageUsable: Bool
  let explicitlyUnsupported: Bool

  init?(response: String) {
    var values: [String: String] = [:]
    for field in response.uppercased().components(separatedBy: CharacterSet(charactersIn: ";\n")) {
      let pieces = field.split(separator: "=", maxSplits: 1).map {
        $0.trimmingCharacters(in: .whitespacesAndNewlines)
      }
      if pieces.count == 2 { values[pieces[0]] = pieces[1] }
    }
    guard !values.isEmpty else { return nil }
    let rawCode = values["CURRENCY"]
    code = (rawCode == "UNKNOWN" || rawCode == "UNSUPPORTED") ? nil : rawCode
    denomination = values["DENOMINATION"].flatMap { Int($0.filter(\.isNumber)) }
    imageUsable = values["IMAGE_USABLE"] == "YES"
    explicitlyUnsupported = rawCode == "UNSUPPORTED"
  }
}

enum CurrencyDecision {
  static func spokenText(evidence: CurrencyOCREvidence, visual: CurrencyVisualAssessment?) -> String {
    guard let visual, visual.imageUsable else {
      return "Unable to identify the bank note. Hold it flat, move closer, and try again."
    }
    if visual.explicitlyUnsupported {
      return "This note was not recognized as a supported currency. Try the other side or improve the lighting."
    }
    guard let code = visual.code, let definition = CurrencyCatalog.definition(for: code) else {
      return "This note was not recognized as a supported currency. Try the other side or improve the lighting."
    }
    guard let denomination = visual.denomination,
          definition.denominations.contains(denomination) else {
      return "The currency may be \(definition.name), but the denomination is unclear. Show the other side and try again."
    }
    let countryAgrees = evidence.countryCodes.contains(code)
    let denominationAgrees = evidence.denominations.contains(denomination)
    let countryConflicts = !evidence.countryCodes.isEmpty && !countryAgrees
    let denominationConflicts = countryAgrees &&
      !evidence.denominations.isEmpty &&
      !denominationAgrees
    if countryConflicts || denominationConflicts {
      return "The image and printed text do not agree. Show the other side of the note and try again."
    }
    if !countryAgrees && !denominationAgrees {
      return "Likely \(denomination) \(definition.name)."
    }
    return "\(denomination) \(definition.name)."
  }
}
