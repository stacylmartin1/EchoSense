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
import com.terranet.echosense.android.ui.home.AppSettings
import com.terranet.echosense.android.ui.home.OnlineProvider
import com.terranet.echosense.android.ui.home.OnlineUsageMode

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
