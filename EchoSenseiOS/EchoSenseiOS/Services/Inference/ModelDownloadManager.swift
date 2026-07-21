import CryptoKit
import Foundation
import OSLog
import UIKit

private let backgroundSessionIdentifier = "com.terranet.echosense.ios.model-download"
private let parallelDownloadPartCount = 4
private let selectedModelDefaultsKey = "echosense.modelDownload.selectedModelID"

private func partBackgroundSessionIdentifier(_ index: Int) -> String {
  "\(backgroundSessionIdentifier).part-\(index)"
}

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
  private nonisolated static let logger = Logger(
    subsystem: "com.terranet.echosense.ios",
    category: "ModelDownload"
  )

  @Published private(set) var phase: ModelDownloadPhase = .checking
  @Published private(set) var availableModels: [RemoteModelDescriptor]
  @Published private(set) var selectedModelID: String
  @Published private(set) var installedModelURL: URL?
  @Published private(set) var installedModelName: String?
  @Published private(set) var bytesDownloaded: Int64 = 0
  @Published private(set) var totalBytes: Int64 = 0
  @Published private(set) var bytesPerSecond: Int64 = 0
  @Published private(set) var estimatedRemainingSeconds: TimeInterval?
  @Published private(set) var errorMessage: String?
  @Published private(set) var downloadDiagnosticReport = "No model download diagnostics recorded yet."
  @Published var allowsCellularDownload: Bool {
    didSet { UserDefaults.standard.set(allowsCellularDownload, forKey: "echosense.modelDownload.cellular") }
  }

  // A separate background session gives each range its own connection pool. A single session
  // multiplexes all four Hugging Face CDN ranges over one HTTP/2 connection on iOS, limiting the
  // aggregate rate even though four URLSession tasks are active.
  private lazy var partSessions: [URLSession] = (0..<parallelDownloadPartCount).map { index in
    makeBackgroundSession(identifier: partBackgroundSessionIdentifier(index))
  }
  // Recreate the identifier used by earlier builds so an in-flight download can be discovered and
  // paused or cancelled instead of being orphaned after an app update.
  private lazy var legacySession: URLSession = makeBackgroundSession(
    identifier: backgroundSessionIdentifier
  )

  private func makeBackgroundSession(identifier: String) -> URLSession {
    let configuration = URLSessionConfiguration.background(withIdentifier: identifier)
    configuration.sessionSendsLaunchEvents = true
    configuration.isDiscretionary = false
    configuration.waitsForConnectivity = true
    configuration.httpMaximumConnectionsPerHost = 1
    return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
  }
  private var activeTasks: [Int: URLSessionDownloadTask] = [:]
  private var taskBytes: [Int: Int64] = [:]
  private var lastSpeedSampleDate = Date()
  private var lastSpeedSampleBytes: Int64 = 0
  private var pauseOperationID: UUID?
  private var pendingPauseCallbacks = 0
  private var installedModel: RemoteModelDescriptor?
  private var diagnosticEpoch = Date()
  private var diagnosticLines: [String] = []
  private var partDiagnostics: [Int: PartDownloadDiagnostic] = [:]

  override init() {
    availableModels = Self.officialModels
    selectedModelID = UserDefaults.standard.string(forKey: selectedModelDefaultsKey)
      ?? Self.defaultModelID
    allowsCellularDownload = UserDefaults.standard.bool(forKey: "echosense.modelDownload.cellular")
    super.init()
    restoreInstalledModel()
    reconnectToBackgroundTask()
  }

  var progress: Double {
    guard totalBytes > 0 else { return 0 }
    return min(1, Double(bytesDownloaded) / Double(totalBytes))
  }

  var availableModel: RemoteModelDescriptor? {
    availableModels.first(where: { $0.id == selectedModelID }) ?? availableModels.first
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
    errorMessage = nil
    availableModels = Self.officialModels
    if !availableModels.contains(where: { $0.id == selectedModelID }) {
      selectedModelID = Self.defaultModelID
    }
    totalBytes = availableModel?.sizeBytes ?? 0
    updateIdlePhase()
  }

  func selectModel(id: String) {
    guard phase != .downloading, phase != .paused, phase != .verifying,
          availableModels.contains(where: { $0.id == id }) else { return }
    selectedModelID = id
    UserDefaults.standard.set(id, forKey: selectedModelDefaultsKey)
    totalBytes = availableModel?.sizeBytes ?? 0
    bytesDownloaded = installedModel?.id == id ? installedModel?.sizeBytes ?? 0 : 0
    errorMessage = nil
    updateIdlePhase()
  }

  func startDownload() async {
    guard let model = availableModel else {
      await refreshCatalog()
      guard availableModel != nil else { return }
      await startDownload()
      return
    }

    do {
      beginDiagnostics(for: model)
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
        let resumeData = try? Data(contentsOf: partResumeDataURL(part.index))
        if let resumeData {
          task = partSessions[part.index].downloadTask(withResumeData: resumeData)
        } else {
          var request = URLRequest(url: model.url)
          request.setValue("bytes=\(part.start)-\(part.end)", forHTTPHeaderField: "Range")
          request.allowsCellularAccess = allowsCellularDownload
          request.allowsExpensiveNetworkAccess = allowsCellularDownload
          request.allowsConstrainedNetworkAccess = true
          task = partSessions[part.index].downloadTask(with: request)
        }
        task.taskDescription = String(part.index)
        activeTasks[part.index] = task
        partDiagnostics[part.index] = PartDownloadDiagnostic(
          requestedStart: part.start,
          requestedEnd: part.end,
          latestBytes: taskBytes[part.index] ?? 0
        )
        recordDiagnostic(
          "part=\(part.index) queued range=\(part.start)-\(part.end) " +
          "resume=\(resumeData != nil) session=\(partBackgroundSessionIdentifier(part.index))"
        )
      }
      phase = .downloading
      if activeTasks.isEmpty {
        await finishParallelDownload()
      } else {
        activeTasks.values.forEach { $0.resume() }
        recordDiagnostic("resumed \(activeTasks.count) parallel URLSession download tasks")
      }
    } catch {
      fail(error)
    }
  }

  func pauseDownload() {
    guard !activeTasks.isEmpty else { return }
    let tasks = activeTasks
    recordDiagnostic("pause requested for \(tasks.count) active tasks")
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
    recordDiagnostic("cancel requested")
    pauseOperationID = nil
    pendingPauseCallbacks = 0
    activeTasks.values.forEach { $0.cancel() }
    activeTasks.removeAll()
    removeDownloadArtifacts()
    try? FileManager.default.removeItem(at: pendingModelURL)
    bytesDownloaded = 0
    bytesPerSecond = 0
    estimatedRemainingSeconds = nil
    updateIdlePhase()
  }

  func deleteInstalledModel() {
    guard let installedModelURL else { return }
    try? FileManager.default.removeItem(at: installedModelURL.deletingLastPathComponent())
    try? FileManager.default.removeItem(at: installedMetadataURL)
    self.installedModelURL = nil
    installedModel = nil
    installedModelName = nil
    bytesDownloaded = 0
    updateIdlePhase()
  }

  private func installDownloadedFile(_ stagedURL: URL) async {
    phase = .verifying
    recordDiagnostic("all parts assembled; size and SHA-256 verification started")
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
      let previousModelURL = installedModelURL
      try JSONEncoder().encode(model).write(to: installedMetadataURL, options: .atomic)
      try? FileManager.default.removeItem(at: pendingModelURL)
      removePartArtifacts()
      installedModelURL = destination
      installedModel = model
      installedModelName = model.displayName
      bytesDownloaded = model.sizeBytes
      bytesPerSecond = 0
      estimatedRemainingSeconds = nil
      phase = .installed
      errorMessage = nil
      recordDiagnostic("model verified and installed successfully")
      if let previousModelURL,
         previousModelURL.deletingLastPathComponent() != destinationDirectory {
        try? FileManager.default.removeItem(at: previousModelURL.deletingLastPathComponent())
      }
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

  private static let defaultModelID = "gemma-4-e4b-it"

  private static let officialModels = [
    RemoteModelDescriptor(
      id: "gemma-4-e4b-it",
      displayName: "Gemma 4 E4B",
      version: "9695417f248178c63a9f318c6e0c56cb917cb837",
      filename: "gemma-4-E4B-it.litertlm",
      url: URL(string: "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/9695417f248178c63a9f318c6e0c56cb917cb837/gemma-4-E4B-it.litertlm?download=true")!,
      licenseURL: URL(string: "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm")!,
      sizeBytes: 3_654_467_584,
      sha256: "f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc",
      supportedPlatforms: ["ios"],
      minimumIOSVersion: "17.0",
      minimumAndroidSDK: nil,
      minimumMemoryGB: 8,
      capabilities: ["text", "image"],
      recommended: true
    ),
    RemoteModelDescriptor(
      id: "gemma-4-e2b-it",
      displayName: "Gemma 4 E2B",
      version: "7fa1d78473894f7e736a21d920c3aa80f950c0db",
      filename: "gemma-4-E2B-it.litertlm",
      url: URL(string: "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db/gemma-4-E2B-it.litertlm?download=true")!,
      licenseURL: URL(string: "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm")!,
      sizeBytes: 2_583_085_056,
      sha256: "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42",
      supportedPlatforms: ["ios"],
      minimumIOSVersion: "17.0",
      minimumAndroidSDK: nil,
      minimumMemoryGB: 6,
      capabilities: ["text", "image"],
      recommended: false
    ),
  ]

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
    installedModel = model
    installedModelURL = url
    installedModelName = model.displayName
    bytesDownloaded = model.sizeBytes
    totalBytes = model.sizeBytes
    phase = .installed
  }

  private func updateIdlePhase() {
    guard phase != .downloading, phase != .paused, phase != .verifying else { return }
    phase = installedModel?.id == selectedModelID ? .installed : .notInstalled
  }

  private func reconnectToBackgroundTask() {
    let sessions = [legacySession] + partSessions
    for backgroundSession in sessions {
      backgroundSession.getAllTasks { [weak self] tasks in
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
          guard !tasks.isEmpty else { return }
          self.recordDiagnostic(
            "reconnected session=\(backgroundSession.configuration.identifier ?? "unknown") " +
            "tasks=\(tasks.count) activeTotal=\(self.activeTasks.count)"
          )
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

  private func beginDiagnostics(for model: RemoteModelDescriptor) {
    diagnosticEpoch = Date()
    diagnosticLines.removeAll(keepingCapacity: true)
    partDiagnostics.removeAll(keepingCapacity: true)
    downloadDiagnosticReport = ""
    let state: String
    switch UIApplication.shared.applicationState {
    case .active: state = "active"
    case .inactive: state = "inactive"
    case .background: state = "background"
    @unknown default: state = "unknown"
    }
    recordDiagnostic(
      "begin model=\(model.id) bytes=\(model.sizeBytes) parts=\(parallelDownloadPartCount) " +
      "appState=\(state) lowPower=\(ProcessInfo.processInfo.isLowPowerModeEnabled) " +
      "thermal=\(ProcessInfo.processInfo.thermalState.rawValue) cellularAllowed=\(allowsCellularDownload) " +
      "sessions=\(parallelDownloadPartCount) background discretionary=false connectionsPerSession=1"
    )
    recordDiagnostic("originHost=\(model.url.host ?? "unknown") path=\(model.url.path)")
  }

  private func recordProgressDiagnostic(
    index: Int,
    totalBytesWritten: Int64,
    totalBytesExpectedToWrite: Int64,
    task: URLSessionTask
  ) {
    let now = Date()
    var diagnostic = partDiagnostics[index] ?? PartDownloadDiagnostic()
    diagnostic.latestBytes = totalBytesWritten
    diagnostic.expectedBytes = totalBytesExpectedToWrite
    if diagnostic.firstByteDate == nil {
      diagnostic.firstByteDate = now
      diagnostic.lastLogDate = now
      diagnostic.lastLogBytes = totalBytesWritten
      partDiagnostics[index] = diagnostic
      recordDiagnostic(
        "part=\(index) first progress after \(String(format: "%.2f", now.timeIntervalSince(diagnosticEpoch)))s " +
        "bytes=\(totalBytesWritten) expected=\(totalBytesExpectedToWrite) " +
        "host=\(task.currentRequest?.url?.host ?? "unknown")"
      )
      return
    }

    let shouldLog = diagnostic.lastLogDate == nil || now.timeIntervalSince(diagnostic.lastLogDate!) >= 5
    if shouldLog {
      let interval = max(0.001, now.timeIntervalSince(diagnostic.lastLogDate ?? diagnostic.firstByteDate ?? now))
      let delta = max(0, totalBytesWritten - diagnostic.lastLogBytes)
      let partRate = Int64(Double(delta) / interval)
      diagnostic.lastLogDate = now
      diagnostic.lastLogBytes = totalBytesWritten
      partDiagnostics[index] = diagnostic
      let parts = partDiagnostics.keys.sorted().map { partIndex in
        "p\(partIndex)=\((partDiagnostics[partIndex]?.latestBytes ?? 0).formattedBytes)"
      }.joined(separator: ",")
      recordDiagnostic(
        "progress part=\(index) partRate=\(partRate.formattedBytes)/s " +
        "aggregateRate=\(bytesPerSecond.formattedBytes)/s total=\(bytesDownloaded.formattedBytes) " +
        "active=\(activeTasks.count) [\(parts)]"
      )
    } else {
      partDiagnostics[index] = diagnostic
    }
  }

  private func recordDiagnostic(_ message: String) {
    let elapsed = max(0, Date().timeIntervalSince(diagnosticEpoch))
    let line = String(format: "+%07.2fs %@", elapsed, message)
    Self.logger.info("\(line, privacy: .public)")
    diagnosticLines.append(line)
    if diagnosticLines.count > 250 { diagnosticLines.removeFirst(diagnosticLines.count - 250) }
    downloadDiagnosticReport = diagnosticLines.joined(separator: "\n")
  }

  private func recordTaskMetrics(part: Int?, lines: [String]) {
    for line in lines {
      recordDiagnostic("metrics part=\(part.map(String.init) ?? "unknown") \(line)")
    }
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
    let applicationSupportDirectory = modelSupportDirectory.deletingLastPathComponent()
    try FileManager.default.createDirectory(
      at: applicationSupportDirectory,
      withIntermediateDirectories: true
    )
    let values = try applicationSupportDirectory
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
    recordDiagnostic("download failed: \(error.localizedDescription)")
  }

  private var modelSupportDirectory: URL {
    FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("EchoSenseModels", isDirectory: true)
  }
  private var installedModelsDirectory: URL { modelSupportDirectory.appendingPathComponent("Models", isDirectory: true) }
  private var installedMetadataURL: URL { modelSupportDirectory.appendingPathComponent("installed-model.json") }
  private var pendingModelURL: URL { modelSupportDirectory.appendingPathComponent("pending-model.json") }
  private var legacyResumeDataURL: URL { modelSupportDirectory.appendingPathComponent("model-download.resume") }
  private func partFileURL(_ index: Int) -> URL {
    modelSupportDirectory.appendingPathComponent("model-download.part-\(index)")
  }
  private func partResumeDataURL(_ index: Int) -> URL {
    modelSupportDirectory.appendingPathComponent("model-download.part-\(index).resume")
  }
  private var stagingModelURL: URL { modelSupportDirectory.appendingPathComponent("model-download.staging") }
}

private struct PartDownloadDiagnostic {
  var requestedStart: Int64 = 0
  var requestedEnd: Int64 = 0
  var latestBytes: Int64 = 0
  var expectedBytes: Int64 = 0
  var firstByteDate: Date?
  var lastLogDate: Date?
  var lastLogBytes: Int64 = 0
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
      self.recordProgressDiagnostic(
        index: index,
        totalBytesWritten: totalBytesWritten,
        totalBytesExpectedToWrite: totalBytesExpectedToWrite,
        task: downloadTask
      )
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
      let finalHost = response.url?.host ?? "unknown"
      let contentRange = response.value(forHTTPHeaderField: "Content-Range") ?? "missing"
      let contentLength = response.value(forHTTPHeaderField: "Content-Length") ?? "missing"
      Task { @MainActor [weak self] in
        self?.recordDiagnostic(
          "part=\(index) response complete status=\(response.statusCode) host=\(finalHost) " +
          "contentRange=\(contentRange) contentLength=\(contentLength)"
        )
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

  nonisolated func urlSession(
    _ session: URLSession,
    task: URLSessionTask,
    didFinishCollecting metrics: URLSessionTaskMetrics
  ) {
    let part = Int(task.taskDescription ?? "")
    var lines = [
      "summary session=\(session.configuration.identifier ?? "unknown") " +
      "redirects=\(metrics.redirectCount) duration=\(String(format: "%.2f", metrics.taskInterval.duration))s " +
      "transactions=\(metrics.transactionMetrics.count)"
    ]
    for (index, transaction) in metrics.transactionMetrics.enumerated() {
      let response = transaction.response as? HTTPURLResponse
      let requestRange = transaction.request.value(forHTTPHeaderField: "Range") ?? "missing"
      let host = transaction.request.url?.host ?? "unknown"
      let duration: String
      if let start = transaction.fetchStartDate,
         let end = transaction.responseEndDate {
        duration = String(format: "%.2f", end.timeIntervalSince(start))
      } else {
        duration = "unknown"
      }
      let firstByte: String
      if let start = transaction.requestStartDate,
         let responseStart = transaction.responseStartDate {
        firstByte = String(format: "%.2f", responseStart.timeIntervalSince(start))
      } else {
        firstByte = "unknown"
      }
      lines.append(
        "tx=\(index) host=\(host) status=\(response?.statusCode ?? -1) protocol=\(transaction.networkProtocolName ?? "unknown") " +
        "range=\(requestRange) duration=\(duration)s ttfb=\(firstByte)s " +
        "received=\(transaction.countOfResponseBodyBytesReceived) reused=\(transaction.isReusedConnection) " +
        "local=\(transaction.localAddress ?? "unknown"): \(transaction.localPort ?? -1) " +
        "remote=\(transaction.remoteAddress ?? "unknown"): \(transaction.remotePort ?? -1) " +
        "cellular=\(transaction.isCellular) expensive=\(transaction.isExpensive) " +
        "constrained=\(transaction.isConstrained) proxy=\(transaction.isProxyConnection)"
      )
    }
    Task { @MainActor [weak self] in self?.recordTaskMetrics(part: part, lines: lines) }
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
