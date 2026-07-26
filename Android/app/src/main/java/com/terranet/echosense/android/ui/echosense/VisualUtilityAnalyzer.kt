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

package com.terranet.echosense.android.ui.echosense

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class CameraColorResult(
    val name: String,
    val brightnessPercent: Int,
) {
    val spokenDescription: String
        get() = "$name. Approximate brightness $brightnessPercent percent."
}

data class CameraLightResult(
    val level: String,
    val brightnessPercent: Int,
) {
    val spokenDescription: String
        get() = "$level light. Approximate brightness $brightnessPercent percent."
}

/**
 * Lightweight, fully on-device color and relative-light analysis.
 *
 * Results describe the camera image rather than calibrated color or lux. Sampling a region
 * instead of one pixel makes the result less sensitive to sensor noise and autofocus movement.
 */
object VisualUtilityAnalyzer {
    private const val CODE_GUIDANCE =
        "No barcode or QR code found. Keep the entire code visible, try a different distance, and avoid glare."
    fun centerColor(bitmap: Bitmap): CameraColorResult? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val sampleWidth = max(1, (bitmap.width * 0.06f).roundToInt())
        val sampleHeight = max(1, (bitmap.height * 0.06f).roundToInt())
        val left = (bitmap.width - sampleWidth) / 2
        val top = (bitmap.height - sampleHeight) / 2
        val samples = sampleRgb(bitmap, left, top, sampleWidth, sampleHeight)
        if (samples.isEmpty()) return null
        val names = samples.map { rgb ->
            val hsv = FloatArray(3)
            Color.RGBToHSV(
                (rgb.red * 255).roundToInt(),
                (rgb.green * 255).roundToInt(),
                (rgb.blue * 255).roundToInt(),
                hsv,
            )
            colorName(hsv[0], hsv[1], hsv[2])
        }
        val dominantName = names.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: "Unknown color"
        val luminances = samples.map(::relativeLuminance).sorted()
        return CameraColorResult(
            name = dominantName,
            brightnessPercent = (luminances[luminances.size / 2] * 100).roundToInt(),
        )
    }

    fun lightLevel(bitmap: Bitmap): CameraLightResult? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val rgb = averageRgb(bitmap, 0, 0, bitmap.width, bitmap.height)
        val brightness = relativeLuminance(rgb)
        val level = when {
            brightness < 0.08 -> "Very dark"
            brightness < 0.22 -> "Dim"
            brightness < 0.50 -> "Moderate"
            brightness < 0.78 -> "Bright"
            else -> "Very bright"
        }
        return CameraLightResult(
            level = level,
            brightnessPercent = (brightness * 100).roundToInt(),
        )
    }

    fun codeCaptureGuidance(bitmap: Bitmap): String {
        return when (textCaptureIssue(bitmap)) {
            null -> CODE_GUIDANCE
            "Possible glare detected. Tilt the page away from the light or shade it." ->
                "Possible glare detected. Tilt the code away from the light or shade it."
            else -> "The code appears out of focus. Move the phone farther away and hold it steady."
        }
    }

    fun textCaptureIssue(bitmap: Bitmap): String? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val side = 48
        val samples = sampleGrid(bitmap, side)
        val luminances = samples.map(::relativeLuminance)
        if (luminances.size != side * side) return null
        val median = luminances.sorted()[luminances.size / 2]

        var maximumBlockMean = 0.0
        val blockSide = 8
        for (blockY in 0 until 6) {
            for (blockX in 0 until 6) {
                var total = 0.0
                for (y in 0 until blockSide) {
                    for (x in 0 until blockSide) {
                        total += luminances[
                            (blockY * blockSide + y) * side + blockX * blockSide + x
                        ]
                    }
                }
                maximumBlockMean = max(maximumBlockMean, total / (blockSide * blockSide))
            }
        }
        if (maximumBlockMean > 0.94 && maximumBlockMean - median > 0.14) {
            return "Possible glare detected. Tilt the page away from the light or shade it."
        }

        var edgeTotal = 0.0
        var edgeCount = 0
        for (y in 0 until side) {
            for (x in 0 until side) {
                val value = luminances[y * side + x]
                if (x + 1 < side) {
                    edgeTotal += kotlin.math.abs(value - luminances[y * side + x + 1])
                    edgeCount++
                }
                if (y + 1 < side) {
                    edgeTotal += kotlin.math.abs(value - luminances[(y + 1) * side + x])
                    edgeCount++
                }
            }
        }
        if (edgeTotal / max(1, edgeCount) < 0.028) {
            return "Text appears out of focus. Move the phone farther away and hold it steady."
        }
        return null
    }

    private fun averageRgb(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): Rgb {
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var count = 0
        val stepX = max(1, width / 48)
        val stepY = max(1, height / 48)
        var y = top
        while (y < min(bitmap.height, top + height)) {
            var x = left
            while (x < min(bitmap.width, left + width)) {
                val pixel = bitmap.getPixel(x, y)
                red += Color.red(pixel) / 255.0
                green += Color.green(pixel) / 255.0
                blue += Color.blue(pixel) / 255.0
                count += 1
                x += stepX
            }
            y += stepY
        }
        val divisor = max(1, count).toDouble()
        return Rgb(red / divisor, green / divisor, blue / divisor)
    }

    private fun sampleRgb(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): List<Rgb> {
        val samples = ArrayList<Rgb>(441)
        val stepX = max(1, width / 21)
        val stepY = max(1, height / 21)
        var y = top
        while (y < min(bitmap.height, top + height)) {
            var x = left
            while (x < min(bitmap.width, left + width)) {
                val pixel = bitmap.getPixel(x, y)
                samples += Rgb(
                    Color.red(pixel) / 255.0,
                    Color.green(pixel) / 255.0,
                    Color.blue(pixel) / 255.0,
                )
                x += stepX
            }
            y += stepY
        }
        return samples
    }

    private fun sampleGrid(bitmap: Bitmap, side: Int): List<Rgb> {
        val samples = ArrayList<Rgb>(side * side)
        for (row in 0 until side) {
            val y = min(bitmap.height - 1, ((row + 0.5) * bitmap.height / side).toInt())
            for (column in 0 until side) {
                val x = min(bitmap.width - 1, ((column + 0.5) * bitmap.width / side).toInt())
                val pixel = bitmap.getPixel(x, y)
                samples += Rgb(
                    Color.red(pixel) / 255.0,
                    Color.green(pixel) / 255.0,
                    Color.blue(pixel) / 255.0,
                )
            }
        }
        return samples
    }

    private fun relativeLuminance(rgb: Rgb): Double {
        fun linearize(component: Double): Double =
            if (component <= 0.04045) component / 12.92
            else ((component + 0.055) / 1.055).pow(2.4)

        return (
            0.2126 * linearize(rgb.red) +
                0.7152 * linearize(rgb.green) +
                0.0722 * linearize(rgb.blue)
            ).coerceIn(0.0, 1.0)
    }

    private fun colorName(hue: Float, saturation: Float, value: Float): String {
        if (value < 0.10f) return "Black"
        if (saturation < 0.10f) {
            return when {
                value > 0.80f -> "White"
                value < 0.32f -> "Dark gray"
                value > 0.72f -> "Light gray"
                else -> "Gray"
            }
        }
        if (value < 0.38f && hue >= 15f && hue < 55f) return "Brown"
        if (saturation < 0.28f) return if (value > 0.82f) "Off-white" else "Gray"
        return when {
            hue < 15f || hue >= 345f -> "Red"
            hue < 42f -> if (value < 0.62f) "Brown" else "Orange"
            hue < 70f -> "Yellow"
            hue < 155f -> "Green"
            hue < 190f -> "Teal"
            hue < 255f -> "Blue"
            hue < 290f -> "Purple"
            else -> "Pink"
        }
    }

    private data class Rgb(val red: Double, val green: Double, val blue: Double)
}
