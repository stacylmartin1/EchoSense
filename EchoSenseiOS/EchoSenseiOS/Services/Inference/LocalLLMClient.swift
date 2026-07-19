import Foundation
import OSLog
import UIKit

#if canImport(LiteRTLM)
import LiteRTLM
#endif

protocol LocalLLMClient: AnyObject {
  var isReady: Bool { get }
  var diagnosticHandler: (@Sendable (String) -> Void)? { get set }
  func load(modelURL: URL) async throws
  func generate(prompt: String, image: UIImage?) async throws -> AsyncThrowingStream<String, Error>
  func cancelGeneration()
}

enum LocalLLMError: LocalizedError {
  case modelNotLoaded
  case runtimeUnavailable
  case emptyResponse
  case imageInputUnavailable
  case modelTooLarge(sizeInGB: Double, limitInGB: Double)

  var errorDescription: String? {
    switch self {
    case .modelNotLoaded: "Local model is not loaded."
    case .runtimeUnavailable: "The local LLM runtime is not available. Install LiteRTLM package and add a compatible model."
    case .emptyResponse: "The local model returned an empty response."
    case .imageInputUnavailable:
      "Image input is not available with the current iOS local model runtime. Text-only local model testing is available."
    case let .modelTooLarge(sizeInGB, limitInGB):
      String(format: "This model is %.1f GB. The current iOS loader limit is %.1f GB to avoid memory termination.", sizeInGB, limitInGB)
    }
  }
}

final class LiteRTLMClient: LocalLLMClient, @unchecked Sendable {
  private static let logger = Logger(subsystem: "com.terranet.echosense.ios", category: "LiteRTLM")
  private static let visualTokenBudget: Int32 = 140

  private(set) var isReady = false
  private let diagnosticLock = NSLock()
  private var storedDiagnosticHandler: (@Sendable (String) -> Void)?
  var diagnosticHandler: (@Sendable (String) -> Void)? {
    get { diagnosticLock.withLock { storedDiagnosticHandler } }
    set { diagnosticLock.withLock { storedDiagnosticHandler = newValue } }
  }
  private var modelURL: URL?
  private let maximumModelSizeInBytes: Int64 = 8_000_000_000 // 8 GB limit since memory limit is increased
  private var imageInputEnabled = true

  #if canImport(LiteRTLM)
  private var engine: Engine?
  private var conversation: Conversation?
  private let generationLock = NSLock()
  private var activeGenerationID: UUID?
  private var activeGenerationConversation: Conversation?
  private var requiresEngineRecovery = false
  #endif

  func load(modelURL: URL) async throws {
    let modelSizeInBytes = try modelURL.totalAllocatedSizeInBytes()
    if modelSizeInBytes > maximumModelSizeInBytes {
      throw LocalLLMError.modelTooLarge(
        sizeInGB: Double(modelSizeInBytes) / 1_000_000_000,
        limitInGB: Double(maximumModelSizeInBytes) / 1_000_000_000
      )
    }

    self.modelURL = modelURL
    #if canImport(LiteRTLM)
    SystemMemoryHelper.logMemoryStatus(stage: "INITIAL load request")

    // Release previous engine and conversation from memory before loading a new one
    self.conversation = nil
    self.engine = nil
    self.isReady = false
    
    // Explicitly suggest system garbage collection / cleanup
    SystemMemoryHelper.logMemoryStatus(stage: "AFTER releasing old model")

    // Try cascading fallback configurations
    let configs: [(backend: Backend, visionBackend: Backend?, imageEnabled: Bool)] = [
      // 1. GPU engine + GPU vision (Primary: Pure GPU execution)
      (backend: .gpu, visionBackend: .gpu, imageEnabled: true),
      // 2. GPU engine + CPU vision (Stable hybrid: GPU for text, CPU for Vision to avoid Metal compile timeouts)
      (backend: .gpu, visionBackend: .cpu(), imageEnabled: true),
      // 3. CPU engine + CPU vision (Fallback for complete CPU execution)
      (backend: .cpu(), visionBackend: .cpu(), imageEnabled: true),
      // 4. GPU engine only (for text-only models on GPU)
      (backend: .gpu, visionBackend: nil, imageEnabled: false),
      // 5. CPU engine only (ultimate safe fallback)
      (backend: .cpu(), visionBackend: nil, imageEnabled: false)
    ]

    var lastError: Error?
    for configTuple in configs {
      do {
        SystemMemoryHelper.logMemoryStatus(stage: "START load configuration (backend: \(configTuple.backend), visionBackend: \(String(describing: configTuple.visionBackend)))")
        let config = try EngineConfig(
          modelPath: modelURL.path,
          backend: configTuple.backend,
          visionBackend: configTuple.visionBackend,
          maxNumTokens: 2048,
          cacheDir: NSTemporaryDirectory()
        )
        let engine = Engine(engineConfig: config)
        
        try await engine.initialize()
        
        // Configure sampler config with sensible default hyperparameters
        let samplerConfig = try SamplerConfig(
          topK: 64,
          topP: 0.95,
          temperature: 1.0
        )
        let conversationConfig = ConversationConfig(samplerConfig: samplerConfig)
        let conversation = try await engine.createConversation(with: conversationConfig)
        
        self.engine = engine
        self.conversation = conversation
        self.imageInputEnabled = configTuple.imageEnabled
        self.isReady = true
        
        SystemMemoryHelper.logMemoryStatus(stage: "SUCCESS load configuration")
        // Successfully initialized!
        return
      } catch {
        lastError = error
        SystemMemoryHelper.logMemoryStatus(stage: "FAILED load configuration with error: \(error)")
        // Continue to next configuration
      }
    }
    
    // If all configurations failed, throw the last error
    SystemMemoryHelper.logMemoryStatus(stage: "ALL load configurations failed")
    throw lastError ?? LocalLLMError.modelNotLoaded
    #else
    throw LocalLLMError.runtimeUnavailable
    #endif
  }

  func generate(prompt: String, image: UIImage?) async throws -> AsyncThrowingStream<String, Error> {
    diagnose("Generation requested")
    guard isReady else { throw LocalLLMError.modelNotLoaded }
    if image != nil && !imageInputEnabled {
      throw LocalLLMError.imageInputUnavailable
    }

    #if canImport(LiteRTLM)
    try await recoverGPUAfterCancellationIfNeeded()
    guard let engine else { throw LocalLLMError.modelNotLoaded }
    ExperimentalFlags.optIntoExperimentalAPIs()
    ExperimentalFlags.visualTokenBudget = Self.visualTokenBudget
    diagnose("Configured Gemma visual-token budget: \(Self.visualTokenBudget)")
    let generationID = beginGeneration()
    diagnose("Generation registered", generationID: generationID)
    return AsyncThrowingStream(String.self) { continuation in
      let task = Task.detached(priority: .userInitiated) { [self] in
        let startedAt = ContinuousClock.now
        diagnose(
          "Inference worker started on \(Thread.isMainThread ? "main" : "background") thread",
          generationID: generationID
        )
        let watchdog = Task.detached { [weak self] in
          var elapsedSeconds = 0
          while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled else { return }
            elapsedSeconds += 5
            self?.diagnose(
              "Watchdog: native generation still active after \(elapsedSeconds)s",
              generationID: generationID,
              publish: false
            )
          }
        }
        defer { watchdog.cancel() }

        do {
          diagnose("Preparing image input", generationID: generationID)
          let message: Message
          if let image = image {
            // Resize image to 384px maximum dimension to reduce patch count and prevent OOM/timeouts
            let scaledImage = image.scaledToFit(maxDimension: 384)
            guard let pngData = scaledImage.pngData() else {
              throw LocalLLMError.emptyResponse
            }
            diagnose("Image ready (\(pngData.count) bytes)", generationID: generationID)
            message = Message(contents: [.imageData(pngData), .text(prompt)])
          } else {
            message = Message(prompt)
          }

          let samplerConfig = try SamplerConfig(
            topK: 64,
            topP: 0.95,
            temperature: 1.0
          )
          let conversationConfig = ConversationConfig(samplerConfig: samplerConfig)
          diagnose("Creating GPU conversation", generationID: generationID)
          let freshConversation = try await engine.createConversation(with: conversationConfig)
          diagnose("GPU conversation created", generationID: generationID)
          guard install(freshConversation, for: generationID) else {
            try? freshConversation.cancel()
            throw CancellationError()
          }
          defer { finishGeneration(generationID) }

          try Task.checkCancellation()

          diagnose("Starting vision prefill and token stream", generationID: generationID)
          let stream = freshConversation.sendMessageStream(message)
          var receivedFirstToken = false
          for try await chunk in stream {
            guard !Task.isCancelled else {
              continuation.finish(throwing: CancellationError())
              return
            }
            if !receivedFirstToken {
              receivedFirstToken = true
              let elapsed = startedAt.duration(to: .now)
              diagnose("First token received after \(elapsed)", generationID: generationID)
            }
            continuation.yield(chunk.toString)
          }
          diagnose("Native stream finished", generationID: generationID)
          continuation.finish()
        } catch {
          diagnose("Generation ended with error: \(error)", generationID: generationID)
          continuation.finish(throwing: error)
        }
      }

      continuation.onTermination = { termination in
        switch termination {
        case .cancelled:
          self.diagnose("Swift stream was cancelled; requesting native cancel", generationID: generationID)
          self.cancelGeneration(generationID)
          task.cancel()
        case .finished(let error):
          if let error {
            self.diagnose("Swift stream finished with error: \(error)", generationID: generationID)
          } else {
            self.diagnose("Swift stream finished normally; no native cancel needed", generationID: generationID)
          }
        }
      }
    }
    #else
    throw LocalLLMError.runtimeUnavailable
    #endif
  }

  func cancelGeneration() {
    #if canImport(LiteRTLM)
    diagnose("Cancellation requested")
    let generationID = generationLock.withLock { activeGenerationID }
    if let generationID {
      cancelGeneration(generationID)
    }
    #endif
  }

  #if canImport(LiteRTLM)
  private func beginGeneration() -> UUID {
    let generationID = UUID()
    let previousConversation = generationLock.withLock { () -> Conversation? in
      let previousConversation = activeGenerationConversation
      activeGenerationID = generationID
      activeGenerationConversation = nil
      return previousConversation
    }
    if let previousConversation {
      diagnose("Cancelling previous native conversation", generationID: generationID)
      try? previousConversation.cancel()
    }
    return generationID
  }

  private func install(_ conversation: Conversation, for generationID: UUID) -> Bool {
    generationLock.withLock {
      guard activeGenerationID == generationID else { return false }
      activeGenerationConversation = conversation
      return true
    }
  }

  private func cancelGeneration(_ generationID: UUID) {
    let conversation = generationLock.withLock { () -> Conversation? in
      guard activeGenerationID == generationID else { return nil }
      activeGenerationID = nil
      let conversation = activeGenerationConversation
      activeGenerationConversation = nil
      if conversation != nil {
        // LiteRT-LM 0.13.1 can report its stream finished while retaining an
        // active callback-pool task after cancellation. Do not reuse that engine.
        requiresEngineRecovery = true
      }
      return conversation
    }
    if let conversation {
      diagnose("Calling LiteRT Conversation.cancel()", generationID: generationID)
      try? conversation.cancel()
      diagnose("LiteRT Conversation.cancel() returned", generationID: generationID)
    } else {
      diagnose("Cancellation recorded before conversation creation", generationID: generationID)
    }
  }

  private func finishGeneration(_ generationID: UUID) {
    generationLock.withLock {
      guard activeGenerationID == generationID else { return }
      activeGenerationID = nil
      activeGenerationConversation = nil
    }
  }

  private func recoverGPUAfterCancellationIfNeeded() async throws {
    let needsRecovery = generationLock.withLock { requiresEngineRecovery }
    guard needsRecovery else { return }
    guard let modelURL else { throw LocalLLMError.modelNotLoaded }

    diagnose("Interrupted generation left LiteRT callback active; rebuilding GPU engine")
    try await Task.detached(priority: .userInitiated) { [self] in
      try await load(modelURL: modelURL)
    }.value
    generationLock.withLock {
      requiresEngineRecovery = false
    }
    diagnose("GPU engine recovery complete")
  }
  #endif

  private func diagnose(_ message: String, generationID: UUID? = nil, publish: Bool = true) {
    let id = generationID.map { String($0.uuidString.prefix(8)) } ?? "--------"
    Self.logger.notice("[generation \(id, privacy: .public)] \(message, privacy: .public)")
    if publish {
      let handler = diagnosticLock.withLock { storedDiagnosticHandler }
      handler?(message)
    }
  }
}

// Keep typealias for backward compatibility in case other targets reference the old class name
typealias MediaPipeLLMClient = LiteRTLMClient

private extension UIImage {
  func scaledToFit(maxDimension: CGFloat) -> UIImage {
    let size = self.size
    let widthRatio = maxDimension / size.width
    let heightRatio = maxDimension / size.height
    // Draw the image in a context to bake the rotation/orientation into the pixels
    let ratio = min(1.0, min(widthRatio, heightRatio))
    
    let newSize = CGSize(width: size.width * ratio, height: size.height * ratio)
    let format = UIGraphicsImageRendererFormat.default()
    format.scale = 1.0 // Keep pixel dimensions exact
    
    return UIGraphicsImageRenderer(size: newSize, format: format).image { _ in
      self.draw(in: CGRect(origin: .zero, size: newSize))
    }
  }

  var renderedCGImage: CGImage? {
    if let cgImage {
      return cgImage
    }

    let format = UIGraphicsImageRendererFormat.default()
    format.scale = scale
    let renderedImage = UIGraphicsImageRenderer(size: size, format: format).image { _ in
      draw(in: CGRect(origin: .zero, size: size))
    }
    return renderedImage.cgImage
  }
}

private extension URL {
  func totalAllocatedSizeInBytes() throws -> Int64 {
    let fileManager = FileManager.default
    let resourceValues = try resourceValues(forKeys: [.isDirectoryKey, .totalFileAllocatedSizeKey, .fileAllocatedSizeKey])

    if resourceValues.isDirectory == true {
      guard let enumerator = fileManager.enumerator(
        at: self,
        includingPropertiesForKeys: [.totalFileAllocatedSizeKey, .fileAllocatedSizeKey],
        options: [.skipsHiddenFiles]
      ) else {
        return 0
      }

      var total: Int64 = 0
      for case let fileURL as URL in enumerator {
        let values = try fileURL.resourceValues(forKeys: [.totalFileAllocatedSizeKey, .fileAllocatedSizeKey])
        total += Int64(values.totalFileAllocatedSize ?? values.fileAllocatedSize ?? 0)
      }
      return total
    }

    return Int64(resourceValues.totalFileAllocatedSize ?? resourceValues.fileAllocatedSize ?? 0)
  }
}

struct LocalModelStore {
  private let fileManager = FileManager.default

  var importedModelURL: URL? {
    guard let contents = try? fileManager.contentsOfDirectory(
      at: modelDirectoryURL,
      includingPropertiesForKeys: [.contentModificationDateKey],
      options: [.skipsHiddenFiles]
    ) else {
      return nil
    }

    return contents.sorted { lhs, rhs in
      let lhsDate = (try? lhs.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
      let rhsDate = (try? rhs.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
      return lhsDate > rhsDate
    }.first
  }

  func importModel(from sourceURL: URL) throws -> URL {
    let didStartAccessing = sourceURL.startAccessingSecurityScopedResource()
    defer {
      if didStartAccessing {
        sourceURL.stopAccessingSecurityScopedResource()
      }
    }

    try fileManager.createDirectory(at: modelDirectoryURL, withIntermediateDirectories: true)
    let destinationURL = uniqueDestinationURL(for: sourceURL.lastPathComponent)

    if fileManager.fileExists(atPath: destinationURL.path) {
      try fileManager.removeItem(at: destinationURL)
    }

    try fileManager.copyItem(at: sourceURL, to: destinationURL)
    return destinationURL
  }

  private var modelDirectoryURL: URL {
    let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
    return documentsURL.appendingPathComponent("LocalModels", isDirectory: true)
  }

  private func uniqueDestinationURL(for filename: String) -> URL {
    let sanitizedFilename = filename.isEmpty ? "local-model" : filename
    let baseURL = modelDirectoryURL.appendingPathComponent(sanitizedFilename)
    guard fileManager.fileExists(atPath: baseURL.path) else { return baseURL }

    let name = baseURL.deletingPathExtension().lastPathComponent
    let pathExtension = baseURL.pathExtension
    let timestamp = Int(Date().timeIntervalSince1970)
    let uniqueName = pathExtension.isEmpty ? "\(name)-\(timestamp)" : "\(name)-\(timestamp).\(pathExtension)"
    return modelDirectoryURL.appendingPathComponent(uniqueName)
  }
}

import MachO

struct SystemMemoryHelper {
  static var physicalMemoryGB: Double {
    return Double(ProcessInfo.processInfo.physicalMemory) / (1024 * 1024 * 1024)
  }

  static var appMemoryFootprintMB: Double {
    var info = mach_task_basic_info()
    var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<integer_t>.size)
    let kerr: kern_return_t = withUnsafeMutablePointer(to: &info) {
      $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
        task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
      }
    }
    if kerr == KERN_SUCCESS {
      return Double(info.resident_size) / (1024 * 1024)
    }
    return 0.0
  }

  static func logMemoryStatus(stage: String) {
    let physical = String(format: "%.2f GB", physicalMemoryGB)
    let usage = String(format: "%.2f MB", appMemoryFootprintMB)
    print("🤖 [Memory Monitor] \(stage) | Device RAM: \(physical) | App Footprint: \(usage)")
  }
}
