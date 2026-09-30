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

package com.terranet.echosense.android.ui.echosense.currencymode

import android.app.Application
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.viewModelScope
import com.terranet.echosense.android.ui.echosense.EchoSenseBaseViewModel
import com.terranet.echosense.android.ui.echosense.OcrHelper
import com.terranet.echosense.android.ui.echosense.OcrScriptPreference
import com.terranet.echosense.android.ui.echosense.VisualUtilityAnalyzer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltViewModel
class CurrencyModeViewModel @Inject constructor(
    application: Application
) : EchoSenseBaseViewModel(application) {

    override val supportsCollisionAvoidance: Boolean = false
    override val streamsAnalysisToUser: Boolean = false

    private var pendingEvidence = CurrencyOcrEvidence("", emptySet(), emptySet(), 0f)

    override fun analyzeImage(imageProxy: ImageProxy) {
        if (!_isProcessing.value || _isAnalyzing.value) {
            imageProxy.close()
            return
        }
        val bitmap = try {
            val source = imageProxyToBitmap(imageProxy)
            val rotation = imageProxy.imageInfo.rotationDegrees
            imageProxy.close()
            source?.let { rotateBitmapIfNeeded(it, rotation) }
        } catch (error: Exception) {
            imageProxy.close()
            Log.e(TAG, "Unable to prepare currency image", error)
            null
        }
        if (bitmap == null) {
            reportOperationFailure("Unable to process the currency image. Please try again.")
            return
        }
        Log.d(TAG, "Currency still captured at ${bitmap.width}x${bitmap.height}")

        _isAnalyzing.value = true
        _activeModelName.value = "Latin OCR"
        viewModelScope.launch(Dispatchers.Default) {
            val qualityIssue = VisualUtilityAnalyzer.textCaptureIssue(bitmap)
            if (qualityIssue != null) {
                _isAnalyzing.value = false
                val message = qualityIssue.replace("page", "bank note")
                _objectDescription.value = message
                speakText(message)
                return@launch
            }

            try {
                val ocr = OcrHelper.recognize(
                    bitmap = bitmap,
                    preference = OcrScriptPreference.LATIN,
                    minimumTextLength = 1,
                )
                pendingEvidence = CurrencyEvidenceExtractor.extract(ocr)
                Log.d(
                    TAG,
                    "Currency OCR: chars=${pendingEvidence.rawText.length}, " +
                        "codes=${pendingEvidence.countryCodes}, " +
                        "denominations=${pendingEvidence.denominations}, " +
                        "confidence=${pendingEvidence.confidence}",
                )
                _isAnalyzing.value = false
                analyzeBitmap(bitmap, promptOverride = currencyFusionPrompt(pendingEvidence))
            } catch (error: Exception) {
                Log.e(TAG, "Currency OCR failed", error)
                _isAnalyzing.value = false
                pendingEvidence = CurrencyOcrEvidence("", emptySet(), emptySet(), 0f)
                analyzeBitmap(bitmap, promptOverride = currencyFusionPrompt(pendingEvidence))
            }
        }
    }

    override fun onAnalysisComplete(fullText: String) {
        val visual = CurrencyVisualAssessmentParser.parse(fullText)
        Log.d(
            TAG,
            "Currency vision: code=${visual?.code}, denomination=${visual?.denomination}, " +
                "usable=${visual?.imageUsable}, unsupported=${visual?.explicitlyUnsupported}",
        )
        val decision = CurrencyDecisionEngine.decide(pendingEvidence, visual)
        _objectDescription.value = decision.spokenText
        speakText(decision.spokenText)
    }

    override fun getAnalysisPrompt(customPrompt: String?, isVerbose: Boolean): String {
        return currencyFusionPrompt(pendingEvidence)
    }

    companion object {
        private const val TAG = "CurrencyModeVM"
    }
}
