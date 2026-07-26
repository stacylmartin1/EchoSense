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
import UIKit

enum CloudVisionError: LocalizedError {
  case invalidKey
  case invalidImage
  case providerError(String)
  case invalidResponse

  var errorDescription: String? {
    switch self {
    case .invalidKey: "The provider rejected this API key."
    case .invalidImage: "The camera image could not be prepared for online analysis."
    case .providerError(let message): message
    case .invalidResponse: "The online provider returned an unreadable response."
    }
  }
}

struct CloudVisionClient {
  let provider: CloudProvider
  let apiKey: String

  func validateKey() async throws {
    let url: URL
    var request: URLRequest
    switch provider {
    case .none:
      throw CloudVisionError.invalidKey
    case .gemini:
      url = URL(string: "https://generativelanguage.googleapis.com/v1beta/models")!
      request = URLRequest(url: url)
      request.setValue(apiKey, forHTTPHeaderField: "x-goog-api-key")
    case .openAICompatible:
      url = URL(string: "https://api.openai.com/v1/models")!
      request = URLRequest(url: url)
      request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
    }
    request.timeoutInterval = 20
    let (_, response) = try await URLSession.shared.data(for: request)
    try Self.validate(response)
  }

  func analyze(image: UIImage, prompt: String) async throws -> String {
    try await generate(prompt: prompt, image: image)
  }

  func generate(prompt: String, image: UIImage? = nil) async throws -> String {
    let imageData = image?.jpegData(compressionQuality: 0.82)
    if image != nil && imageData == nil { throw CloudVisionError.invalidImage }
    switch provider {
    case .none:
      throw CloudVisionError.invalidKey
    case .gemini:
      return try await analyzeWithGemini(imageData: imageData, prompt: prompt)
    case .openAICompatible:
      return try await analyzeWithOpenAI(imageData: imageData, prompt: prompt)
    }
  }

  private func analyzeWithGemini(imageData: Data?, prompt: String) async throws -> String {
    let url = URL(string: "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent")!
    let body: [String: Any] = [
      "contents": [["parts": geminiParts(prompt: prompt, imageData: imageData)]],
      "generationConfig": ["temperature": 0.1, "maxOutputTokens": 4096],
    ]
    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.timeoutInterval = 60
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.setValue(apiKey, forHTTPHeaderField: "x-goog-api-key")
    request.httpBody = try JSONSerialization.data(withJSONObject: body)
    let (data, response) = try await URLSession.shared.data(for: request)
    try Self.validate(response, data: data)
    guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
          let candidates = json["candidates"] as? [[String: Any]],
          let content = candidates.first?["content"] as? [String: Any],
          let parts = content["parts"] as? [[String: Any]] else {
      throw CloudVisionError.invalidResponse
    }
    let text = parts.compactMap { $0["text"] as? String }.joined(separator: "\n")
      .trimmingCharacters(in: .whitespacesAndNewlines)
    guard !text.isEmpty else { throw CloudVisionError.invalidResponse }
    return text
  }

  private func analyzeWithOpenAI(imageData: Data?, prompt: String) async throws -> String {
    let url = URL(string: "https://api.openai.com/v1/responses")!
    var content: [[String: Any]] = [["type": "input_text", "text": prompt]]
    if let imageData {
      content.append([
        "type": "input_image",
        "image_url": "data:image/jpeg;base64,\(imageData.base64EncodedString())",
      ])
    }
    let body: [String: Any] = [
      "model": "gpt-5.4-mini",
      "input": [[
        "role": "user",
        "content": content,
      ]],
      "max_output_tokens": 4096,
    ]
    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.timeoutInterval = 60
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
    request.httpBody = try JSONSerialization.data(withJSONObject: body)
    let (data, response) = try await URLSession.shared.data(for: request)
    try Self.validate(response, data: data)
    guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
          let output = json["output"] as? [[String: Any]] else {
      throw CloudVisionError.invalidResponse
    }
    let text = output
      .compactMap { $0["content"] as? [[String: Any]] }
      .flatMap { $0 }
      .filter { ($0["type"] as? String) == "output_text" }
      .compactMap { $0["text"] as? String }
      .joined(separator: "\n")
      .trimmingCharacters(in: .whitespacesAndNewlines)
    guard !text.isEmpty else { throw CloudVisionError.invalidResponse }
    return text
  }

  private func geminiParts(prompt: String, imageData: Data?) -> [[String: Any]] {
    var parts: [[String: Any]] = [["text": prompt]]
    if let imageData {
      parts.append([
        "inline_data": ["mime_type": "image/jpeg", "data": imageData.base64EncodedString()],
      ])
    }
    return parts
  }

  private static func validate(_ response: URLResponse, data: Data? = nil) throws {
    guard let http = response as? HTTPURLResponse else { throw CloudVisionError.invalidResponse }
    guard 200..<300 ~= http.statusCode else {
      if http.statusCode == 401 || http.statusCode == 403 { throw CloudVisionError.invalidKey }
      let providerMessage = data.flatMap(providerErrorMessage(from:))
      throw CloudVisionError.providerError(providerMessage ?? "Online analysis failed (HTTP \(http.statusCode)).")
    }
  }

  private static func providerErrorMessage(from data: Data) -> String? {
    guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let error = json["error"] as? [String: Any],
          let message = error["message"] as? String else { return nil }
    return String(message.prefix(240))
  }
}
