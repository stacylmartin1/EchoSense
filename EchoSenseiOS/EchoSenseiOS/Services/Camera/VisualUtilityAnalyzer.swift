import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import UIKit

struct CameraColorResult: Equatable {
  let name: String
  let brightnessPercent: Int

  var spokenDescription: String {
    "\(name). Approximate brightness \(brightnessPercent) percent."
  }
}

struct CameraLightResult: Equatable {
  let level: String
  let brightnessPercent: Int

  var spokenDescription: String {
    "\(level) light. Approximate brightness \(brightnessPercent) percent."
  }
}

enum VisualUtilityAnalyzer {
  private static let context = CIContext(options: [.cacheIntermediates: false])

  static func centerColor(in image: UIImage) -> CameraColorResult? {
    guard let input = CIImage(image: image) else { return nil }
    let extent = input.extent
    guard extent.width > 0, extent.height > 0 else { return nil }

    let sampleWidth = max(1, extent.width * 0.06)
    let sampleHeight = max(1, extent.height * 0.06)
    let sampleRect = CGRect(
      x: extent.midX - sampleWidth / 2,
      y: extent.midY - sampleHeight / 2,
      width: sampleWidth,
      height: sampleHeight
    )
    let samples = sampledRGBAs(of: input, in: sampleRect)
    guard !samples.isEmpty else { return nil }
    let names = samples.map { rgba -> String in
      let hsv = rgbToHSV(red: rgba.red, green: rgba.green, blue: rgba.blue)
      return colorName(hue: hsv.hue, saturation: hsv.saturation, value: hsv.value)
    }
    let dominantName = names.reduce(into: [String: Int]()) { counts, name in
      counts[name, default: 0] += 1
    }
    .max { lhs, rhs in lhs.value < rhs.value }?
    .key ?? "Unknown color"
    let luminances = samples.map(relativeLuminance).sorted()
    let medianLuminance = luminances[luminances.count / 2]
    return CameraColorResult(
      name: dominantName,
      brightnessPercent: Int((medianLuminance * 100).rounded())
    )
  }

  static func lightLevel(in image: UIImage) -> CameraLightResult? {
    guard let input = CIImage(image: image),
          let rgba = averageRGBA(of: input, in: input.extent) else { return nil }
    let brightness = relativeLuminance(rgba)
    let level: String
    switch brightness {
    case ..<0.08: level = "Very dark"
    case ..<0.22: level = "Dim"
    case ..<0.50: level = "Moderate"
    case ..<0.78: level = "Bright"
    default: level = "Very bright"
    }
    return CameraLightResult(
      level: level,
      brightnessPercent: Int((brightness * 100).rounded())
    )
  }

  private static func averageRGBA(of image: CIImage, in extent: CGRect) -> RGBA? {
    guard !extent.isEmpty else { return nil }
    let filter = CIFilter.areaAverage()
    filter.inputImage = image
    filter.extent = extent
    guard let output = filter.outputImage else { return nil }

    var bytes = [UInt8](repeating: 0, count: 4)
    context.render(
      output,
      toBitmap: &bytes,
      rowBytes: 4,
      bounds: CGRect(x: 0, y: 0, width: 1, height: 1),
      format: .RGBA8,
      colorSpace: CGColorSpace(name: CGColorSpace.sRGB)
    )
    return RGBA(
      red: Double(bytes[0]) / 255,
      green: Double(bytes[1]) / 255,
      blue: Double(bytes[2]) / 255
    )
  }

  private static func sampledRGBAs(of image: CIImage, in extent: CGRect) -> [RGBA] {
    guard !extent.isEmpty else { return [] }
    let side = 21
    let translated = image
      .cropped(to: extent)
      .transformed(by: CGAffineTransform(translationX: -extent.minX, y: -extent.minY))
      .transformed(
        by: CGAffineTransform(
          scaleX: CGFloat(side) / extent.width,
          y: CGFloat(side) / extent.height
        )
      )
    var bytes = [UInt8](repeating: 0, count: side * side * 4)
    context.render(
      translated,
      toBitmap: &bytes,
      rowBytes: side * 4,
      bounds: CGRect(x: 0, y: 0, width: side, height: side),
      format: .RGBA8,
      colorSpace: CGColorSpace(name: CGColorSpace.sRGB)
    )
    return stride(from: 0, to: bytes.count, by: 4).map { offset in
      RGBA(
        red: Double(bytes[offset]) / 255,
        green: Double(bytes[offset + 1]) / 255,
        blue: Double(bytes[offset + 2]) / 255
      )
    }
  }

  private static func relativeLuminance(_ rgba: RGBA) -> Double {
    let linearize: (Double) -> Double = { component in
      component <= 0.04045
        ? component / 12.92
        : pow((component + 0.055) / 1.055, 2.4)
    }
    return min(
      1,
      max(
        0,
        0.2126 * linearize(rgba.red)
          + 0.7152 * linearize(rgba.green)
          + 0.0722 * linearize(rgba.blue)
      )
    )
  }

  private static func rgbToHSV(red: Double, green: Double, blue: Double)
    -> (hue: Double, saturation: Double, value: Double)
  {
    let maximum = max(red, green, blue)
    let minimum = min(red, green, blue)
    let delta = maximum - minimum
    let saturation = maximum == 0 ? 0 : delta / maximum
    guard delta > 0 else { return (0, saturation, maximum) }

    let rawHue: Double
    if maximum == red {
      rawHue = 60 * ((green - blue) / delta).truncatingRemainder(dividingBy: 6)
    } else if maximum == green {
      rawHue = 60 * (((blue - red) / delta) + 2)
    } else {
      rawHue = 60 * (((red - green) / delta) + 4)
    }
    return (rawHue < 0 ? rawHue + 360 : rawHue, saturation, maximum)
  }

  private static func colorName(hue: Double, saturation: Double, value: Double) -> String {
    if value < 0.10 { return "Black" }
    if saturation < 0.10 {
      if value > 0.80 { return "White" }
      if value < 0.32 { return "Dark gray" }
      if value > 0.72 { return "Light gray" }
      return "Gray"
    }
    if value < 0.38, hue >= 15, hue < 55 { return "Brown" }
    if saturation < 0.28 {
      if value > 0.82 { return "Off-white" }
      return "Gray"
    }

    switch hue {
    case 0..<15, 345...360: return "Red"
    case 15..<42: return value < 0.62 ? "Brown" : "Orange"
    case 42..<70: return "Yellow"
    case 70..<155: return "Green"
    case 155..<190: return "Teal"
    case 190..<255: return "Blue"
    case 255..<290: return "Purple"
    case 290..<345: return "Pink"
    default: return "Unknown color"
    }
  }

  private struct RGBA {
    let red: Double
    let green: Double
    let blue: Double
  }
}
