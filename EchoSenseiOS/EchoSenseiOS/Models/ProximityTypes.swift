import Foundation

enum Bearing: String {
  case left
  case center
  case right
}

enum ProximitySeverity: Int {
  case info = 0
  case warning = 1
  case urgent = 2
}

struct DetectionBox: Identifiable, Equatable {
  let id = UUID()
  var label: String
  var score: Float
  var x: Int
  var y: Int
  var width: Int
  var height: Int
  var imageWidth: Int
  var imageHeight: Int

  var centerXNormalized: Float {
    Float(x + width / 2) / Float(max(1, imageWidth))
  }

  var centerYNormalized: Float {
    Float(y + height / 2) / Float(max(1, imageHeight))
  }
}

struct ProximityAlert: Equatable {
  var label: String
  var severity: ProximitySeverity
  var bearing: Bearing
  var distanceMeters: Float?
  var relativeDepth: Float?
  var timestamp = Date()
}

func bearing(fromCenterXNormalized centerX: Float, deadZone: Float = 0.2) -> Bearing {
  let offset = centerX - 0.5
  if abs(offset) <= deadZone / 2 { return .center }
  return offset < 0 ? .left : .right
}

func severity(forRelativeDepth relativeDepth: Float) -> ProximitySeverity {
  switch relativeDepth {
  case ...0.20: .urgent
  case ...0.35: .warning
  default: .info
  }
}

