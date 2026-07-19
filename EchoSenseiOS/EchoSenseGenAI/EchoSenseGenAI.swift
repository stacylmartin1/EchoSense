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
