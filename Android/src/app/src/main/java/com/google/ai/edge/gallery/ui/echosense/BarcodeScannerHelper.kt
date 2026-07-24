package com.google.ai.edge.gallery.ui.echosense

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

data class ScannedVisualCode(
    val type: String,
    val value: String,
) {
    val displayDescription: String
        get() = "$type: $value"

    val spokenDescription: String
        get() {
            val trimmed = value.trim()
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            if (uri?.scheme?.lowercase() in setOf("http", "https")) {
                return "$type containing a web address for ${uri?.host ?: "an unknown site"}."
            }
            if (trimmed.startsWith("WIFI:", ignoreCase = true)) {
                val network = wifiField("S", trimmed)
                return network?.let { "$type containing Wi-Fi information for network $it." }
                    ?: "$type containing Wi-Fi network information."
            }
            if (trimmed.startsWith("mailto:", ignoreCase = true)) {
                return "$type containing an email address."
            }
            if (trimmed.startsWith("tel:", ignoreCase = true)) {
                return "$type containing a telephone number."
            }
            if (trimmed.length >= 8 && trimmed.all(Char::isDigit)) {
                return "$type, ${trimmed.toCharArray().joinToString(" ")}."
            }
            val safeValue = if (trimmed.length > 180) trimmed.take(180) + "…" else trimmed
            return "$type: $safeValue"
        }

    private fun wifiField(name: String, payload: String): String? =
        payload.split(';')
            .firstOrNull { it.startsWith("$name:") }
            ?.removePrefix("$name:")
            ?.takeIf(String::isNotBlank)
}

object BarcodeScannerHelper {
    private val scanner: BarcodeScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        BarcodeScanning.getClient(options)
    }

    fun scan(
        bitmap: Bitmap,
        onSuccess: (List<ScannedVisualCode>) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        scanner.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { barcodes ->
                val seen = HashSet<String>()
                val results = barcodes.mapNotNull { barcode ->
                    val value = barcode.rawValue?.trim().orEmpty()
                    if (value.isEmpty() || !seen.add(value)) return@mapNotNull null
                    ScannedVisualCode(formatName(barcode.format), value)
                }.take(3)
                onSuccess(results)
            }
            .addOnFailureListener(onFailure)
    }

    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE -> "QR code"
        Barcode.FORMAT_EAN_13 -> "EAN-13 barcode"
        Barcode.FORMAT_EAN_8 -> "EAN-8 barcode"
        Barcode.FORMAT_UPC_A -> "UPC-A barcode"
        Barcode.FORMAT_UPC_E -> "UPC-E barcode"
        Barcode.FORMAT_CODE_128 -> "Code 128 barcode"
        Barcode.FORMAT_CODE_39 -> "Code 39 barcode"
        Barcode.FORMAT_CODE_93 -> "Code 93 barcode"
        Barcode.FORMAT_PDF417 -> "PDF417 barcode"
        Barcode.FORMAT_AZTEC -> "Aztec code"
        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix code"
        Barcode.FORMAT_ITF -> "ITF barcode"
        else -> "Barcode"
    }
}
