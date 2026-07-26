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
import CoreImage
import ImageIO
import UIKit
import Vision

struct ScannedVisualCode: Equatable {
  let type: String
  let value: String

  var displayDescription: String {
    "\(type): \(value)"
  }

  var spokenDescription: String {
    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
    if let url = URL(string: trimmed),
       let scheme = url.scheme?.lowercased(),
       ["http", "https"].contains(scheme) {
      return "\(type) containing a web address for \(url.host ?? "an unknown site")."
    }
    if trimmed.uppercased().hasPrefix("WIFI:") {
      let network = Self.wifiField("S", in: trimmed)
      return network.map { "\(type) containing Wi-Fi information for network \($0)." }
        ?? "\(type) containing Wi-Fi network information."
    }
    if trimmed.lowercased().hasPrefix("mailto:") {
      return "\(type) containing an email address."
    }
    if trimmed.lowercased().hasPrefix("tel:") {
      return "\(type) containing a telephone number."
    }
    if trimmed.allSatisfy(\.isNumber), trimmed.count >= 8 {
      return "\(type), \(trimmed.map(String.init).joined(separator: " "))."
    }
    let safeValue = trimmed.count > 180 ? String(trimmed.prefix(180)) + "…" : trimmed
    return "\(type): \(safeValue)"
  }

  private static func wifiField(_ name: String, in payload: String) -> String? {
    payload
      .split(separator: ";")
      .map(String.init)
      .first { $0.hasPrefix("\(name):") }?
      .dropFirst(name.count + 1)
      .description
  }
}

protocol BarcodeScanning {
  func scan(image: UIImage) async throws -> [ScannedVisualCode]
}

struct VisionBarcodeScanner: BarcodeScanning {
  func scan(image: UIImage) async throws -> [ScannedVisualCode] {
    guard let cgImage = image.cgImage else { return [] }
    return try await Task.detached(priority: .userInitiated) {
      let orientation = CGImagePropertyOrientation(image.imageOrientation)
      var observations = try Self.detect(
        cgImage: cgImage,
        orientation: orientation,
        symbologies: nil
      )
      observations += try Self.detect(
        cgImage: cgImage,
        orientation: orientation,
        symbologies: [.qr]
      )
      if let enhanced = Self.enhancedCodeImage(from: cgImage) {
        observations += try Self.detect(
          cgImage: enhanced,
          orientation: orientation,
          symbologies: nil
        )
        observations += try Self.detect(
          cgImage: enhanced,
          orientation: orientation,
          symbologies: [.qr]
        )
      }
      var seen = Set<String>()
      return observations
        .sorted { $0.confidence > $1.confidence }
        .compactMap { observation -> ScannedVisualCode? in
          guard let payload = observation.payloadStringValue?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            !payload.isEmpty,
            seen.insert(payload).inserted else { return nil }
          return ScannedVisualCode(
            type: Self.displayName(for: observation.symbology),
            value: payload
          )
        }
        .prefix(3)
        .map { $0 }
    }.value
  }

  private static func detect(
    cgImage: CGImage,
    orientation: CGImagePropertyOrientation,
    symbologies: [VNBarcodeSymbology]?
  ) throws -> [VNBarcodeObservation] {
    let request = VNDetectBarcodesRequest()
    if let symbologies {
      request.symbologies = symbologies
    }
    let handler = VNImageRequestHandler(
      cgImage: cgImage,
      orientation: orientation,
      options: [:]
    )
    try handler.perform([request])
    return request.results ?? []
  }

  private static func enhancedCodeImage(from cgImage: CGImage) -> CGImage? {
    let input = CIImage(cgImage: cgImage)
    let controls = CIFilter(name: "CIColorControls")
    controls?.setValue(input, forKey: kCIInputImageKey)
    controls?.setValue(0.0, forKey: kCIInputSaturationKey)
    controls?.setValue(1.65, forKey: kCIInputContrastKey)
    let sharpened = (controls?.outputImage ?? input).applyingFilter(
      "CISharpenLuminance",
      parameters: [kCIInputSharpnessKey: 0.7]
    )
    return CIContext(options: [.cacheIntermediates: false]).createCGImage(
      sharpened,
      from: sharpened.extent
    )
  }

  private static func displayName(for symbology: VNBarcodeSymbology) -> String {
    switch symbology {
    case .qr: "QR code"
    case .ean13: "EAN-13 barcode"
    case .ean8: "EAN-8 barcode"
    case .upce: "UPC-E barcode"
    case .code128: "Code 128 barcode"
    case .code39, .code39Checksum, .code39FullASCII, .code39FullASCIIChecksum:
      "Code 39 barcode"
    case .code93, .code93i: "Code 93 barcode"
    case .pdf417: "PDF417 barcode"
    case .aztec: "Aztec code"
    case .dataMatrix: "Data Matrix code"
    case .itf14: "ITF-14 barcode"
    default: "Barcode"
    }
  }
}

private extension CGImagePropertyOrientation {
  init(_ orientation: UIImage.Orientation) {
    switch orientation {
    case .up: self = .up
    case .upMirrored: self = .upMirrored
    case .down: self = .down
    case .downMirrored: self = .downMirrored
    case .left: self = .left
    case .leftMirrored: self = .leftMirrored
    case .right: self = .right
    case .rightMirrored: self = .rightMirrored
    @unknown default: self = .up
    }
  }
}
