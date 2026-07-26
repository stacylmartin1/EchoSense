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

package com.terranet.echosense.android.ui.echosense.documenttranslator

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.viewModelScope
import com.terranet.echosense.android.ui.echosense.EchoSenseBaseViewModel
import com.terranet.echosense.android.ui.echosense.GeminiHelper
import com.terranet.echosense.android.ui.echosense.OcrHelper
import com.terranet.echosense.android.ui.echosense.TesseractOcrHelper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class DocumentTranslatorViewModel @Inject constructor(
    private val app: Application
) : EchoSenseBaseViewModel(app) {

    override val supportsCollisionAvoidance: Boolean = false
    override val supportsCamera: Boolean = true

    private val _translatedText = MutableStateFlow("")
    val translatedText: StateFlow<String> = _translatedText

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage

    private val _totalPages = MutableStateFlow(0)
    val totalPages: StateFlow<Int> = _totalPages

    private val _documentMode = MutableStateFlow(TranslatorMode.NONE)
    val documentMode: StateFlow<TranslatorMode> = _documentMode

    private val _forceLlmOcr = MutableStateFlow(false)
    val forceLlmOcr: StateFlow<Boolean> = _forceLlmOcr

    private var pdfRenderer: PdfRenderer? = null
    private var pdfDescriptor: ParcelFileDescriptor? = null
    private var textPages: List<String> = emptyList()

    init {
        PDFBoxResourceLoader.init(app)
        TranslationHelper.refreshDownloadedLanguages()
    }

    // ── Text / Markdown files ──────────────────────────────────────────
    // Direct text → ML Kit detect + translate → TTS

    fun loadTextFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (text.isBlank()) {
                    _error.value = "File is empty"
                    return@launch
                }
                textPages = text.chunked(1500)
                _totalPages.value = textPages.size
                _currentPage.value = 0
                _documentMode.value = TranslatorMode.TEXT
                translateAndSpeak(textPages[0])
            } catch (e: Exception) {
                Log.e(TAG, "Error reading text file", e)
                _error.value = "Error reading file: ${e.message}"
            }
        }
    }

    // ── PDF files ──────────────────────────────────────────────────────
    // Text PDFs: PdfBox → ML Kit translate → TTS
    // Scanned PDFs: ML Kit OCR → ML Kit translate → TTS

    fun loadPdfFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                closePdf()

                val extractedPages = extractTextWithPdfBox(uri)

                if (extractedPages != null) {
                    Log.d(TAG, "PDF has extractable text (${extractedPages.size} pages)")
                    textPages = extractedPages
                    _totalPages.value = textPages.size
                    _currentPage.value = 0
                    _documentMode.value = TranslatorMode.PDF_TEXT
                    translateAndSpeak(textPages[0])
                } else {
                    Log.d(TAG, "PDF appears image-based, using ML Kit OCR + translate")
                    pdfDescriptor = app.contentResolver.openFileDescriptor(uri, "r")
                    pdfRenderer = PdfRenderer(pdfDescriptor!!)
                    _totalPages.value = pdfRenderer!!.pageCount
                    _currentPage.value = 0
                    _documentMode.value = TranslatorMode.PDF_IMAGE
                    startProcessing()
                    ocrTranslateAndSpeakPdfPage(0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error opening PDF", e)
                _error.value = "Error opening PDF: ${e.message}"
            }
        }
    }

    private fun extractTextWithPdfBox(uri: Uri): List<String>? {
        var document: PDDocument? = null
        try {
            val inputStream = app.contentResolver.openInputStream(uri) ?: return null
            document = PDDocument.load(inputStream)
            inputStream.close()

            val pageCount = document.numberOfPages
            if (pageCount == 0) return null

            val stripper = PDFTextStripper()
            val pages = mutableListOf<String>()
            var totalTextLength = 0

            for (i in 1..pageCount) {
                stripper.startPage = i
                stripper.endPage = i
                val pageText = stripper.getText(document)
                    .replace(Regex("(?<!\\n)\\n(?!\\n)"), " ")
                    .replace(Regex("\\n{2,}"), "\n\n")
                    .trim()
                pages.add(pageText)
                totalTextLength += pageText.length
            }

            document.close()
            return if (totalTextLength > 50) pages else null
        } catch (e: Exception) {
            Log.e(TAG, "PdfBox text extraction failed", e)
            document?.close()
            return null
        }
    }

    // ── Image files ────────────────────────────────────────────────────
    // ML Kit OCR → ML Kit translate → TTS

    fun loadImageFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = app.contentResolver.openInputStream(uri)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    _documentMode.value = TranslatorMode.IMAGE
                    _totalPages.value = 1
                    _currentPage.value = 0
                    startProcessing()
                    ocrTranslateAndSpeakBitmap(bitmap)
                } else {
                    _error.value = "Could not decode image"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image", e)
                _error.value = "Error loading image: ${e.message}"
            }
        }
    }

    // ── Camera capture (ML Kit OCR + translate) ────────────────────────

    /**
     * Analyze a camera-captured image using ML Kit OCR + translate instead of LLM.
     * Call this from the screen instead of the base class analyzeImage().
     */
    fun analyzeImageWithOcr(imageProxy: ImageProxy) {
        if (!_isProcessing.value) {
            imageProxy.close()
            return
        }
        try {
            val srcBitmap = imageProxyToBitmap(imageProxy)
            if (srcBitmap != null) {
                val rotation = try { imageProxy.imageInfo.rotationDegrees } catch (_: Exception) { 0 }
                val bitmap = rotateBitmapIfNeeded(srcBitmap, rotation)
                imageProxy.close()
                _documentMode.value = TranslatorMode.IMAGE
                _totalPages.value = 1
                _currentPage.value = 0
                ocrTranslateAndSpeakBitmap(bitmap)
            } else {
                imageProxy.close()
                _error.value = "Could not process camera image"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing camera image", e)
            imageProxy.close()
            _error.value = "Error: ${e.message}"
        }
    }

    // ── ML Kit OCR + Translation pipeline ──────────────────────────────

    fun setForceLlmOcr(enabled: Boolean) {
        _forceLlmOcr.value = enabled
    }

    /**
     * ML Kit OCR on a bitmap, then ML Kit translate, then speak.
     * Falls back to Gemini (online) then Gemma 3n (LLM) if ML Kit
     * doesn't extract enough text (e.g., Thai, Arabic scripts).
     */
    private fun ocrTranslateAndSpeakBitmap(bitmap: Bitmap) {
        _isAnalyzing.value = true
        _translatedText.value = ""
        _objectDescription.value = ""

        viewModelScope.launch(Dispatchers.IO) {
            try {
                var ocrText = ""
                val forceLlm = _forceLlmOcr.value
                
                if (!forceLlm) {
                    // Step 1: ML Kit OCR
                    _activeModelName.value = "ML Kit"
                    ocrText = OcrHelper.recognizeText(bitmap)
                    Log.d(TAG, "ML Kit OCR extracted ${ocrText.length} chars")
                }

                // Step 1b: Tesseract fallback for unsupported scripts (Thai, Khmer, etc.)
                if (!forceLlm && !OcrHelper.isUsableResult(ocrText)) {
                    try {
                        Log.d(TAG, "ML Kit insufficient (${ocrText.length} chars), trying Tesseract...")
                        _activeModelName.value = "Tesseract"
                        val tessText = TesseractOcrHelper.recognizeText(app, bitmap)
                        if (OcrHelper.isUsableResult(tessText)) {
                            ocrText = tessText
                            Log.d(TAG, "Tesseract OCR extracted ${ocrText.length} chars")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Tesseract OCR failed: ${e.message}")
                    }
                }

                // Step 1c: Gemini fallback if ML Kit + Tesseract insufficient or forced
                if ((forceLlm || !OcrHelper.isUsableResult(ocrText)) && GeminiHelper.isAvailable()) {
                    try {
                        if (forceLlm) {
                            Log.d(TAG, "Forcing Gemini OCR...")
                        } else {
                            Log.d(TAG, "ML Kit insufficient, trying Gemini OCR...")
                        }
                        _activeModelName.value = "Gemini 3 Flash"
                        val geminiText = GeminiHelper.recognizeText(bitmap)
                        if (geminiText.isNotBlank()) {
                            ocrText = geminiText
                            Log.d(TAG, "Gemini OCR extracted ${ocrText.length} chars")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Gemini OCR failed: ${e.message}")
                    }
                }

                // Step 1c: LLM fallback if still insufficient
                if (!OcrHelper.isUsableResult(ocrText)) {
                    Log.d(TAG, "Falling back to LLM OCR")
                    _activeModelName.value = "Gemma 3n"
                    _isAnalyzing.value = false
                    analyzeBitmap(bitmap) // result goes to onAnalysisComplete() → translate
                    return@launch
                }

                _objectDescription.value = ocrText

                // Step 2: ML Kit translate
                val result = TranslationHelper.detectAndTranslate(ocrText)
                _isAnalyzing.value = false

                val displayLang = java.util.Locale(result.detectedLanguage).displayLanguage
                Log.d(TAG, "Detected: $displayLang, translated: ${result.wasTranslated}")

                _translatedText.value = result.translatedText
                _objectDescription.value = result.translatedText

                // Step 3: Speak
                speakText(result.translatedText)
            } catch (e: IllegalStateException) {
                _isAnalyzing.value = false
                Log.e(TAG, "Translation failed: ${e.message}")
                _error.value = e.message
                ttsPlayer.announceStatus(e.message ?: "Language pack not downloaded.")
            } catch (e: Exception) {
                _isAnalyzing.value = false
                Log.e(TAG, "OCR/translate error", e)
                _error.value = "Error: ${e.message}"
                stopProcessing()
            }
        }
    }

    private fun ocrTranslateAndSpeakPdfPage(pageIndex: Int) {
        val renderer = pdfRenderer ?: return
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return

        val page = renderer.openPage(pageIndex)
        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()

        _currentPage.value = pageIndex
        ocrTranslateAndSpeakBitmap(bitmap)
    }

    // ── ML Kit Translation for text ────────────────────────────────────

    /**
     * Detect language → translate via ML Kit → speak result.
     * Used for text files and text-based PDFs.
     */
    private fun translateAndSpeak(text: String) {
        startProcessing()
        _isAnalyzing.value = true
        _activeModelName.value = "ML Kit"
        _translatedText.value = ""
        _objectDescription.value = ""

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = TranslationHelper.detectAndTranslate(text)

                val displayLang = java.util.Locale(result.detectedLanguage).displayLanguage
                Log.d(TAG, "Detected: ${result.detectedLanguage} ($displayLang), translated: ${result.wasTranslated}")

                _translatedText.value = result.translatedText
                _objectDescription.value = result.translatedText
                _isAnalyzing.value = false

                speakText(result.translatedText)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Translation failed: ${e.message}")
                _isAnalyzing.value = false
                _error.value = e.message
                ttsPlayer.announceStatus(e.message ?: "Translation failed. Language pack not downloaded.")
            } catch (e: Exception) {
                Log.e(TAG, "Translation error", e)
                _isAnalyzing.value = false
                _error.value = "Translation error: ${e.message}"
            }
        }
    }

    // ── Page navigation ────────────────────────────────────────────────

    fun nextPage() {
        val cur = _currentPage.value
        when (_documentMode.value) {
            TranslatorMode.TEXT, TranslatorMode.PDF_TEXT -> {
                if (cur + 1 < textPages.size) {
                    stopTranslating()
                    _currentPage.value = cur + 1
                    translateAndSpeak(textPages[cur + 1])
                }
            }
            TranslatorMode.PDF_IMAGE -> {
                if (cur + 1 < _totalPages.value) {
                    stopProcessing()
                    startProcessing()
                    ocrTranslateAndSpeakPdfPage(cur + 1)
                }
            }
            else -> {}
        }
    }

    fun previousPage() {
        val cur = _currentPage.value
        when (_documentMode.value) {
            TranslatorMode.TEXT, TranslatorMode.PDF_TEXT -> {
                if (cur > 0) {
                    stopTranslating()
                    _currentPage.value = cur - 1
                    translateAndSpeak(textPages[cur - 1])
                }
            }
            TranslatorMode.PDF_IMAGE -> {
                if (cur > 0) {
                    stopProcessing()
                    startProcessing()
                    ocrTranslateAndSpeakPdfPage(cur - 1)
                }
            }
            else -> {}
        }
    }

    // ── TTS ────────────────────────────────────────────────────────────



    fun stopTranslating() {
        ttsPlayer.stop()
        stopProcessing()
    }

    // ── Cleanup ────────────────────────────────────────────────────────

    private fun closePdf() {
        try { pdfRenderer?.close(); pdfDescriptor?.close() } catch (_: Exception) {}
        pdfRenderer = null
        pdfDescriptor = null
    }

    override fun onCleared() {
        closePdf()
        super.onCleared()
    }

    // Used when LLM fallback OCR is triggered for unsupported scripts
    override fun getAnalysisPrompt(customPrompt: String?, isVerbose: Boolean): String {
        return "Extract all text from this image exactly as written. Preserve the original language. Do not translate. Do not add any commentary."
    }

    // When LLM OCR finishes (fallback for unsupported scripts), translate the result
    override fun onAnalysisComplete(fullText: String) {
        if (fullText.isNotBlank()) {
            translateOcrResult(fullText)
        }
    }

    /**
     * Translate text extracted by the LLM OCR fallback, then speak.
     */
    private fun translateOcrResult(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = TranslationHelper.detectAndTranslate(text)
                val displayLang = java.util.Locale(result.detectedLanguage).displayLanguage
                Log.d(TAG, "LLM OCR fallback translated ($displayLang): ${result.wasTranslated}")

                _translatedText.value = result.translatedText
                _objectDescription.value = result.translatedText

                // Re-speak with translated text (overwrites LLM's raw output)
                ttsPlayer.stop()
                speakText(result.translatedText)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Translation of LLM OCR result failed: ${e.message}")
                _error.value = e.message
                ttsPlayer.announceStatus(e.message ?: "Language pack not downloaded.")
            } catch (e: Exception) {
                Log.e(TAG, "Translation error on LLM OCR result", e)
                _error.value = "Translation error: ${e.message}"
            }
        }
    }

    companion object {
        private const val TAG = "DocumentTranslatorVM"
    }
}

enum class TranslatorMode {
    NONE, TEXT, PDF_TEXT, PDF_IMAGE, IMAGE
}
