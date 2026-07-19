import CoreMedia
import CoreML
import Foundation
import UIKit
import Vision

protocol ObjectDetectionService: AnyObject {
  var isAvailable: Bool { get }
  var modelName: String { get }
  func prepare() async
  func detect(image: UIImage) async -> [DetectionBox]
  func detectLive(sampleBuffer: CMSampleBuffer, timestampInMilliseconds: Int)
  var onLiveDetections: (([DetectionBox]) -> Void)? { get set }
}

/// A facade that routes detection requests to the selected backend model (YOLOv3 Tiny or YOLOv8 Nano)
final class EchoSenseObjectDetectionService: ObjectDetectionService, @unchecked Sendable {
  static let shared = EchoSenseObjectDetectionService()
  
  private var activeDetector: ObjectDetectionService {
    let selected = UserDefaults.standard.string(forKey: "echosense.selectedDetector") ?? ""
    if selected == ObjectDetectorModel.yolov8.rawValue {
      return YOLOv8ObjectDetectionService.shared
    } else {
      return AppleVisionObjectDetectionService.shared
    }
  }
  
  var isAvailable: Bool { activeDetector.isAvailable }
  var modelName: String { activeDetector.modelName }
  
  var onLiveDetections: (([DetectionBox]) -> Void)? {
    get { activeDetector.onLiveDetections }
    set {
      AppleVisionObjectDetectionService.shared.onLiveDetections = newValue
      YOLOv8ObjectDetectionService.shared.onLiveDetections = newValue
    }
  }
  
  func prepare() async {
    // Prepare both so switching is instant and fast
    await AppleVisionObjectDetectionService.shared.prepare()
    await YOLOv8ObjectDetectionService.shared.prepare()
  }
  
  func detect(image: UIImage) async -> [DetectionBox] {
    await activeDetector.detect(image: image)
  }
  
  func detectLive(sampleBuffer: CMSampleBuffer, timestampInMilliseconds: Int) {
    activeDetector.detectLive(sampleBuffer: sampleBuffer, timestampInMilliseconds: timestampInMilliseconds)
  }
}

// Keep the old typealias pointing to the unified facade so client code doesn't break
typealias MediaPipeObjectDetectionService = EchoSenseObjectDetectionService

/// CoreML implementation using Apple's Vision framework (typically running YOLOv3Tiny)
final class AppleVisionObjectDetectionService: ObjectDetectionService, @unchecked Sendable {
  static let shared = AppleVisionObjectDetectionService()

  private(set) var isAvailable = false
  private(set) var modelName = "YOLOv3 Tiny"
  var onLiveDetections: (([DetectionBox]) -> Void)?
  private let liveStateLock = NSLock()
  private let detectorQueue = DispatchQueue(label: "com.echosense.appleVisionObjectDetector")
  private var liveDetectionInFlight = false
  private var didAttemptConfigure = false
  private var request: VNCoreMLRequest?

  func prepare() async {
    await withCheckedContinuation { continuation in
      detectorQueue.async {
        self.configureIfNeeded()
        continuation.resume()
      }
    }
  }

  private func configureIfNeeded() {
    guard !didAttemptConfigure else { return }
    didAttemptConfigure = true

    guard let modelURL = Self.findBundledDetectorModelURL() else {
      isAvailable = false
      return
    }

    do {
      let configuration = MLModelConfiguration()
      configuration.computeUnits = .all
      let coreMLModel = try MLModel(contentsOf: modelURL, configuration: configuration)
      let visionModel = try VNCoreMLModel(for: coreMLModel)
      let request = VNCoreMLRequest(model: visionModel)
      request.imageCropAndScaleOption = .scaleFill
      self.request = request
      isAvailable = true
    } catch {
      isAvailable = false
    }
  }

  func detect(image: UIImage) async -> [DetectionBox] {
    await prepare()
    return await withCheckedContinuation { continuation in
      detectorQueue.async {
        guard let request = self.request, let cgImage = image.cgImage else {
          continuation.resume(returning: [])
          return
        }

        do {
          let handler = VNImageRequestHandler(cgImage: cgImage, orientation: image.cgImagePropertyOrientation)
          try handler.perform([request])
          let boxes = Self.map(
            observations: request.results,
            imageWidth: Int(image.size.width),
            imageHeight: Int(image.size.height)
          )
          continuation.resume(returning: boxes)
        } catch {
          continuation.resume(returning: [])
        }
      }
    }
  }

  func detectLive(sampleBuffer: CMSampleBuffer, timestampInMilliseconds: Int) {
    detectorQueue.async {
      guard let request = self.request else { return }
      guard self.beginLiveDetection() else { return }
      defer { self.finishLiveDetection() }

      do {
        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
          self.onLiveDetections?([])
          return
        }
        
        let width = CVPixelBufferGetWidth(imageBuffer)
        let height = CVPixelBufferGetHeight(imageBuffer)
        let isLandscape = width > height
        let orientation: CGImagePropertyOrientation = isLandscape ? .left : .up
        
        let handler = VNImageRequestHandler(cmSampleBuffer: sampleBuffer, orientation: orientation)
        try handler.perform([request])

        let imageWidth = isLandscape ? height : width
        let imageHeight = isLandscape ? width : height
        let boxes = Self.map(
          observations: request.results,
          imageWidth: max(1, imageWidth),
          imageHeight: max(1, imageHeight)
        )
        self.onLiveDetections?(boxes)
      } catch {
        self.onLiveDetections?([])
      }
    }
  }

  private func beginLiveDetection() -> Bool {
    liveStateLock.lock()
    defer { liveStateLock.unlock() }

    guard !liveDetectionInFlight else { return false }
    liveDetectionInFlight = true
    return true
  }

  private func finishLiveDetection() {
    liveStateLock.lock()
    defer { liveStateLock.unlock() }

    liveDetectionInFlight = false
  }

  private static func map(observations: [VNObservation]?, imageWidth: Int, imageHeight: Int) -> [DetectionBox] {
    let recognizedObjects = observations as? [VNRecognizedObjectObservation] ?? []
    return recognizedObjects.compactMap { observation in
      guard let label = observation.labels.first else { return nil }
      guard label.confidence >= 0.35 else { return nil }
      let rect = observation.boundingBox
      let x = Int(rect.minX * CGFloat(imageWidth))
      let y = Int((1 - rect.maxY) * CGFloat(imageHeight))
      let width = Int(rect.width * CGFloat(imageWidth))
      let height = Int(rect.height * CGFloat(imageHeight))
      guard width > 0, height > 0 else { return nil }

      return DetectionBox(
        label: label.identifier.isEmpty ? "Obstacle" : label.identifier,
        score: label.confidence,
        x: max(0, x),
        y: max(0, y),
        width: min(width, imageWidth),
        height: min(height, imageHeight),
        imageWidth: imageWidth,
        imageHeight: imageHeight
      )
    }
  }

  private static func findBundledDetectorModelURL() -> URL? {
    let preferredNames = [
      "EchoSenseObjectDetector",
      "ObjectDetector",
      "CollisionObjectDetector",
      "YOLOv3Tiny"
    ]

    for name in preferredNames {
      if let url = Bundle.main.url(forResource: name, withExtension: "mlmodelc") {
        return url
      }
    }

    return Bundle.main.urls(forResourcesWithExtension: "mlmodelc", subdirectory: nil)?.first
  }
}

/// CoreML implementation for YOLOv8 Object Detection
final class YOLOv8ObjectDetectionService: ObjectDetectionService, @unchecked Sendable {
  static let shared = YOLOv8ObjectDetectionService()
  
  private(set) var isAvailable = false
  private(set) var modelName = "YOLOv8 Nano"
  var onLiveDetections: (([DetectionBox]) -> Void)?
  
  private let detectorQueue = DispatchQueue(label: "com.echosense.yolov8.detector")
  private let liveStateLock = NSLock()
  private var liveDetectionInFlight = false
  private var model: MLModel?
  private var didAttemptConfigure = false
  
  func prepare() async {
    await withCheckedContinuation { continuation in
      detectorQueue.async {
        self.configureIfNeeded()
        continuation.resume()
      }
    }
  }
  
  private func configureIfNeeded() {
    guard !didAttemptConfigure else { return }
    didAttemptConfigure = true
    
    guard let modelURL = Bundle.main.url(forResource: "yolov8n", withExtension: "mlmodelc") else {
      isAvailable = false
      return
    }
    
    do {
      let configuration = MLModelConfiguration()
      configuration.computeUnits = .all
      self.model = try MLModel(contentsOf: modelURL, configuration: configuration)
      self.isAvailable = true
    } catch {
      print("Failed to load YOLOv8 CoreML model: \(error)")
      self.isAvailable = false
    }
  }
  
  func detect(image: UIImage) async -> [DetectionBox] {
    await prepare()
    
    return await withCheckedContinuation { continuation in
      detectorQueue.async {
        guard let model = self.model else {
          continuation.resume(returning: [])
          return
        }
        
        do {
          // Scale image to 640x640 for YOLOv8
          let targetSize = CGSize(width: 640, height: 640)
          let format = UIGraphicsImageRendererFormat.default()
          format.scale = 1.0
          let resizedImage = UIGraphicsImageRenderer(size: targetSize, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
          }
          
          guard let pixelBuffer = resizedImage.toCVPixelBuffer() else {
            continuation.resume(returning: [])
            return
          }
          
          let prediction = try model.prediction(from: YOLOv8Input(image: pixelBuffer))
          guard let multiArray = prediction.featureValue(for: "var_911")?.multiArrayValue else {
            continuation.resume(returning: [])
            return
          }
          
          let boxes = Self.parseYOLOv8Output(multiArray: multiArray, imageWidth: Int(image.size.width), imageHeight: Int(image.size.height))
          continuation.resume(returning: boxes)
        } catch {
          print("YOLOv8 detection failed: \(error)")
          continuation.resume(returning: [])
        }
      }
    }
  }
  
  func detectLive(sampleBuffer: CMSampleBuffer, timestampInMilliseconds: Int) {
    detectorQueue.async {
      guard let model = self.model else { return }
      guard self.beginLiveDetection() else { return }
      defer { self.finishLiveDetection() }
      
      do {
        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
          self.onLiveDetections?([])
          return
        }
        
        let width = CVPixelBufferGetWidth(imageBuffer)
        let height = CVPixelBufferGetHeight(imageBuffer)
        let isLandscape = width > height
        
        let ciImage = CIImage(cvPixelBuffer: imageBuffer)
        let context = CIContext()
        guard let cgImage = context.createCGImage(ciImage, from: ciImage.extent) else {
          self.onLiveDetections?([])
          return
        }
        
        let orientation: UIImage.Orientation = isLandscape ? .left : .up
        let uiImage = UIImage(cgImage: cgImage, scale: 1, orientation: orientation)
        
        // Scale and draw to 640x640 to bake orientation upright and resize
        let targetSize = CGSize(width: 640, height: 640)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1.0
        let resizedImage = UIGraphicsImageRenderer(size: targetSize, format: format).image { _ in
          uiImage.draw(in: CGRect(origin: .zero, size: targetSize))
        }
        
        guard let pixelBuffer = resizedImage.toCVPixelBuffer() else {
          self.onLiveDetections?([])
          return
        }
        
        let prediction = try model.prediction(from: YOLOv8Input(image: pixelBuffer))
        guard let multiArray = prediction.featureValue(for: "var_911")?.multiArrayValue else {
          self.onLiveDetections?([])
          return
        }
        
        let originalWidth = isLandscape ? height : width
        let originalHeight = isLandscape ? width : height
        
        let boxes = Self.parseYOLOv8Output(multiArray: multiArray, imageWidth: originalWidth, imageHeight: originalHeight)
        self.onLiveDetections?(boxes)
      } catch {
        self.onLiveDetections?([])
      }
    }
  }
  
  private func beginLiveDetection() -> Bool {
    liveStateLock.lock()
    defer { liveStateLock.unlock() }
    guard !liveDetectionInFlight else { return false }
    liveDetectionInFlight = true
    return true
  }
  
  private func finishLiveDetection() {
    liveStateLock.lock()
    defer { liveStateLock.unlock() }
    liveDetectionInFlight = false
  }
  
  private static func parseYOLOv8Output(multiArray: MLMultiArray, imageWidth: Int, imageHeight: Int) -> [DetectionBox] {
    var candidates: [DetectionBox] = []
    
    let numClasses = 80
    let numBoxes = 8400
    
    let ptr = UnsafeMutablePointer<Float32>(OpaquePointer(multiArray.dataPointer))
    let strides = multiArray.strides.map { $0.intValue }
    
    let rowStride = strides[1]
    let colStride = strides[2]
    
    for c in 0..<numBoxes {
      var maxScore: Float32 = 0.0
      var maxClassIndex = -1
      
      for r in 0..<numClasses {
        let score = ptr[(4 + r) * rowStride + c * colStride]
        if score > maxScore {
          maxScore = score
          maxClassIndex = r
        }
      }
      
      guard maxScore >= 0.35, maxClassIndex != -1 else { continue }
      
      let x_center = Double(ptr[0 * rowStride + c * colStride])
      let y_center = Double(ptr[1 * rowStride + c * colStride])
      let boxWidth = Double(ptr[2 * rowStride + c * colStride])
      let boxHeight = Double(ptr[3 * rowStride + c * colStride])
      
      let xmin = (x_center - (boxWidth / 2.0)) / 640.0
      let ymin = (y_center - (boxHeight / 2.0)) / 640.0
      let xmax = (x_center + (boxWidth / 2.0)) / 640.0
      let ymax = (y_center + (boxHeight / 2.0)) / 640.0
      
      let label = cocoClasses[maxClassIndex] ?? "Obstacle"
      
      let x = Int(xmin * Double(imageWidth))
      let y = Int(ymin * Double(imageHeight))
      let w = Int((xmax - xmin) * Double(imageWidth))
      let h = Int((ymax - ymin) * Double(imageHeight))
      
      candidates.append(DetectionBox(
        label: label,
        score: maxScore,
        x: max(0, x),
        y: max(0, y),
        width: min(w, imageWidth),
        height: min(h, imageHeight),
        imageWidth: imageWidth,
        imageHeight: imageHeight
      ))
    }
    
    return performNMS(candidates: candidates, iouThreshold: 0.45)
  }
  
  private static func performNMS(candidates: [DetectionBox], iouThreshold: Float) -> [DetectionBox] {
    let sorted = candidates.sorted { $0.score > $1.score }
    var selected: [DetectionBox] = []
    
    for candidate in sorted {
      var keep = true
      for active in selected {
        let intersectionWidth = max(0, min(candidate.x + candidate.width, active.x + active.width) - max(candidate.x, active.x))
        let intersectionHeight = max(0, min(candidate.y + candidate.height, active.y + active.height) - max(candidate.y, active.y))
        let intersectionArea = Float(intersectionWidth * intersectionHeight)
        
        let candidateArea = Float(candidate.width * candidate.height)
        let activeArea = Float(active.width * active.height)
        let unionArea = candidateArea + activeArea - intersectionArea
        
        let iou = unionArea > 0 ? (intersectionArea / unionArea) : 0.0
        if iou > iouThreshold {
          keep = false
          break
        }
      }
      if keep {
        selected.append(candidate)
      }
    }
    return selected
  }
  
  private static let cocoClasses: [Int: String] = [
    0: "person", 1: "bicycle", 2: "car", 3: "motorcycle", 4: "airplane", 5: "bus", 6: "train", 7: "truck", 8: "boat",
    9: "traffic light", 10: "fire hydrant", 11: "stop sign", 12: "parking meter", 13: "bench", 14: "bird", 15: "cat",
    16: "dog", 17: "horse", 18: "sheep", 19: "cow", 20: "elephant", 21: "bear", 22: "zebra", 23: "giraffe",
    24: "backpack", 25: "umbrella", 26: "handbag", 27: "tie", 28: "suitcase", 29: "frisbee", 30: "skis",
    31: "snowboard", 32: "sports ball", 33: "kite", 34: "baseball bat", 35: "baseball glove", 36: "skateboard",
    37: "surfboard", 38: "tennis racket", 39: "bottle", 40: "wine glass", 41: "cup", 42: "fork", 43: "knife",
    44: "spoon", 45: "bowl", 46: "banana", 47: "apple", 48: "sandwich", 49: "orange", 50: "broccoli",
    51: "carrot", 52: "hot dog", 53: "pizza", 54: "donut", 55: "cake", 56: "chair", 57: "couch",
    58: "potted plant", 59: "bed", 60: "dining table", 61: "toilet", 62: "tv", 63: "laptop", 64: "mouse",
    65: "remote", 66: "keyboard", 67: "cell phone", 68: "microwave", 69: "oven", 70: "toaster", 71: "sink",
    72: "refrigerator", 73: "book", 74: "clock", 75: "vase", 76: "scissors", 77: "teddy bear", 78: "hair drier",
    79: "toothbrush"
  ]
}

/// Helper model input conforming to MLFeatureProvider for custom prediction runs
private final class YOLOv8Input: MLFeatureProvider {
  var image: CVPixelBuffer
  
  var featureNames: Set<String> {
    return ["image"]
  }
  
  func featureValue(for featureName: String) -> MLFeatureValue? {
    if featureName == "image" {
      return MLFeatureValue(pixelBuffer: image)
    }
    return nil
  }
  
  init(image: CVPixelBuffer) {
    self.image = image
  }
}

private extension UIImage {
  var cgImagePropertyOrientation: CGImagePropertyOrientation {
    switch imageOrientation {
    case .up: .up
    case .down: .down
    case .left: .left
    case .right: .right
    case .upMirrored: .upMirrored
    case .downMirrored: .downMirrored
    case .leftMirrored: .leftMirrored
    case .rightMirrored: .rightMirrored
    @unknown default: .up
    }
  }

  func toCVPixelBuffer() -> CVPixelBuffer? {
    let attrs = [
      kCVPixelBufferCGImageCompatibilityKey: kCFBooleanTrue,
      kCVPixelBufferCGBitmapContextCompatibilityKey: kCFBooleanTrue
    ] as CFDictionary
    
    var pixelBuffer: CVPixelBuffer?
    let status = CVPixelBufferCreate(
      kCFAllocatorDefault,
      Int(size.width),
      Int(size.height),
      kCVPixelFormatType_32BGRA,
      attrs,
      &pixelBuffer
    )
    
    guard status == kCVReturnSuccess, let buffer = pixelBuffer else {
      return nil
    }
    
    CVPixelBufferLockBaseAddress(buffer, CVPixelBufferLockFlags(rawValue: 0))
    defer { CVPixelBufferUnlockBaseAddress(buffer, CVPixelBufferLockFlags(rawValue: 0)) }
    
    let pixelData = CVPixelBufferGetBaseAddress(buffer)
    let rgbColorSpace = CGColorSpaceCreateDeviceRGB()
    
    guard let context = CGContext(
      data: pixelData,
      width: Int(size.width),
      height: Int(size.height),
      bitsPerComponent: 8,
      bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
      space: rgbColorSpace,
      bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
    ) else {
      return nil
    }
    
    UIGraphicsPushContext(context)
    draw(in: CGRect(x: 0, y: 0, width: size.width, height: size.height))
    UIGraphicsPopContext()
    
    return buffer
  }
}
