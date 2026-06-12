package com.google.ai.edge.gallery.ui.echosense.documentreader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.ui.echosense.EchoSenseBaseViewModel
import com.google.ai.edge.gallery.ui.echosense.GeminiHelper
import com.google.ai.edge.gallery.ui.echosense.OcrHelper
import com.google.ai.edge.gallery.ui.echosense.TesseractOcrHelper
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
class DocumentReaderViewModel @Inject constructor(
    private val app: Application
) : EchoSenseBaseViewModel(app) {

    override val supportsCollisionAvoidance: Boolean = false
    override val supportsCamera: Boolean = true

    private val _documentText = MutableStateFlow("")
    val documentText: StateFlow<String> = _documentText

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage

    private val _totalPages = MutableStateFlow(0)
    val totalPages: StateFlow<Int> = _totalPages

    private val _documentMode = MutableStateFlow(DocumentMode.NONE)
    val documentMode: StateFlow<DocumentMode> = _documentMode

    private var pdfRenderer: PdfRenderer? = null
    private var pdfDescriptor: ParcelFileDescriptor? = null
    private var textPages: List<String> = emptyList()

    init {
        PDFBoxResourceLoader.init(app)
    }

    // ── Text / Markdown files ──────────────────────────────────────────
    // Reads the raw text and speaks it directly via TTS — no LLM needed.

    fun loadTextFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (text.isBlank()) {
                    _error.value = "File is empty"
                    return@launch
                }
                textPages = text.chunked(2000)
                _totalPages.value = textPages.size
                _currentPage.value = 0
                _documentText.value = textPages[0]
                _documentMode.value = DocumentMode.TEXT
                Log.d(TAG, "Loaded text file: ${textPages.size} pages")
                startProcessing()
                speakText(textPages[0])
            } catch (e: Exception) {
                Log.e(TAG, "Error reading text file", e)
                _error.value = "Error reading file: ${e.message}"
            }
        }
    }

    // ── PDF files ──────────────────────────────────────────────────────
    // PdfBox for text-based PDFs; ML Kit OCR for scanned/image PDFs.

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
                    _documentText.value = textPages[0]
                    _documentMode.value = DocumentMode.PDF_TEXT
                    startProcessing()
                    speakText(textPages[0])
                } else {
                    Log.d(TAG, "PDF appears image-based, using ML Kit OCR")
                    pdfDescriptor = app.contentResolver.openFileDescriptor(uri, "r")
                    pdfRenderer = PdfRenderer(pdfDescriptor!!)
                    _totalPages.value = pdfRenderer!!.pageCount
                    _currentPage.value = 0
                    _documentMode.value = DocumentMode.PDF_IMAGE
                    startProcessing()
                    ocrAndSpeakPdfPage(0)
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
    // Uses ML Kit OCR — no LLM needed.

    fun loadImageFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = app.contentResolver.openInputStream(uri)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    _documentMode.value = DocumentMode.IMAGE
                    _totalPages.value = 1
                    _currentPage.value = 0
                    startProcessing()
                    ocrAndSpeakBitmap(bitmap)
                } else {
                    _error.value = "Could not decode image"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image", e)
                _error.value = "Error loading image: ${e.message}"
            }
        }
    }

    // ── Camera capture (ML Kit OCR) ────────────────────────────────────

    /**
     * Analyze a camera-captured image using ML Kit OCR instead of LLM.
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
                _documentMode.value = DocumentMode.IMAGE
                _totalPages.value = 1
                _currentPage.value = 0
                ocrAndSpeakBitmap(bitmap)
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

    // ── ML Kit OCR ─────────────────────────────────────────────────────

    private fun ocrAndSpeakBitmap(bitmap: Bitmap) {
        _isAnalyzing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Step 1: ML Kit OCR
                _activeModelName.value = "ML Kit"
                val text = OcrHelper.recognizeText(bitmap)

                if (OcrHelper.isUsableResult(text)) {
                    _isAnalyzing.value = false
                    _documentText.value = text
                    _objectDescription.value = text
                    speakText(text)
                    return@launch
                }

                // ML Kit insufficient — try Tesseract for unsupported scripts
                Log.d(TAG, "ML Kit OCR insufficient (${text.length} chars)")

                var ocrText = text
                try {
                    Log.d(TAG, "Trying Tesseract OCR...")
                    _activeModelName.value = "Tesseract"
                    val tessText = TesseractOcrHelper.recognizeText(app, bitmap)
                    if (OcrHelper.isUsableResult(tessText)) {
                        _isAnalyzing.value = false
                        _documentText.value = tessText
                        _objectDescription.value = tessText
                        speakText(tessText)
                        return@launch
                    }
                    ocrText = tessText  // keep for logging even if not usable
                } catch (e: Exception) {
                    Log.w(TAG, "Tesseract OCR failed: ${e.message}")
                }

                // Tesseract insufficient — try Gemini if available
                Log.d(TAG, "Tesseract OCR insufficient (${ocrText.length} chars)")

                if (GeminiHelper.isAvailable()) {
                    try {
                        Log.d(TAG, "Trying Gemini OCR...")
                        _activeModelName.value = "Gemini 3 Flash"
                        val geminiText = GeminiHelper.recognizeText(bitmap)
                        if (geminiText.isNotBlank()) {
                            _isAnalyzing.value = false
                            _documentText.value = geminiText
                            _objectDescription.value = geminiText
                            speakText(geminiText)
                            return@launch
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Gemini OCR failed: ${e.message}")
                    }
                }

                // Last resort: on-device LLM OCR
                Log.d(TAG, "Falling back to LLM OCR")
                _activeModelName.value = "Gemma 3n"
                _isAnalyzing.value = false
                analyzeBitmap(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "OCR pipeline failed", e)
                _isAnalyzing.value = false
                analyzeBitmap(bitmap)
            }
        }
    }

    private fun ocrAndSpeakPdfPage(pageIndex: Int) {
        val renderer = pdfRenderer ?: return
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return

        val page = renderer.openPage(pageIndex)
        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()

        _currentPage.value = pageIndex
        ocrAndSpeakBitmap(bitmap)
    }

    // ── Page navigation ────────────────────────────────────────────────

    fun nextPage() {
        val cur = _currentPage.value
        when (_documentMode.value) {
            DocumentMode.TEXT, DocumentMode.PDF_TEXT -> {
                if (cur + 1 < textPages.size) {
                    _currentPage.value = cur + 1
                    _documentText.value = textPages[cur + 1]
                    ttsPlayer.stop()
                    speakText(textPages[cur + 1])
                }
            }
            DocumentMode.PDF_IMAGE -> {
                if (cur + 1 < _totalPages.value) {
                    stopProcessing()
                    startProcessing()
                    ocrAndSpeakPdfPage(cur + 1)
                }
            }
            else -> {}
        }
    }

    fun previousPage() {
        val cur = _currentPage.value
        when (_documentMode.value) {
            DocumentMode.TEXT, DocumentMode.PDF_TEXT -> {
                if (cur > 0) {
                    _currentPage.value = cur - 1
                    _documentText.value = textPages[cur - 1]
                    ttsPlayer.stop()
                    speakText(textPages[cur - 1])
                }
            }
            DocumentMode.PDF_IMAGE -> {
                if (cur > 0) {
                    stopProcessing()
                    startProcessing()
                    ocrAndSpeakPdfPage(cur - 1)
                }
            }
            else -> {}
        }
    }

    // ── TTS ────────────────────────────────────────────────────────────



    fun stopReading() {
        ttsPlayer.stop()
        stopProcessing()
    }

    // ── Cleanup ────────────────────────────────────────────────────────

    private fun closePdf() {
        try {
            pdfRenderer?.close()
            pdfDescriptor?.close()
        } catch (_: Exception) {}
        pdfRenderer = null
        pdfDescriptor = null
    }

    override fun onCleared() {
        closePdf()
        super.onCleared()
    }

    // getAnalysisPrompt is still required by the abstract base class but
    // is no longer called since we use ML Kit OCR instead of analyzeBitmap().
    override fun getAnalysisPrompt(customPrompt: String?, isVerbose: Boolean): String {
        return "Extract all text from this image exactly as written."
    }

    companion object {
        private const val TAG = "DocumentReaderVM"
    }
}

enum class DocumentMode {
    NONE, TEXT, PDF_TEXT, PDF_IMAGE, IMAGE
}
