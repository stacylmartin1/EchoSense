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

#if canImport(LiteRTLM)
internal import LiteRTLM
#endif

public enum EchoSenseGenAIAvailability {
  public static var isLiteRTLMAvailable: Bool {
    #if canImport(LiteRTLM)
    true
    #else
    false
    #endif
  }

  @available(*, deprecated, renamed: "isLiteRTLMAvailable")
  public static var isMediaPipeGenAIAvailable: Bool {
    isLiteRTLMAvailable
  }
}

public final class EchoSenseGenAIEngine {
  public private(set) var modelURL: URL?

  #if canImport(LiteRTLM)
  private var engine: Engine?
  #endif

  public init() {}

  public func load(modelURL: URL) throws {
    self.modelURL = modelURL
    #if canImport(LiteRTLM)
    let config = try EngineConfig(
      modelPath: modelURL.path,
      backend: .gpu,
      cacheDir: NSTemporaryDirectory()
    )
    self.engine = Engine(engineConfig: config)
    #endif
  }
}
