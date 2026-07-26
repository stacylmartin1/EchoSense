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

import AVFoundation
import Foundation
import UIKit

protocol CameraFrameSourceDelegate: AnyObject {
  func cameraFrameSource(_ source: CameraFrameSource, authorizationDidChange status: AVAuthorizationStatus)
  func cameraFrameSourceDidStart(_ source: CameraFrameSource)
  func cameraFrameSource(_ source: CameraFrameSource, didOutput sampleBuffer: CMSampleBuffer)
  func cameraFrameSource(_ source: CameraFrameSource, depthAvailabilityDidChange isAvailable: Bool)
  func cameraFrameSource(_ source: CameraFrameSource, didUpdateDepth observations: [DepthObservation])
}

extension CameraFrameSourceDelegate {
  func cameraFrameSource(_ source: CameraFrameSource, depthAvailabilityDidChange isAvailable: Bool) {}
  func cameraFrameSource(_ source: CameraFrameSource, didUpdateDepth observations: [DepthObservation]) {}
}

final class CameraFrameSource: NSObject, ObservableObject {
  let session = AVCaptureSession()
  weak var delegate: CameraFrameSourceDelegate?

  @Published private(set) var isRunning = false
  @Published private(set) var authorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)

  private let sessionQueue = DispatchQueue(label: "com.echosense.camera.session")
  private let videoOutput = AVCaptureVideoDataOutput()
  private let photoOutput = AVCapturePhotoOutput()
  private let depthOutput = AVCaptureDepthDataOutput()
  private var outputSynchronizer: AVCaptureDataOutputSynchronizer?
  private let frameQueue = DispatchQueue(label: "com.echosense.camera.frames", qos: .userInitiated)
  private let deliveryLock = NSLock()
  private var shouldDeliverFrames = false
  private var photoCaptureProcessors: [Int64: PhotoCaptureProcessor] = [:]

  func requestPermissionAndStart() {
    switch AVCaptureDevice.authorizationStatus(for: .video) {
    case .authorized:
      authorizationStatus = .authorized
      delegate?.cameraFrameSource(self, authorizationDidChange: .authorized)
      start()
    case .notDetermined:
      AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
        DispatchQueue.main.async {
          guard let self else { return }
          let status = AVCaptureDevice.authorizationStatus(for: .video)
          self.authorizationStatus = status
          self.delegate?.cameraFrameSource(self, authorizationDidChange: status)
        }
        if granted {
          self?.start()
        }
      }
    default:
      DispatchQueue.main.async {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        self.authorizationStatus = status
        self.delegate?.cameraFrameSource(self, authorizationDidChange: status)
      }
    }
  }

  func start() {
    sessionQueue.async {
      self.configureIfNeeded()
      guard !self.session.isRunning else { return }
      self.setShouldDeliverFrames(true)
      self.session.startRunning()
      DispatchQueue.main.async {
        self.isRunning = true
        self.delegate?.cameraFrameSourceDidStart(self)
      }
    }
  }

  func stop(completion: (() -> Void)? = nil) {
    sessionQueue.async {
      self.setShouldDeliverFrames(false)
      guard self.session.isRunning else {
        DispatchQueue.main.async { completion?() }
        return
      }
      self.session.stopRunning()
      DispatchQueue.main.async {
        self.isRunning = false
        completion?()
      }
    }
  }

  private func configureIfNeeded() {
    guard session.inputs.isEmpty else { return }

    session.beginConfiguration()
    session.sessionPreset = .high

    let lidarCamera = AVCaptureDevice.default(.builtInLiDARDepthCamera, for: .video, position: .back)
    guard let camera = lidarCamera ?? AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
          let input = try? AVCaptureDeviceInput(device: camera),
          session.canAddInput(input) else {
      session.commitConfiguration()
      return
    }
    session.addInput(input)

    let depthConfigured = lidarCamera != nil && configureDepthFormat(for: camera)

    videoOutput.alwaysDiscardsLateVideoFrames = true
    videoOutput.videoSettings = [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
    ]
    if session.canAddOutput(videoOutput) {
      session.addOutput(videoOutput)
    }
    if session.canAddOutput(photoOutput) {
      session.addOutput(photoOutput)
    }

    if depthConfigured, session.canAddOutput(depthOutput) {
      depthOutput.alwaysDiscardsLateDepthData = true
      depthOutput.isFilteringEnabled = true
      session.addOutput(depthOutput)
      outputSynchronizer = AVCaptureDataOutputSynchronizer(dataOutputs: [videoOutput, depthOutput])
      outputSynchronizer?.setDelegate(self, queue: frameQueue)
    } else {
      videoOutput.setSampleBufferDelegate(self, queue: frameQueue)
    }

    if let connection = videoOutput.connection(with: .video), connection.isVideoRotationAngleSupported(90) {
      connection.videoRotationAngle = 90
    }
    if let connection = photoOutput.connection(with: .video), connection.isVideoRotationAngleSupported(90) {
      connection.videoRotationAngle = 90
    }
    if let connection = depthOutput.connection(with: .depthData), connection.isVideoRotationAngleSupported(90) {
      connection.videoRotationAngle = 90
    }
    session.commitConfiguration()

    let depthAvailable = outputSynchronizer != nil
    DispatchQueue.main.async {
      self.delegate?.cameraFrameSource(self, depthAvailabilityDidChange: depthAvailable)
    }
  }

  private func configureDepthFormat(for camera: AVCaptureDevice) -> Bool {
    let candidates = camera.formats.filter { format in
      let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
      return !format.supportedDepthDataFormats.isEmpty && dimensions.width <= 1920
    }
    guard let videoFormat = candidates.max(by: { lhs, rhs in
      let left = CMVideoFormatDescriptionGetDimensions(lhs.formatDescription)
      let right = CMVideoFormatDescriptionGetDimensions(rhs.formatDescription)
      return Int(left.width) * Int(left.height) < Int(right.width) * Int(right.height)
    }) else { return false }

    guard let depthFormat = videoFormat.supportedDepthDataFormats.max(by: { lhs, rhs in
      let left = CMVideoFormatDescriptionGetDimensions(lhs.formatDescription)
      let right = CMVideoFormatDescriptionGetDimensions(rhs.formatDescription)
      return Int(left.width) * Int(left.height) < Int(right.width) * Int(right.height)
    }) else { return false }

    do {
      if session.canSetSessionPreset(.inputPriority) {
        session.sessionPreset = .inputPriority
      }
      try camera.lockForConfiguration()
      camera.activeFormat = videoFormat
      camera.activeDepthDataFormat = depthFormat
      camera.activeVideoMinFrameDuration = CMTime(value: 1, timescale: 30)
      camera.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: 30)
      camera.unlockForConfiguration()
      return true
    } catch {
      return false
    }
  }

  func captureCurrentFrameImage(from sampleBuffer: CMSampleBuffer) -> UIImage? {
    guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return nil }
    let ciImage = CIImage(cvPixelBuffer: imageBuffer)
    let context = CIContext()
    guard let cgImage = context.createCGImage(ciImage, from: ciImage.extent) else { return nil }
    
    let width = CVPixelBufferGetWidth(imageBuffer)
    let height = CVPixelBufferGetHeight(imageBuffer)
    let orientation: UIImage.Orientation = width > height ? .left : .up
    return UIImage(cgImage: cgImage, scale: 1, orientation: orientation)
  }

  func requestCenterFocus() {
    sessionQueue.async {
      guard let camera = (self.session.inputs.first as? AVCaptureDeviceInput)?.device else {
        return
      }
      do {
        try camera.lockForConfiguration()
        let center = CGPoint(x: 0.5, y: 0.5)
        if camera.isFocusPointOfInterestSupported {
          camera.focusPointOfInterest = center
          if camera.isFocusModeSupported(.autoFocus) {
            camera.focusMode = .autoFocus
          }
        }
        if camera.isExposurePointOfInterestSupported {
          camera.exposurePointOfInterest = center
          if camera.isExposureModeSupported(.continuousAutoExposure) {
            camera.exposureMode = .continuousAutoExposure
          }
        }
        camera.unlockForConfiguration()
      } catch {
        // Continuous camera operation is still preferable to failing the scan.
      }
    }
  }

  func captureHighQualityPhoto() async throws -> UIImage {
    try await withCheckedThrowingContinuation { continuation in
      sessionQueue.async {
        guard self.session.isRunning,
              self.session.outputs.contains(where: { $0 === self.photoOutput }) else {
          continuation.resume(throwing: CameraPhotoCaptureError.notReady)
          return
        }

        let settings = AVCapturePhotoSettings()
        settings.photoQualityPrioritization = .quality
        let identifier = settings.uniqueID
        let processor = PhotoCaptureProcessor { [weak self] result in
          self?.sessionQueue.async {
            self?.photoCaptureProcessors[identifier] = nil
          }
          continuation.resume(with: result)
        }
        self.photoCaptureProcessors[identifier] = processor
        self.photoOutput.capturePhoto(with: settings, delegate: processor)
      }
    }
  }

  func setTorch(enabled: Bool) {
    sessionQueue.async {
      guard let camera = (self.session.inputs.first as? AVCaptureDeviceInput)?.device,
            camera.hasTorch else { return }
      do {
        try camera.lockForConfiguration()
        if enabled, camera.isTorchModeSupported(.on) {
          try camera.setTorchModeOn(level: min(0.7, AVCaptureDevice.maxAvailableTorchLevel))
        } else if camera.isTorchModeSupported(.off) {
          camera.torchMode = .off
        }
        camera.unlockForConfiguration()
      } catch {
        // The magnifier remains usable without the torch.
      }
    }
  }

  private func setShouldDeliverFrames(_ value: Bool) {
    deliveryLock.lock()
    shouldDeliverFrames = value
    deliveryLock.unlock()
  }

  private func canDeliverFrames() -> Bool {
    deliveryLock.lock()
    defer { deliveryLock.unlock() }
    return shouldDeliverFrames
  }
}

private enum CameraPhotoCaptureError: LocalizedError {
  case notReady
  case noImageData

  var errorDescription: String? {
    switch self {
    case .notReady: "The camera is not ready to capture a photo."
    case .noImageData: "The captured photo could not be decoded."
    }
  }
}

private final class PhotoCaptureProcessor: NSObject, AVCapturePhotoCaptureDelegate {
  private let completion: (Result<UIImage, Error>) -> Void

  init(completion: @escaping (Result<UIImage, Error>) -> Void) {
    self.completion = completion
  }

  func photoOutput(
    _ output: AVCapturePhotoOutput,
    didFinishProcessingPhoto photo: AVCapturePhoto,
    error: Error?
  ) {
    if let error {
      completion(.failure(error))
      return
    }
    guard let data = photo.fileDataRepresentation(),
          let image = UIImage(data: data) else {
      completion(.failure(CameraPhotoCaptureError.noImageData))
      return
    }
    completion(.success(image))
  }
}

extension CameraFrameSource: AVCaptureVideoDataOutputSampleBufferDelegate {
  func captureOutput(
    _ output: AVCaptureOutput,
    didOutput sampleBuffer: CMSampleBuffer,
    from connection: AVCaptureConnection
  ) {
    guard canDeliverFrames() else { return }
    delegate?.cameraFrameSource(self, didOutput: sampleBuffer)
  }
}

extension CameraFrameSource: AVCaptureDataOutputSynchronizerDelegate {
  func dataOutputSynchronizer(
    _ synchronizer: AVCaptureDataOutputSynchronizer,
    didOutput synchronizedDataCollection: AVCaptureSynchronizedDataCollection
  ) {
    guard canDeliverFrames(),
          let videoData = synchronizedDataCollection.synchronizedData(for: videoOutput)
            as? AVCaptureSynchronizedSampleBufferData,
          !videoData.sampleBufferWasDropped else { return }

    delegate?.cameraFrameSource(self, didOutput: videoData.sampleBuffer)

    guard let synchronizedDepth = synchronizedDataCollection.synchronizedData(for: depthOutput)
            as? AVCaptureSynchronizedDepthData,
          !synchronizedDepth.depthDataWasDropped else { return }
    let observations = Self.summarize(depthData: synchronizedDepth.depthData)
    delegate?.cameraFrameSource(self, didUpdateDepth: observations)
  }

  private static func summarize(depthData: AVDepthData) -> [DepthObservation] {
    let converted = depthData.converting(toDepthDataType: kCVPixelFormatType_DepthFloat32)
    let map = converted.depthDataMap
    CVPixelBufferLockBaseAddress(map, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(map, .readOnly) }

    guard let baseAddress = CVPixelBufferGetBaseAddress(map) else { return [] }
    let width = CVPixelBufferGetWidth(map)
    let height = CVPixelBufferGetHeight(map)
    let rowStride = CVPixelBufferGetBytesPerRow(map) / MemoryLayout<Float32>.stride
    let values = baseAddress.assumingMemoryBound(to: Float32.self)
    let regions: [(Bearing, ClosedRange<Float>)] = [
      (.left, 0.08...0.36),
      (.center, 0.36...0.64),
      (.right, 0.64...0.92),
    ]

    return regions.compactMap { bearing, xRange in
      var samples: [Float] = []
      var attempted = 0
      let stepX = max(1, width / 36)
      let stepY = max(1, height / 28)
      let minX = Int(Float(width) * xRange.lowerBound)
      let maxX = min(width - 1, Int(Float(width) * xRange.upperBound))
      let minY = Int(Float(height) * 0.22)
      let maxY = min(height - 1, Int(Float(height) * 0.78))

      for y in stride(from: minY, through: maxY, by: stepY) {
        for x in stride(from: minX, through: maxX, by: stepX) {
          attempted += 1
          let value = values[y * rowStride + x]
          if value.isFinite, value >= 0.20, value <= 8.0 { samples.append(value) }
        }
      }

      guard samples.count >= 12 else { return nil }
      samples.sort()
      let distance = samples[min(samples.count - 1, Int(Float(samples.count) * 0.18))]
      guard distance <= 3.5 else { return nil }
      let lower = samples[Int(Float(samples.count - 1) * 0.25)]
      let upper = samples[Int(Float(samples.count - 1) * 0.75)]
      let coverage = Float(samples.count) / Float(max(1, attempted))
      let wallLike = coverage >= 0.65 && upper - lower <= 0.28
      return DepthObservation(
        bearing: bearing,
        distanceMeters: distance,
        confidence: min(1, coverage),
        surface: wallLike ? .wall : .unknown
      )
    }
  }
}
