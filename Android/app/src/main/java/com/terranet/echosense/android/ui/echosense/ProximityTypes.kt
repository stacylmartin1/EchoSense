/*
 * Copyright 2025 Google LLC
 * Modifications Copyright 2026 TerraNet Technologies LLC
 * Modified from TerraNet Technologies LLC implementation
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

package com.terranet.echosense.android.ui.echosense

import kotlin.math.abs
import kotlin.math.max

/**
 * Bearing of an obstacle relative to the camera optical center.
 */
enum class Bearing {
  LEFT, CENTER, RIGHT
}

/**
 * Severity of proximity alert.
 */
enum class ProximitySeverity {
  INFO, WARNING, URGENT
}

enum class DepthSurface {
  UNKNOWN, WALL
}

/** Confidence-filtered metric range for one third of the visible camera view. */
data class DepthObservation(
  val bearing: Bearing,
  val distanceMeters: Float,
  val confidence: Float,
  val surface: DepthSurface = DepthSurface.UNKNOWN,
  val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * Obstacle proximity alert data.
 * If metric is true, distanceMeters is populated and isRelativeDepth should be false.
 * If metric is false (relative depth), relativeDepth in 0..1 is populated and isRelativeDepth is true.
 */
data class ProximityAlert(
  val label: String,                // e.g., "Obstacle", "Chair", "Person"
  val severity: ProximitySeverity,  // urgency level
  val bearing: Bearing,             // left/center/right
  val distanceMeters: Float? = null,
  val relativeDepth: Float? = null, // 0..1 near..far for monocular fallback
  val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Simple detection structure from detector.
 */
data class DetectionBox(
  val label: String,
  val score: Float,
  val x: Int,          // left
  val y: Int,          // top
  val width: Int,
  val height: Int,
  val imageWidth: Int,
  val imageHeight: Int,
)

/**
 * Convenience getters
 */
fun DetectionBox.centerX(): Int = x + width / 2
fun DetectionBox.centerY(): Int = y + height / 2
fun DetectionBox.centerXNormalized(): Float = (centerX().toFloat() / max(1, imageWidth).toFloat())
fun DetectionBox.centerYNormalized(): Float = (centerY().toFloat() / max(1, imageHeight).toFloat())

/**
 * Determine bearing bucket from normalized center X [0..1].
 */
fun bearingFromCenterXNorm(cx: Float, deadZone: Float = 0.2f): Bearing {
  val offset = cx - 0.5f
  return when {
    abs(offset) <= deadZone / 2f -> Bearing.CENTER
    offset < 0 -> Bearing.LEFT
    else -> Bearing.RIGHT
  }
}

/*
 * Corridor trapezoid described in normalized coordinates (0..1).
 * For simplicity, vertical range [top..bottom], with wider base at bottom.
 */
data class Corridor(
  val top: Float = 0.25f,
  val bottom: Float = 0.95f,
  val topHalfWidth: Float = 0.15f,
  val bottomHalfWidth: Float = 0.35f,
)

/**
 * Return true if a box center lies inside the corridor trapezoid.
 */
fun isInsideCorridor(cxNorm: Float, cyNorm: Float, corridor: Corridor = Corridor()): Boolean {
  if (cyNorm < corridor.top || cyNorm > corridor.bottom) return false
  val t = (cyNorm - corridor.top) / max(1e-6f, (corridor.bottom - corridor.top))
  val halfWidth = corridor.topHalfWidth * (1 - t) + corridor.bottomHalfWidth * t
  return abs(cxNorm - 0.5f) <= halfWidth
}

/**
 * Map distance to severity bands for metric mode (ARCore).
 */
fun severityForMetric(distanceMeters: Float): ProximitySeverity {
  return when {
    distanceMeters <= 1.0f -> ProximitySeverity.URGENT
    distanceMeters <= 2.0f -> ProximitySeverity.WARNING
    distanceMeters <= 3.5f -> ProximitySeverity.INFO
    else -> ProximitySeverity.INFO
  }
}

/**
 * Map relative depth (0 near .. 1 far) to severity bands for monocular fallback.
 */
fun severityForRelativeDepth(relativeDepth: Float): ProximitySeverity {
  return when {
    relativeDepth <= 0.20f -> ProximitySeverity.URGENT
    relativeDepth <= 0.35f -> ProximitySeverity.WARNING
    relativeDepth <= 0.50f -> ProximitySeverity.INFO
    else -> ProximitySeverity.INFO
  }
}
