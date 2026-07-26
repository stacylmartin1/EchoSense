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

package com.terranet.echosense.android.ui.echosense.documentreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class NormalizedDocumentPoint(val x: Float, val y: Float)

data class DocumentQuad(
    val topLeft: NormalizedDocumentPoint,
    val topRight: NormalizedDocumentPoint,
    val bottomRight: NormalizedDocumentPoint,
    val bottomLeft: NormalizedDocumentPoint,
) {
    fun maximumCornerDistance(other: DocumentQuad): Float =
        listOf(
            topLeft to other.topLeft,
            topRight to other.topRight,
            bottomRight to other.bottomRight,
            bottomLeft to other.bottomLeft,
        ).maxOf { (left, right) -> hypot(left.x - right.x, left.y - right.y) }

    val area: Float
        get() {
            val points = listOf(topLeft, topRight, bottomRight, bottomLeft)
            return abs(
                points.indices.sumOf { index ->
                    val current = points[index]
                    val next = points[(index + 1) % points.size]
                    (current.x * next.y - next.x * current.y).toDouble()
                }.toFloat(),
            ) / 2f
        }
}

data class DocumentFrameAnalysis(
    val quad: DocumentQuad?,
    val guidance: String,
)

/**
 * Small offline page detector for live guidance. It looks for four persistent,
 * near-linear luminance boundaries rather than trying to recognize text. The
 * high-resolution still is checked again before perspective correction.
 */
object DocumentCaptureAnalyzer {
    private const val ANALYSIS_MAX_DIMENSION = 320
    private const val MIN_EDGE_STRENGTH = 17

    fun analyze(bitmap: Bitmap): DocumentFrameAnalysis {
        val scale = min(
            1f,
            ANALYSIS_MAX_DIMENSION.toFloat() / max(bitmap.width, bitmap.height).coerceAtLeast(1),
        )
        val working =
            if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    max(1, (bitmap.width * scale).toInt()),
                    max(1, (bitmap.height * scale).toInt()),
                    true,
                )
            } else {
                bitmap
            }

        return try {
            detectInScaledBitmap(working)
        } finally {
            if (working !== bitmap) working.recycle()
        }
    }

    fun correctAndEnhance(bitmap: Bitmap, fallbackQuad: DocumentQuad? = null): Bitmap {
        val detected = analyze(bitmap).quad ?: fallbackQuad
        if (detected == null) return enhanceForReading(bitmap.copy(Bitmap.Config.ARGB_8888, true))

        val source = floatArrayOf(
            detected.topLeft.x * bitmap.width,
            detected.topLeft.y * bitmap.height,
            detected.topRight.x * bitmap.width,
            detected.topRight.y * bitmap.height,
            detected.bottomRight.x * bitmap.width,
            detected.bottomRight.y * bitmap.height,
            detected.bottomLeft.x * bitmap.width,
            detected.bottomLeft.y * bitmap.height,
        )
        val topWidth = distance(source[0], source[1], source[2], source[3])
        val bottomWidth = distance(source[6], source[7], source[4], source[5])
        val leftHeight = distance(source[0], source[1], source[6], source[7])
        val rightHeight = distance(source[2], source[3], source[4], source[5])
        var outputWidth = ((topWidth + bottomWidth) / 2f).toInt().coerceAtLeast(1)
        var outputHeight = ((leftHeight + rightHeight) / 2f).toInt().coerceAtLeast(1)
        val outputScale = min(1f, 2400f / max(outputWidth, outputHeight))
        outputWidth = max(1, (outputWidth * outputScale).toInt())
        outputHeight = max(1, (outputHeight * outputScale).toInt())

        val destination = floatArrayOf(
            0f, 0f,
            outputWidth.toFloat(), 0f,
            outputWidth.toFloat(), outputHeight.toFloat(),
            0f, outputHeight.toFloat(),
        )
        val transform = Matrix()
        if (!transform.setPolyToPoly(source, 0, destination, 0, 4)) {
            return enhanceForReading(bitmap.copy(Bitmap.Config.ARGB_8888, true))
        }
        val corrected = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        Canvas(corrected).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, transform, null)
        }
        return enhanceForReading(corrected)
    }

    private fun detectInScaledBitmap(bitmap: Bitmap): DocumentFrameAnalysis {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 40 || height < 40) {
            return DocumentFrameAnalysis(null, "Camera image is not ready")
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val luminance = IntArray(pixels.size) { index ->
            val color = pixels[index]
            (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
        }

        val horizontalSamples = samplePositions(height, 0.14f, 0.86f, 9)
        val verticalSamples = samplePositions(width, 0.14f, 0.86f, 9)
        val left = fitXBoundary(
            horizontalSamples.mapNotNull { y ->
                strongestVerticalEdge(luminance, width, height, y, 0.025f, 0.46f)
                    ?.let { (x, strength) -> if (strength >= MIN_EDGE_STRENGTH) x.toFloat() to y.toFloat() else null }
            },
            width,
        )
        val right = fitXBoundary(
            horizontalSamples.mapNotNull { y ->
                strongestVerticalEdge(luminance, width, height, y, 0.54f, 0.975f)
                    ?.let { (x, strength) -> if (strength >= MIN_EDGE_STRENGTH) x.toFloat() to y.toFloat() else null }
            },
            width,
        )
        val top = fitYBoundary(
            verticalSamples.mapNotNull { x ->
                strongestHorizontalEdge(luminance, width, height, x, 0.025f, 0.46f)
                    ?.let { (y, strength) -> if (strength >= MIN_EDGE_STRENGTH) x.toFloat() to y.toFloat() else null }
            },
            height,
        )
        val bottom = fitYBoundary(
            verticalSamples.mapNotNull { x ->
                strongestHorizontalEdge(luminance, width, height, x, 0.54f, 0.975f)
                    ?.let { (y, strength) -> if (strength >= MIN_EDGE_STRENGTH) x.toFloat() to y.toFloat() else null }
            },
            height,
        )

        if (left == null) return DocumentFrameAnalysis(null, "Left page edge missing. Move the camera left")
        if (right == null) return DocumentFrameAnalysis(null, "Right page edge missing. Move the camera right")
        if (top == null) return DocumentFrameAnalysis(null, "Top page edge missing. Move the camera up")
        if (bottom == null) return DocumentFrameAnalysis(null, "Bottom page edge missing. Move the camera down")

        val topLeft = intersect(left, top)
        val topRight = intersect(right, top)
        val bottomRight = intersect(right, bottom)
        val bottomLeft = intersect(left, bottom)
        val rawPoints = listOf(topLeft, topRight, bottomRight, bottomLeft)
        if (rawPoints.any { it == null }) {
            return DocumentFrameAnalysis(null, "Center one complete page in the view")
        }
        val points = rawPoints.filterNotNull()
        if (points.any { it.first !in -3f..(width + 3f) || it.second !in -3f..(height + 3f) }) {
            return DocumentFrameAnalysis(null, "Move farther away so every page edge is visible")
        }
        val quad = DocumentQuad(
            topLeft = points[0].normalized(width, height),
            topRight = points[1].normalized(width, height),
            bottomRight = points[2].normalized(width, height),
            bottomLeft = points[3].normalized(width, height),
        )
        if (quad.area < 0.12f) {
            return DocumentFrameAnalysis(quad, "Move closer so the page fills more of the view")
        }
        if (quad.area < 0.28f) {
            return DocumentFrameAnalysis(quad, "Move closer so the page fills more of the view")
        }
        if (quad.area > 0.88f) {
            return DocumentFrameAnalysis(quad, "Move farther away so every page edge is visible")
        }
        return DocumentFrameAnalysis(quad, "Hold steady")
    }

    /** x = slope * y + intercept */
    private data class XLine(val slope: Float, val intercept: Float)

    /** y = slope * x + intercept */
    private data class YLine(val slope: Float, val intercept: Float)

    private fun fitXBoundary(points: List<Pair<Float, Float>>, width: Int): XLine? {
        if (points.size < 6) return null
        val fit = linearFit(points.map { it.second to it.first }) ?: return null
        val residual = points.map { (x, y) -> abs(x - (fit.first * y + fit.second)) }.average()
        if (residual > width * 0.065) return null
        return XLine(fit.first, fit.second)
    }

    private fun fitYBoundary(points: List<Pair<Float, Float>>, height: Int): YLine? {
        if (points.size < 6) return null
        val fit = linearFit(points) ?: return null
        val residual = points.map { (x, y) -> abs(y - (fit.first * x + fit.second)) }.average()
        if (residual > height * 0.065) return null
        return YLine(fit.first, fit.second)
    }

    private fun linearFit(points: List<Pair<Float, Float>>): Pair<Float, Float>? {
        if (points.size < 2) return null
        val meanX = points.map { it.first }.average().toFloat()
        val meanY = points.map { it.second }.average().toFloat()
        val denominator = points.sumOf { ((it.first - meanX) * (it.first - meanX)).toDouble() }.toFloat()
        if (denominator < 0.001f) return 0f to meanY
        val numerator = points.sumOf { ((it.first - meanX) * (it.second - meanY)).toDouble() }.toFloat()
        val slope = numerator / denominator
        return slope to (meanY - slope * meanX)
    }

    private fun intersect(xLine: XLine, yLine: YLine): Pair<Float, Float>? {
        val denominator = 1f - xLine.slope * yLine.slope
        if (abs(denominator) < 0.01f) return null
        val x = (xLine.slope * yLine.intercept + xLine.intercept) / denominator
        return x to (yLine.slope * x + yLine.intercept)
    }

    private fun strongestVerticalEdge(
        luminance: IntArray,
        width: Int,
        height: Int,
        y: Int,
        startFraction: Float,
        endFraction: Float,
    ): Pair<Int, Int>? {
        if (y !in 2 until height - 2) return null
        var bestX = -1
        var bestStrength = 0
        val start = max(3, (width * startFraction).toInt())
        val end = min(width - 4, (width * endFraction).toInt())
        for (x in start..end) {
            var strength = 0
            for (offsetY in -2..2) {
                strength += abs(
                    luminance[(y + offsetY) * width + x + 2] -
                        luminance[(y + offsetY) * width + x - 2],
                )
            }
            strength /= 5
            if (strength > bestStrength) {
                bestStrength = strength
                bestX = x
            }
        }
        return if (bestX >= 0) bestX to bestStrength else null
    }

    private fun strongestHorizontalEdge(
        luminance: IntArray,
        width: Int,
        height: Int,
        x: Int,
        startFraction: Float,
        endFraction: Float,
    ): Pair<Int, Int>? {
        if (x !in 2 until width - 2) return null
        var bestY = -1
        var bestStrength = 0
        val start = max(3, (height * startFraction).toInt())
        val end = min(height - 4, (height * endFraction).toInt())
        for (y in start..end) {
            var strength = 0
            for (offsetX in -2..2) {
                strength += abs(
                    luminance[(y + 2) * width + x + offsetX] -
                        luminance[(y - 2) * width + x + offsetX],
                )
            }
            strength /= 5
            if (strength > bestStrength) {
                bestStrength = strength
                bestY = y
            }
        }
        return if (bestY >= 0) bestY to bestStrength else null
    }

    private fun samplePositions(size: Int, start: Float, end: Float, count: Int): List<Int> =
        (0 until count).map { index ->
            val fraction = start + (end - start) * index / (count - 1).coerceAtLeast(1)
            (size * fraction).toInt().coerceIn(2, size - 3)
        }

    private fun Pair<Float, Float>.normalized(width: Int, height: Int) =
        NormalizedDocumentPoint(
            (first / width).coerceIn(0f, 1f),
            (second / height).coerceIn(0f, 1f),
        )

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        hypot(x2 - x1, y2 - y1)

    private fun enhanceForReading(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (index in pixels.indices) {
            val color = pixels[index]
            val gray =
                (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
            val enhanced = (((gray - 128) * 1.22f) + 136).toInt().coerceIn(0, 255)
            pixels[index] = Color.rgb(enhanced, enhanced, enhanced)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
