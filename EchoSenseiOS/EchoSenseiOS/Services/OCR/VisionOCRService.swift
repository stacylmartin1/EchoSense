import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import UIKit
import Vision

struct OCRTextLine: Equatable {
  let text: String
  let boundingBox: CGRect
  let confidence: Float
}

struct OCRResult: Equatable {
  let text: String
  let lines: [OCRTextLine]
  let script: String?
  let processingTime: TimeInterval

  static let empty = OCRResult(text: "", lines: [], script: nil, processingTime: 0)
}

enum OCRLanguageOption: String, CaseIterable, Identifiable {
  case automatic
  case english
  case spanish
  case french
  case german
  case chineseSimplified
  case japanese
  case korean

  var id: String { rawValue }

  var title: String {
    switch self {
    case .automatic: "Automatic"
    case .english: "English"
    case .spanish: "Spanish"
    case .french: "French"
    case .german: "German"
    case .chineseSimplified: "Chinese"
    case .japanese: "Japanese"
    case .korean: "Korean"
    }
  }

  var recognitionLanguages: [String] {
    switch self {
    case .automatic: []
    case .english: ["en-US"]
    case .spanish: ["es-ES"]
    case .french: ["fr-FR"]
    case .german: ["de-DE"]
    case .chineseSimplified: ["zh-Hans"]
    case .japanese: ["ja-JP"]
    case .korean: ["ko-KR"]
    }
  }
}

protocol OCRService {
  var backendName: String { get }
  func recognize(in image: UIImage, language: OCRLanguageOption) async throws -> OCRResult
}

extension OCRService {
  func recognize(in image: UIImage) async throws -> OCRResult {
    try await recognize(in: image, language: .automatic)
  }

  func recognizeText(in image: UIImage) async throws -> String {
    try await recognize(in: image).text
  }
}

final class VisionOCRService: OCRService {
  let backendName = "Apple Vision"

  func recognize(in image: UIImage, language: OCRLanguageOption) async throws -> OCRResult {
    guard let cgImage = image.cgImage else { return .empty }
    let orientation = image.cgImagePropertyOrientation

    return try await withCheckedThrowingContinuation { continuation in
      DispatchQueue.global(qos: .userInitiated).async {
        let startedAt = ProcessInfo.processInfo.systemUptime
        let request = VNRecognizeTextRequest { request, error in
          if let error {
            continuation.resume(throwing: error)
            return
          }

          let observations = request.results as? [VNRecognizedTextObservation] ?? []
          let lines = observations.compactMap { observation -> OCRTextLine? in
            guard let candidate = observation.topCandidates(1).first else { return nil }
            return OCRTextLine(
              text: candidate.string,
              boundingBox: observation.boundingBox,
              confidence: candidate.confidence
            )
          }
          let text = lines
            .map(\.text)
            .joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
          continuation.resume(
            returning: OCRResult(
              text: text,
              lines: lines,
              script: Self.dominantScript(in: text),
              processingTime: ProcessInfo.processInfo.systemUptime - startedAt
            )
          )
        }
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        request.recognitionLanguages = language.recognitionLanguages
        request.automaticallyDetectsLanguage = language == .automatic

        let handler = VNImageRequestHandler(cgImage: cgImage, orientation: orientation)
        do {
          try handler.perform([request])
        } catch {
          continuation.resume(throwing: error)
        }
      }
    }
  }

  private static func dominantScript(in text: String) -> String? {
    var counts: [String: Int] = [:]
    for scalar in text.unicodeScalars {
      let value = scalar.value
      let script: String?
      switch value {
      case 0x0041...0x024F: script = "Latin"
      case 0x0400...0x052F: script = "Cyrillic"
      case 0x0600...0x06FF: script = "Arabic"
      case 0x0900...0x097F: script = "Devanagari"
      case 0x0E00...0x0E7F: script = "Thai"
      case 0x3040...0x30FF: script = "Japanese"
      case 0x3400...0x4DBF, 0x4E00...0x9FFF: script = "CJK"
      case 0xAC00...0xD7AF: script = "Korean"
      default: script = nil
      }
      if let script {
        counts[script, default: 0] += 1
      }
    }
    return counts.max { $0.value < $1.value }?.key
  }
}

struct DocumentQuadrilateral: Equatable {
  let topLeft: CGPoint
  let topRight: CGPoint
  let bottomRight: CGPoint
  let bottomLeft: CGPoint

  var boundingBox: CGRect {
    let points = [topLeft, topRight, bottomRight, bottomLeft]
    let xs = points.map(\.x)
    let ys = points.map(\.y)
    return CGRect(
      x: xs.min() ?? 0,
      y: ys.min() ?? 0,
      width: (xs.max() ?? 0) - (xs.min() ?? 0),
      height: (ys.max() ?? 0) - (ys.min() ?? 0)
    )
  }

  func maximumCornerDistance(from other: DocumentQuadrilateral) -> CGFloat {
    zip(
      [topLeft, topRight, bottomRight, bottomLeft],
      [other.topLeft, other.topRight, other.bottomRight, other.bottomLeft]
    )
    .map { hypot($0.x - $1.x, $0.y - $1.y) }
    .max() ?? .greatestFiniteMagnitude
  }
}

struct DocumentFrameObservation {
  let quadrilateral: DocumentQuadrilateral?
  let guidance: String

  var isCaptureReady: Bool {
    quadrilateral != nil && guidance == "Hold steady"
  }
}

/// Offline page-boundary detection and perspective correction used by Guided Scan.
final class VisionDocumentCaptureService {
  private let context = CIContext(options: [.useSoftwareRenderer: false])

  func observe(in image: UIImage) async throws -> DocumentFrameObservation {
    let normalized = image.normalizedForDocumentProcessing()
    guard let cgImage = normalized.cgImage else {
      return DocumentFrameObservation(
        quadrilateral: nil,
        guidance: "Camera image is not ready"
      )
    }

    let quadrilateral = try await detectQuadrilateral(in: cgImage)
    guard let quadrilateral else {
      return DocumentFrameObservation(
        quadrilateral: nil,
        guidance: "No complete page detected. Center one page in the view"
      )
    }
    return DocumentFrameObservation(
      quadrilateral: quadrilateral,
      guidance: guidance(for: quadrilateral)
    )
  }

  func correctAndEnhance(_ image: UIImage) async throws -> UIImage {
    let normalized = image.normalizedForDocumentProcessing()
    guard let cgImage = normalized.cgImage else { return normalized }
    guard let quadrilateral = try await detectQuadrilateral(in: cgImage) else {
      return enhance(normalized)
    }

    let input = CIImage(cgImage: cgImage)
    let extent = input.extent
    let correction = CIFilter.perspectiveCorrection()
    correction.inputImage = input
    correction.topLeft = ciPoint(quadrilateral.topLeft, in: extent)
    correction.topRight = ciPoint(quadrilateral.topRight, in: extent)
    correction.bottomRight = ciPoint(quadrilateral.bottomRight, in: extent)
    correction.bottomLeft = ciPoint(quadrilateral.bottomLeft, in: extent)
    guard let corrected = correction.outputImage else { return enhance(normalized) }
    return renderEnhanced(corrected) ?? normalized
  }

  private func detectQuadrilateral(in cgImage: CGImage) async throws -> DocumentQuadrilateral? {
    try await withCheckedThrowingContinuation { continuation in
      DispatchQueue.global(qos: .userInitiated).async {
        let request = VNDetectRectanglesRequest { request, error in
          if let error {
            continuation.resume(throwing: error)
            return
          }
          guard let rectangle = (request.results as? [VNRectangleObservation])?.first else {
            continuation.resume(returning: nil)
            return
          }
          continuation.resume(
            returning: DocumentQuadrilateral(
              topLeft: rectangle.topLeft,
              topRight: rectangle.topRight,
              bottomRight: rectangle.bottomRight,
              bottomLeft: rectangle.bottomLeft
            )
          )
        }
        request.maximumObservations = 1
        request.minimumConfidence = 0.62
        request.minimumAspectRatio = 0.25
        request.maximumAspectRatio = 1
        request.minimumSize = 0.22
        request.quadratureTolerance = 28
        do {
          try VNImageRequestHandler(cgImage: cgImage, orientation: .up).perform([request])
        } catch {
          continuation.resume(throwing: error)
        }
      }
    }
  }

  private func guidance(for quadrilateral: DocumentQuadrilateral) -> String {
    let box = quadrilateral.boundingBox
    let area = box.width * box.height
    if box.minX < 0.025 { return "Left edge is too close. Move the camera left" }
    if box.maxX > 0.975 { return "Right edge is too close. Move the camera right" }
    if box.minY < 0.025 { return "Bottom edge is too close. Move the camera down" }
    if box.maxY > 0.975 { return "Top edge is too close. Move the camera up" }
    if area < 0.28 { return "Move closer so the page fills more of the view" }
    if area > 0.88 { return "Move farther away so every page edge is visible" }
    return "Hold steady"
  }

  private func ciPoint(_ point: CGPoint, in extent: CGRect) -> CGPoint {
    CGPoint(
      x: extent.minX + point.x * extent.width,
      y: extent.minY + point.y * extent.height
    )
  }

  private func enhance(_ image: UIImage) -> UIImage {
    guard let ciImage = CIImage(image: image) else { return image }
    return renderEnhanced(ciImage) ?? image
  }

  private func renderEnhanced(_ image: CIImage) -> UIImage? {
    let controls = CIFilter.colorControls()
    controls.inputImage = image
    controls.saturation = 0
    controls.contrast = 1.18
    controls.brightness = 0.015

    let sharpen = CIFilter.sharpenLuminance()
    sharpen.inputImage = controls.outputImage
    sharpen.sharpness = 0.35
    guard let output = sharpen.outputImage,
          let cgImage = context.createCGImage(output, from: output.extent) else { return nil }
    return UIImage(cgImage: cgImage)
  }
}

private extension UIImage {
  func normalizedForDocumentProcessing() -> UIImage {
    guard imageOrientation != .up else { return self }
    return UIGraphicsImageRenderer(size: size).image { _ in
      draw(in: CGRect(origin: .zero, size: size))
    }
  }

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
}
