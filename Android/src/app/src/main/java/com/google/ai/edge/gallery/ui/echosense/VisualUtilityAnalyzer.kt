package com.google.ai.edge.gallery.ui.echosense

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
