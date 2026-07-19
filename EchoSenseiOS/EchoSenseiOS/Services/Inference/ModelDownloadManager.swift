import CryptoKit
import Foundation
import UIKit

private let modelCatalogURL = URL(string: "https://models.echosense-ai.app/v1/models.json")!
private let backgroundSessionIdentifier = "com.terranet.echosense.ios.model-download"
private let parallelDownloadPartCount = 4

enum ModelDownloadPhase: Equatable {
  case checking
  case notInstalled
  case downloading
  case paused
  case verifying
  case installed
  case failed
}

enum ModelDownloadError: LocalizedError {
  case insufficientStorage(required: Int64, available: Int64)
  case sizeMismatch(expected: Int64, actual: Int64)
  case checksumMismatch
  case missingDownloadMetadata
  case moveFailed
  case rangeDownloadUnsupported

  var errorDescription: String? {
    switch self {
    case .insufficientStorage(let required, let available):
      "Not enough storage. \(required.formattedBytes) is required, but only \(available.formattedBytes) is available."
    case .sizeMismatch(let expected, let actual):
      "The model download is incomplete. Expected \(expected.formattedBytes), received \(actual.formattedBytes)."
    case .checksumMismatch:
      "The downloaded model failed its security check. Please download it again."
    case .missingDownloadMetadata:
      "The model download could not be restored. Please start it again."
    case .moveFailed:
      "The downloaded model could not be installed."
    case .rangeDownloadUnsupported:
      "The model server did not honor a ranged download request."
    }
  }
}

@MainActor
final class ModelDownloadManager: NSObject, ObservableObject {
  @Published private(set) var phase: ModelDownloadPhase = .checking
  @Published private(set) var availableModel: RemoteModelDescriptor?
  @Published private(set) var installedModelURL: URL?
  @Published private(set) var installedModelName: String?
  @Published private(set) var bytesDownloaded: Int64 = 0
  @Published private(set) var totalBytes: Int64 = 0
  @Published private(set) var bytesPerSecond: Int64 = 0
  @Published private(set) var estimatedRemainingSeconds: TimeInterval?
  @Published private(set) var errorMessage: String?
  @Published var allowsCellularDownload: Bool {
    didSet { UserDefaults.standard.set(allowsCellularDownload, forKey: "echosense.modelDownload.cellular") }
  }

  private lazy var session: URLSession = {
    let configuration = URLSessionConfiguration.background(withIdentifier: backgroundSessionIdentifier)
    configuration.sessionSendsLaunchEvents = true
    configuration.isDiscretionary = false
    configuration.waitsForConnectivity = true
    configuration.httpMaximumConnectionsPerHost = parallelDownloadPartCount
    return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
  }()
  private var activeTasks: [Int: URLSessionDownloadTask] = [:]
  private var taskBytes: [Int: Int64] = [:]
  private var lastSpeedSampleDate = Date()
  private var lastSpeedSampleBytes: Int64 = 0
  private var pauseOperationID: UUID?
  private var pendingPauseCallbacks = 0

  override init() {
    allowsCellularDownload = UserDefaults.standard.bool(forKey: "echosense.modelDownload.cellular")
    super.init()
    restoreInstalledModel()
    reconnectToBackgroundTask()
  }

  var progress: Double {
    guard totalBytes > 0 else { return 0 }
    return min(1, Double(bytesDownloaded) / Double(totalBytes))
  }

  var statusText: String {
    switch phase {
    case .checking: "Checking for model"
    case .notInstalled: "Model not installed"
    case .downloading:
      if bytesPerSecond > 0, let estimatedRemainingSeconds {
        "Downloading \(bytesDownloaded.formattedBytes) of \(totalBytes.formattedBytes) · \(bytesPerSecond.formattedBytes)/s · \(estimatedRemainingSeconds.formattedDuration) left"
      } else {
        "Downloading \(bytesDownloaded.formattedBytes) of \(totalBytes.formattedBytes)"
      }
    case .paused: "Download paused at \(bytesDownloaded.formattedBytes)"
    case .verifying: "Verifying downloaded model"
    case .installed: "Model installed"
    case .failed: errorMessage ?? "Model download failed"
    }
  }

  func refreshCatalog() async {
    if installedModelURL == nil { phase = .checking }
    errorMessage = nil
    do {
      let descriptor = try await fetchCatalog()
      availableModel = descriptor
      totalBytes = descriptor.sizeBytes
      if installedModelURL == nil, phase != .downloading, phase != .paused, phase != .verifying {
        phase = .notInstalled
      }
    } catch {
      if let cached = try? readCachedCatalog() {
        availableModel = cached
        totalBytes = cached.sizeBytes
        if installedModelURL == nil { phase = .notInstalled }
      } else {
        // A tiny built-in descriptor keeps first installation possible during a catalog outage.
        // The model data itself is never included in the application bundle.
        let fallback = Self.offlineFallbackModel
        availableModel = fallback
        totalBytes = fallback.sizeBytes
        if installedModelURL == nil { phase = .notInstalled }
      }
    }
  }

  func startDownload() async {
    guard let model = availableModel else {
      await refreshCatalog()
      guard availableModel != nil else { return }
      await startDownload()
      return
    }

    do {
      try model.validateForIOS()
      let required = max(model.sizeBytes + 1_073_741_824, Int64(Double(model.sizeBytes) * 1.25))
      let available = try availableStorageCapacity()
      guard available >= required else {
        throw ModelDownloadError.insufficientStorage(required: required, available: available)
      }
      try writePendingModel(model)
      errorMessage = nil
      totalBytes = model.sizeBytes

      try? FileManager.default.removeItem(at: legacyResumeDataURL)
      activeTasks.removeAll()
      taskBytes = completedPartBytes(for: model)
      updateAggregateProgress()
      resetSpeedSample()

      for part in downloadParts(for: model) where taskBytes[part.index] != part.length {
        let task: URLSessionDownloadTask
        if let resumeData = try? Data(contentsOf: partResumeDataURL(part.index)) {
          task = session.downloadTask(withResumeData: resumeData)
        } else {
          var request = URLRequest(url: model.url)
          request.setValue("bytes=\(part.start)-\(part.end)", forHTTPHeaderField: "Range")
          request.allowsCellularAccess = allowsCellularDownload
          request.allowsExpensiveNetworkAccess = allowsCellularDownload
          request.allowsConstrainedNetworkAccess = true
          task = session.downloadTask(with: request)
        }
        task.taskDescription = String(part.index)
        activeTasks[part.index] = task
      }
      phase = .downloading
      if activeTasks.isEmpty {
        await finishParallelDownload()
      } else {
        activeTasks.values.forEach { $0.resume() }
      }
    } catch {
      fail(error)
    }
  }

  func pauseDownload() {
    guard !activeTasks.isEmpty else { return }
    let tasks = activeTasks
    activeTasks.removeAll()
    let operationID = UUID()
    pauseOperationID = operationID
    pendingPauseCallbacks = tasks.count
    for (index, task) in tasks {
      let resumeURL = partResumeDataURL(index)
      task.cancel { [weak self] resumeData in
        Task { @MainActor in
          guard let self, self.pauseOperationID == operationID else { return }
          if let resumeData { try? resumeData.write(to: resumeURL, options: .atomic) }
          self.pendingPauseCallbacks -= 1
          if self.pendingPauseCallbacks == 0 {
            self.pauseOperationID = nil
            self.bytesPerSecond = 0
            self.estimatedRemainingSeconds = nil
            self.phase = .paused
          }
        }
      }
    }
  }

  func cancelDownload() {
    pauseOperationID = nil
    pendingPauseCallbacks = 0
    activeTasks.values.forEach { $0.cancel() }
    activeTasks.removeAll()
    removeDownloadArtifacts()
    try? FileManager.default.removeItem(at: pendingModelURL)
    bytesDownloaded = 0
    bytesPerSecond = 0
    estimatedRemainingSeconds = nil
    phase = installedModelURL == nil ? .notInstalled : .installed
  }

  func deleteInstalledModel() {
    guard let installedModelURL else { return }
    try? FileManager.default.removeItem(at: installedModelURL.deletingLastPathComponent())
    try? FileManager.default.removeItem(at: installedMetadataURL)
    self.installedModelURL = nil
    installedModelName = nil
    bytesDownloaded = 0
    phase = .notInstalled
  }

  private func fetchCatalog() async throws -> RemoteModelDescriptor {
    var request = URLRequest(url: modelCatalogURL)
    request.cachePolicy = .reloadRevalidatingCacheData
    request.timeoutInterval = 30
    let (data, response) = try await URLSession.shared.data(for: request)
    guard let http = response as? HTTPURLResponse, 200..<300 ~= http.statusCode else {
      throw ModelCatalogError.unavailable
    }
    let manifest = try JSONDecoder().decode(ModelManifest.self, from: data)
    guard manifest.schemaVersion == 1 else {
      throw ModelCatalogError.invalidManifest("Unsupported schema version.")
    }
    guard let model = manifest.models.first(where: { $0.recommended && $0.supportedPlatforms.contains("ios") })
      ?? manifest.models.first(where: { $0.supportedPlatforms.contains("ios") }) else {
      throw ModelCatalogError.noCompatibleModel
    }
    try model.validateForIOS()
    try FileManager.default.createDirectory(at: modelSupportDirectory, withIntermediateDirectories: true)
    try data.write(to: cachedCatalogURL, options: .atomic)
    return model
  }

  private func readCachedCatalog() throws -> RemoteModelDescriptor {
    let data = try Data(contentsOf: cachedCatalogURL)
    let manifest = try JSONDecoder().decode(ModelManifest.self, from: data)
    guard let model = manifest.models.first(where: { $0.supportedPlatforms.contains("ios") }) else {
      throw ModelCatalogError.noCompatibleModel
    }
    try model.validateForIOS()
    return model
  }

  private func installDownloadedFile(_ stagedURL: URL) async {
    phase = .verifying
    do {
      let model = try readPendingModel()
      let values = try stagedURL.resourceValues(forKeys: [.fileSizeKey])
      let actualSize = Int64(values.fileSize ?? 0)
      guard actualSize == model.sizeBytes else {
        throw ModelDownloadError.sizeMismatch(expected: model.sizeBytes, actual: actualSize)
      }
      let digest = try await Task.detached(priority: .utility) {
        try Self.sha256(of: stagedURL)
      }.value
      guard digest == model.normalizedSHA256 else { throw ModelDownloadError.checksumMismatch }

      let destinationDirectory = installedModelsDirectory
        .appendingPathComponent(model.id, isDirectory: true)
        .appendingPathComponent(model.version, isDirectory: true)
      try FileManager.default.createDirectory(at: destinationDirectory, withIntermediateDirectories: true)
      var directory = installedModelsDirectory
      var valuesToSet = URLResourceValues()
      valuesToSet.isExcludedFromBackup = true
      try? directory.setResourceValues(valuesToSet)
      let destination = destinationDirectory.appendingPathComponent(model.filename)
      if FileManager.default.fileExists(atPath: destination.path) {
        try FileManager.default.removeItem(at: destination)
      }
      try FileManager.default.moveItem(at: stagedURL, to: destination)
      try JSONEncoder().encode(model).write(to: installedMetadataURL, options: .atomic)
      try? FileManager.default.removeItem(at: pendingModelURL)
      removePartArtifacts()
      installedModelURL = destination
      installedModelName = model.displayName
      bytesDownloaded = model.sizeBytes
      bytesPerSecond = 0
      estimatedRemainingSeconds = nil
      phase = .installed
      errorMessage = nil
    } catch {
      try? FileManager.default.removeItem(at: stagedURL)
      fail(error)
    }
  }

  nonisolated private static func sha256(of url: URL) throws -> String {
    let handle = try FileHandle(forReadingFrom: url)
    defer { try? handle.close() }
    var hasher = SHA256()
    while true {
      let data = try handle.read(upToCount: 4 * 1_024 * 1_024) ?? Data()
      if data.isEmpty { break }
      hasher.update(data: data)
    }
    return hasher.finalize().map { String(format: "%02x", $0) }.joined()
  }

  private static let offlineFallbackModel = RemoteModelDescriptor(
    id: "gemma-4-e4b-it",
    displayName: "Gemma 4 E4B",
    version: "1",
    filename: "gemma-4-e4b-it.litertlm",
    url: URL(string: "https://models.echosense-ai.app/v1/gemma-4-e4b-it.litertlm")!,
    licenseURL: URL(string: "https://models.echosense-ai.app/v1/LICENSE.txt")!,
    sizeBytes: 3_654_467_584,
    sha256: "f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc",
    supportedPlatforms: ["ios"],
    minimumIOSVersion: "17.0",
    minimumAndroidSDK: nil,
    minimumMemoryGB: 6,
    capabilities: ["text", "image"],
    recommended: true
  )

  private func restoreInstalledModel() {
    guard let model = try? JSONDecoder().decode(RemoteModelDescriptor.self, from: Data(contentsOf: installedMetadataURL)) else {
      phase = .notInstalled
      return
    }
    let url = installedModelsDirectory
      .appendingPathComponent(model.id, isDirectory: true)
      .appendingPathComponent(model.version, isDirectory: true)
      .appendingPathComponent(model.filename)
    guard FileManager.default.fileExists(atPath: url.path) else {
      phase = .notInstalled
      return
    }
    availableModel = model
    installedModelURL = url
    installedModelName = model.displayName
    bytesDownloaded = model.sizeBytes
    totalBytes = model.sizeBytes
    phase = .installed
  }

  private func reconnectToBackgroundTask() {
    session.getAllTasks { [weak self] tasks in
      Task { @MainActor in
        guard let self else { return }
        for case let task as URLSessionDownloadTask in tasks {
          guard let index = self.partIndex(for: task) else {
            // Cancel a single-stream task left by an older app build; it cannot be merged with
            // the new independently addressed part files.
            task.cancel()
            continue
          }
          self.activeTasks[index] = task
          self.taskBytes[index] = max(0, task.countOfBytesReceived)
        }
        guard !self.activeTasks.isEmpty else { return }
        if let model = try? self.readPendingModel() {
          for (index, bytes) in self.completedPartBytes(for: model) {
            self.taskBytes[index] = bytes
          }
        }
        self.updateAggregateProgress()
        self.resetSpeedSample()
        self.phase = .downloading
      }
    }
  }

  private struct DownloadPart: Sendable {
    let index: Int
    let start: Int64
    let end: Int64
    var length: Int64 { end - start + 1 }
  }

  private func downloadParts(for model: RemoteModelDescriptor) -> [DownloadPart] {
    let baseLength = model.sizeBytes / Int64(parallelDownloadPartCount)
    let remainder = model.sizeBytes % Int64(parallelDownloadPartCount)
    var start: Int64 = 0
    return (0..<parallelDownloadPartCount).map { index in
      let length = baseLength + (Int64(index) < remainder ? 1 : 0)
      defer { start += length }
      return DownloadPart(index: index, start: start, end: start + length - 1)
    }
  }

  private func completedPartBytes(for model: RemoteModelDescriptor) -> [Int: Int64] {
    Dictionary(uniqueKeysWithValues: downloadParts(for: model).compactMap { part in
      let url = partFileURL(part.index)
      guard let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
            Int64(size) == part.length else { return nil }
      return (part.index, part.length)
    })
  }

  private func updateAggregateProgress() {
    bytesDownloaded = min(totalBytes, taskBytes.values.reduce(0, +))
    updateSpeedEstimate()
  }

  private func resetSpeedSample() {
    lastSpeedSampleDate = Date()
    lastSpeedSampleBytes = bytesDownloaded
    bytesPerSecond = 0
    estimatedRemainingSeconds = nil
  }

  private func updateSpeedEstimate() {
    let now = Date()
    let interval = now.timeIntervalSince(lastSpeedSampleDate)
    guard interval >= 0.5 else { return }
    let delta = max(0, bytesDownloaded - lastSpeedSampleBytes)
    let sample = Int64(Double(delta) / interval)
    bytesPerSecond = bytesPerSecond == 0 ? sample : Int64(Double(bytesPerSecond) * 0.7 + Double(sample) * 0.3)
    if bytesPerSecond > 0 {
      estimatedRemainingSeconds = Double(max(0, totalBytes - bytesDownloaded)) / Double(bytesPerSecond)
    }
    lastSpeedSampleDate = now
    lastSpeedSampleBytes = bytesDownloaded
  }

  private func finishParallelDownload() async {
    guard phase == .downloading else { return }
    phase = .verifying
    do {
      let model = try readPendingModel()
      let partURLs = downloadParts(for: model).map { partFileURL($0.index) }
      let stagedURL = stagingModelURL
      try await Task.detached(priority: .utility) {
        try Self.assemble(partURLs: partURLs, destination: stagedURL)
      }.value
      await installDownloadedFile(stagedURL)
    } catch {
      fail(error)
    }
  }

  private func handleFinishedPart(index: Int, url: URL) async {
    do {
      let model = try readPendingModel()
      guard let part = downloadParts(for: model).first(where: { $0.index == index }) else {
        throw ModelDownloadError.missingDownloadMetadata
      }
      let actualSize = Int64(try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0)
      guard actualSize == part.length else {
        try? FileManager.default.removeItem(at: url)
        throw ModelDownloadError.sizeMismatch(expected: part.length, actual: actualSize)
      }
      activeTasks.removeValue(forKey: index)
      taskBytes[index] = part.length
      try? FileManager.default.removeItem(at: partResumeDataURL(index))
      updateAggregateProgress()
      if completedPartBytes(for: model).count == parallelDownloadPartCount {
        await finishParallelDownload()
      }
    } catch {
      handleTaskFailure(error)
    }
  }

  private func handleTaskFailure(_ error: Error) {
    pauseOperationID = nil
    pendingPauseCallbacks = 0
    let tasks = activeTasks
    activeTasks.removeAll()
    tasks.values.forEach { $0.cancel() }
    bytesPerSecond = 0
    estimatedRemainingSeconds = nil
    fail(error)
  }

  nonisolated private static func assemble(partURLs: [URL], destination: URL) throws {
    let fileManager = FileManager.default
    if fileManager.fileExists(atPath: destination.path) { try fileManager.removeItem(at: destination) }
    guard fileManager.createFile(atPath: destination.path, contents: nil) else {
      throw ModelDownloadError.moveFailed
    }
    let output = try FileHandle(forWritingTo: destination)
    defer { try? output.close() }
    do {
      for partURL in partURLs {
        let input = try FileHandle(forReadingFrom: partURL)
        while true {
          let data = try input.read(upToCount: 4 * 1_024 * 1_024) ?? Data()
          if data.isEmpty { break }
          try output.write(contentsOf: data)
        }
        try input.close()
        try fileManager.removeItem(at: partURL)
      }
      try output.synchronize()
    } catch {
      try? fileManager.removeItem(at: destination)
      throw error
    }
  }

  private func partIndex(for task: URLSessionTask) -> Int? {
    guard let description = task.taskDescription,
          let index = Int(description),
          (0..<parallelDownloadPartCount).contains(index) else { return nil }
    return index
  }

  private func removePartArtifacts() {
    for index in 0..<parallelDownloadPartCount {
      try? FileManager.default.removeItem(at: partFileURL(index))
      try? FileManager.default.removeItem(at: partResumeDataURL(index))
    }
  }

  private func removeDownloadArtifacts() {
    removePartArtifacts()
    try? FileManager.default.removeItem(at: legacyResumeDataURL)
    try? FileManager.default.removeItem(at: stagingModelURL)
  }

  private func availableStorageCapacity() throws -> Int64 {
    let values = try modelSupportDirectory.deletingLastPathComponent()
      .resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
    return values.volumeAvailableCapacityForImportantUsage ?? 0
  }

  private func writePendingModel(_ model: RemoteModelDescriptor) throws {
    try FileManager.default.createDirectory(at: modelSupportDirectory, withIntermediateDirectories: true)
    try JSONEncoder().encode(model).write(to: pendingModelURL, options: .atomic)
  }

  private func readPendingModel() throws -> RemoteModelDescriptor {
    guard let model = try? JSONDecoder().decode(RemoteModelDescriptor.self, from: Data(contentsOf: pendingModelURL)) else {
      throw ModelDownloadError.missingDownloadMetadata
    }
    return model
  }

  private func fail(_ error: Error) {
    phase = .failed
    errorMessage = error.localizedDescription
  }

  private var modelSupportDirectory: URL {
    FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("EchoSenseModels", isDirectory: true)
  }
  private var installedModelsDirectory: URL { modelSupportDirectory.appendingPathComponent("Models", isDirectory: true) }
  private var installedMetadataURL: URL { modelSupportDirectory.appendingPathComponent("installed-model.json") }
  private var pendingModelURL: URL { modelSupportDirectory.appendingPathComponent("pending-model.json") }
  private var cachedCatalogURL: URL { modelSupportDirectory.appendingPathComponent("models.json") }
  private var legacyResumeDataURL: URL { modelSupportDirectory.appendingPathComponent("model-download.resume") }
  private func partFileURL(_ index: Int) -> URL {
    modelSupportDirectory.appendingPathComponent("model-download.part-\(index)")
  }
  private func partResumeDataURL(_ index: Int) -> URL {
    modelSupportDirectory.appendingPathComponent("model-download.part-\(index).resume")
  }
  private var stagingModelURL: URL { modelSupportDirectory.appendingPathComponent("model-download.staging") }
}

extension ModelDownloadManager: URLSessionDownloadDelegate, URLSessionTaskDelegate {
  nonisolated func urlSession(
    _ session: URLSession,
    downloadTask: URLSessionDownloadTask,
    didWriteData bytesWritten: Int64,
    totalBytesWritten: Int64,
    totalBytesExpectedToWrite: Int64
  ) {
    Task { @MainActor [weak self] in
      guard let self,
            self.phase == .downloading,
            let index = self.partIndex(for: downloadTask) else { return }
      self.taskBytes[index] = max(0, totalBytesWritten)
      self.updateAggregateProgress()
    }
  }

  nonisolated func urlSession(
    _ session: URLSession,
    downloadTask: URLSessionDownloadTask,
    didFinishDownloadingTo location: URL
  ) {
    do {
      guard let index = Int(downloadTask.taskDescription ?? ""),
            (0..<parallelDownloadPartCount).contains(index) else {
        throw ModelDownloadError.missingDownloadMetadata
      }
      guard let response = downloadTask.response as? HTTPURLResponse,
            response.statusCode == 206 else {
        throw ModelDownloadError.rangeDownloadUnsupported
      }
      let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("EchoSenseModels", isDirectory: true)
      try FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
      let partURL = support.appendingPathComponent("model-download.part-\(index)")
      if FileManager.default.fileExists(atPath: partURL.path) { try FileManager.default.removeItem(at: partURL) }
      try FileManager.default.moveItem(at: location, to: partURL)
      Task { @MainActor [weak self] in await self?.handleFinishedPart(index: index, url: partURL) }
    } catch {
      Task { @MainActor [weak self] in self?.handleTaskFailure(error) }
    }
  }

  nonisolated func urlSession(
    _ session: URLSession,
    task: URLSessionTask,
    didCompleteWithError error: Error?
  ) {
    guard let error else { return }
    let nsError = error as NSError
    if nsError.code == NSURLErrorCancelled { return }
    if let index = Int(task.taskDescription ?? ""),
       let data = nsError.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
      let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("EchoSenseModels/model-download.part-\(index).resume")
      try? data.write(to: url, options: .atomic)
    }
    Task { @MainActor [weak self] in self?.handleTaskFailure(error) }
  }

  nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
    Task { @MainActor in EchoSenseAppDelegate.finishBackgroundEvents(for: session.configuration.identifier) }
  }
}

final class EchoSenseAppDelegate: NSObject, UIApplicationDelegate {
  private static var backgroundCompletionHandlers: [String: () -> Void] = [:]

  func application(
    _ application: UIApplication,
    handleEventsForBackgroundURLSession identifier: String,
    completionHandler: @escaping () -> Void
  ) {
    Self.backgroundCompletionHandlers[identifier] = completionHandler
  }

  @MainActor static func finishBackgroundEvents(for identifier: String?) {
    guard let identifier, let handler = backgroundCompletionHandlers.removeValue(forKey: identifier) else { return }
    handler()
  }
}

private extension Int64 {
  var formattedBytes: String { ByteCountFormatter.string(fromByteCount: self, countStyle: .file) }
}

private extension TimeInterval {
  var formattedDuration: String {
    let totalMinutes = max(1, Int(ceil(self / 60)))
    if totalMinutes < 60 { return "\(totalMinutes)m" }
    let hours = totalMinutes / 60
    let minutes = totalMinutes % 60
    return minutes == 0 ? "\(hours)h" : "\(hours)h \(minutes)m"
  }
}
