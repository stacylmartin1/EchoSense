import AVFoundation
import Foundation
import UIKit

protocol CameraFrameSourceDelegate: AnyObject {
  func cameraFrameSource(_ source: CameraFrameSource, authorizationDidChange status: AVAuthorizationStatus)
  func cameraFrameSourceDidStart(_ source: CameraFrameSource)
  func cameraFrameSource(_ source: CameraFrameSource, didOutput sampleBuffer: CMSampleBuffer)
}

final class CameraFrameSource: NSObject, ObservableObject {
  let session = AVCaptureSession()
  weak var delegate: CameraFrameSourceDelegate?

  @Published private(set) var isRunning = false
  @Published private(set) var authorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)

  private let sessionQueue = DispatchQueue(label: "com.echosense.camera.session")
  private let videoOutput = AVCaptureVideoDataOutput()
  private let deliveryLock = NSLock()
  private var shouldDeliverFrames = false

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

    guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
          let input = try? AVCaptureDeviceInput(device: camera),
          session.canAddInput(input) else {
      session.commitConfiguration()
      return
    }
    session.addInput(input)

    videoOutput.alwaysDiscardsLateVideoFrames = true
    videoOutput.videoSettings = [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
    ]
    videoOutput.setSampleBufferDelegate(self, queue: DispatchQueue(label: "com.echosense.camera.frames"))
    if session.canAddOutput(videoOutput) {
      session.addOutput(videoOutput)
    }
    if let connection = videoOutput.connection(with: .video), connection.isVideoRotationAngleSupported(90) {
      connection.videoRotationAngle = 90
    }
    session.commitConfiguration()
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
