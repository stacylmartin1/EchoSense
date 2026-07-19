import Foundation
import UIKit
import Vision

protocol OCRService {
  func recognizeText(in image: UIImage) async throws -> String
}

final class VisionOCRService: OCRService {
  func recognizeText(in image: UIImage) async throws -> String {
    guard let cgImage = image.cgImage else { return "" }

    return try await withCheckedThrowingContinuation { continuation in
      let request = VNRecognizeTextRequest { request, error in
        if let error {
          continuation.resume(throwing: error)
          return
        }

        let observations = request.results as? [VNRecognizedTextObservation] ?? []
        let text = observations
          .compactMap { $0.topCandidates(1).first?.string }
          .joined(separator: "\n")
          .trimmingCharacters(in: .whitespacesAndNewlines)
        continuation.resume(returning: text)
      }
      request.recognitionLevel = .accurate
      request.usesLanguageCorrection = true
      request.automaticallyDetectsLanguage = true

      let handler = VNImageRequestHandler(cgImage: cgImage, orientation: image.cgImagePropertyOrientation)
      do {
        try handler.perform([request])
      } catch {
        continuation.resume(throwing: error)
      }
    }
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
}

