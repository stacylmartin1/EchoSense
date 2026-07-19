package com.google.ai.edge.gallery.ui.echosense

import android.graphics.Bitmap
import com.google.ai.edge.gallery.ui.home.AppSettings
import com.google.ai.edge.gallery.ui.home.OnlineProvider
import com.google.ai.edge.gallery.ui.home.OnlineUsageMode

/** Compatibility wrapper for features that specifically require Gemini OCR. */
object GeminiHelper {
  fun isAvailable(): Boolean =
    AppSettings.isOnlineConnected() &&
      AppSettings.onlineProvider.value == OnlineProvider.GEMINI &&
      AppSettings.onlineConsentGranted.value &&
      AppSettings.onlineUsageMode.value != OnlineUsageMode.ASK

  suspend fun recognizeText(bitmap: Bitmap): String =
    OnlineAnalysisHelper.analyzeImage(
      bitmap,
      "Extract all text from this image exactly as written. Preserve its language and formatting. Return only the extracted text.",
    )

  suspend fun analyzeImage(bitmap: Bitmap, prompt: String): String =
    OnlineAnalysisHelper.analyzeImage(bitmap, prompt)
}
