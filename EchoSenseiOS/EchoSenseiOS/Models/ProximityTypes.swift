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

enum DepthSurface: String, Equatable {
  case unknown
  case wall
}

/// A coarse, confidence-filtered range sample for one third of the camera view.
/// Keeping this independent of a specific depth SDK lets Safety combine metric
/// ranging with object labels while retaining the monocular fallback.
struct DepthObservation: Equatable {
  var bearing: Bearing
  var distanceMeters: Float
  var confidence: Float
  var surface: DepthSurface
  var timestamp = Date()
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

func severity(forDistanceMeters distanceMeters: Float) -> ProximitySeverity {
  switch distanceMeters {
  case ...1.0: .urgent
  case ...2.0: .warning
  default: .info
  }
}
