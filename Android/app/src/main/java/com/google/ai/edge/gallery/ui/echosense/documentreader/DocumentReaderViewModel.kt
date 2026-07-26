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
import com.google.ai.edge.gallery.ui.echosense.OcrScriptPreference
import com.google.ai.edge.gallery.ui.echosense.TesseractOcrHelper
import com.google.ai.edge.gallery.ui.echosense.VisualUtilityAnalyzer
import com.google.ai.edge.gallery.ui.echosense.BarcodeScannerHelper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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

    private val _instantTextEnabled = MutableStateFlow(false)
    val instantTextEnabled: StateFlow<Boolean> = _instantTextEnabled

    private val _instantTextPaused = MutableStateFlow(false)
    val instantTextPaused: StateFlow<Boolean> = _instantTextPaused

    private val _instantTextStatus = MutableStateFlow("")
    val instantTextStatus: StateFlow<String> = _instantTextStatus

    private val _instantTextScript = MutableStateFlow(OcrScriptPreference.AUTOMATIC)
    val instantTextScript: StateFlow<OcrScriptPreference> = _instantTextScript

    private val _guidedDocumentEnabled = MutableStateFlow(false)
    val guidedDocumentEnabled: StateFlow<Boolean> = _guidedDocumentEnabled

    private val _guidedDocumentStatus = MutableStateFlow("")
    val guidedDocumentStatus: StateFlow<String> = _guidedDocumentStatus

    private var pdfRenderer: PdfRenderer? = null
    private var pdfDescriptor: ParcelFileDescriptor? = null
    private var textPages: List<String> = emptyList()
    private val instantTextInFlight = AtomicBoolean(false)
    private val instantSnapshotRequested = AtomicBoolean(false)
    private var lastInstantTextFrameMillis = 0L
    private var instantTextCandidate = ""
    private var instantTextCandidateCount = 0
    private var lastSpokenInstantText = ""
    private var lastInstantGuidanceMillis = 0L
    private var lastInstantFrameDiagnosticMillis = 0L
    private var instantEmptyResultCount = 0
    private var instantGuidanceCandidate = ""
    private var instantGuidanceCandidateCount = 0
    private var instantSnapshotFallbackText = ""
    private var instantSpeechActive = false
    private var instantTextCaptureCallback: (() -> Unit)? = null
    private val guidedDocumentInFlight = AtomicBoolean(false)
    private val guidedDocumentSnapshotRequested = AtomicBoolean(false)
    private var guidedDocumentCaptureCallback: (() -> Unit)? = null
    private var lastGuidedDocumentFrameMillis = 0L
    private var guidedDocumentCandidate: DocumentQuad? = null
    private var guidedDocumentStableCount = 0
    private var guidedDocumentGuidanceCandidate = ""
    private var guidedDocumentGuidanceCount = 0
    private var lastGuidedDocumentGuidanceMillis = 0L
    private var guidedDocumentSpeechActive = false

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

    fun toggleInstantText() {
        if (_instantTextEnabled.value) {
            stopInstantText()
            return
        }
        stopGuidedDocumentCapture(announce = false)
        _instantTextEnabled.value = true
        _instantTextPaused.value = false
        _instantTextStatus.value = "Scanning for text"
        instantTextCandidate = ""
        instantTextCandidateCount = 0
        lastSpokenInstantText = ""
        instantEmptyResultCount = 0
        lastInstantGuidanceMillis = 0L
        lastInstantFrameDiagnosticMillis = 0L
        instantGuidanceCandidate = ""
        instantGuidanceCandidateCount = 0
        instantSnapshotRequested.set(false)
        instantSpeechActive = false
        lastInstantTextFrameMillis = 0L
        announceAction("Instant text on. Point the camera at text.")
    }

    fun toggleInstantTextPause() {
        if (!_instantTextEnabled.value) return
        _instantTextPaused.value = !_instantTextPaused.value
        _instantTextStatus.value =
            if (_instantTextPaused.value) "Paused on recognized text" else "Scanning for text"
        announceAction(if (_instantTextPaused.value) "Instant text paused." else "Instant text resumed.")
    }

    fun setInstantTextScript(preference: OcrScriptPreference) {
        _instantTextScript.value = preference
        instantTextCandidate = ""
        instantTextCandidateCount = 0
        lastSpokenInstantText = ""
        announceAction("Instant Text script ${preference.displayName}.")
    }

    fun setInstantTextCaptureCallback(callback: () -> Unit) {
        instantTextCaptureCallback = callback
    }

    fun setGuidedDocumentCaptureCallback(callback: () -> Unit) {
        guidedDocumentCaptureCallback = callback
    }

    fun stopInstantText(announce: Boolean = true) {
        if (!_instantTextEnabled.value && !instantTextInFlight.get()) return
        _instantTextEnabled.value = false
        _instantTextPaused.value = false
        _instantTextStatus.value = ""
        instantTextCandidate = ""
        instantTextCandidateCount = 0
        instantEmptyResultCount = 0
        instantSnapshotRequested.set(false)
        instantSpeechActive = false
        if (announce) announceAction("Instant text off.")
    }

    fun toggleGuidedDocumentCapture() {
        if (_guidedDocumentEnabled.value) {
            stopGuidedDocumentCapture()
            return
        }
        if (_isAnalyzing.value) return
        stopInstantText(announce = false)
        _guidedDocumentEnabled.value = true
        _guidedDocumentStatus.value = "Looking for a complete page"
        guidedDocumentCandidate = null
        guidedDocumentStableCount = 0
        guidedDocumentGuidanceCandidate = ""
        guidedDocumentGuidanceCount = 0
        lastGuidedDocumentGuidanceMillis = 0L
        lastGuidedDocumentFrameMillis = 0L
        guidedDocumentSnapshotRequested.set(false)
        guidedDocumentSpeechActive = false
        announceAction("Guided scan on. Center one complete page in the camera view.")
    }

    fun captureGuidedDocumentManually() {
        requestGuidedDocumentSnapshot(manual = true)
    }

    fun stopGuidedDocumentCapture(announce: Boolean = true) {
        val wasActive =
            _guidedDocumentEnabled.value ||
                guidedDocumentInFlight.get() ||
                guidedDocumentSnapshotRequested.get()
        _guidedDocumentEnabled.value = false
        _guidedDocumentStatus.value = ""
        guidedDocumentCandidate = null
        guidedDocumentStableCount = 0
        guidedDocumentSnapshotRequested.set(false)
        guidedDocumentSpeechActive = false
        if (announce && wasActive) announceAction("Guided scan off.")
    }

    fun analyzeLiveCameraFrame(imageProxy: ImageProxy) {
        when {
            _guidedDocumentEnabled.value -> analyzeGuidedDocumentFrame(imageProxy)
            _instantTextEnabled.value -> analyzeInstantTextFrame(imageProxy)
            else -> imageProxy.close()
        }
    }

    private fun analyzeGuidedDocumentFrame(imageProxy: ImageProxy) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!_guidedDocumentEnabled.value ||
            guidedDocumentSnapshotRequested.get() ||
            guidedDocumentSpeechActive ||
            now - lastGuidedDocumentFrameMillis < 550 ||
            !guidedDocumentInFlight.compareAndSet(false, true)
        ) {
            imageProxy.close()
            return
        }
        lastGuidedDocumentFrameMillis = now
        val bitmap = try {
            val source = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            imageProxy.close()
            rotateBitmapIfNeeded(source, rotation)
        } catch (error: Exception) {
            imageProxy.close()
            guidedDocumentInFlight.set(false)
            Log.w(TAG, "Guided document frame conversion failed", error)
            null
        }
        if (bitmap == null) {
            guidedDocumentInFlight.set(false)
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val analysis = DocumentCaptureAnalyzer.analyze(bitmap)
                val qualityIssue = VisualUtilityAnalyzer.textCaptureIssue(bitmap)
                if (_guidedDocumentEnabled.value) {
                    applyGuidedDocumentAnalysis(analysis, qualityIssue)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Guided document edge analysis failed", error)
                _guidedDocumentStatus.value = "Looking for a complete page"
            } finally {
                bitmap.recycle()
                guidedDocumentInFlight.set(false)
            }
        }
    }

    private fun applyGuidedDocumentAnalysis(
        analysis: DocumentFrameAnalysis,
        qualityIssue: String?,
    ) {
        val guidance = qualityIssue ?: analysis.guidance
        _guidedDocumentStatus.value = guidance
        val quad = analysis.quad
        if (quad == null) {
            guidedDocumentCandidate = null
            guidedDocumentStableCount = 0
            announceGuidedDocumentGuidanceIfNeeded(guidance)
            return
        }

        val candidate = guidedDocumentCandidate
        if (candidate != null && quad.maximumCornerDistance(candidate) < 0.03f) {
            guidedDocumentStableCount++
        } else {
            guidedDocumentCandidate = quad
            guidedDocumentStableCount = 1
        }
        if (guidance != "Hold steady") {
            announceGuidedDocumentGuidanceIfNeeded(guidance)
            return
        }
        guidedDocumentGuidanceCandidate = ""
        guidedDocumentGuidanceCount = 0
        if (guidedDocumentStableCount >= 3) {
            requestGuidedDocumentSnapshot(manual = false)
        }
    }

    private fun announceGuidedDocumentGuidanceIfNeeded(guidance: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (guidance == guidedDocumentGuidanceCandidate) {
            guidedDocumentGuidanceCount++
        } else {
            guidedDocumentGuidanceCandidate = guidance
            guidedDocumentGuidanceCount = 1
        }
        if (guidedDocumentGuidanceCount < 2 ||
            now - lastGuidedDocumentGuidanceMillis < 6_000
        ) return
        lastGuidedDocumentGuidanceMillis = now
        guidedDocumentGuidanceCount = 0
        guidedDocumentSpeechActive = true
        speakText(guidance)
    }

    private fun requestGuidedDocumentSnapshot(manual: Boolean) {
        if (!_guidedDocumentEnabled.value ||
            !guidedDocumentSnapshotRequested.compareAndSet(false, true)
        ) return
        _guidedDocumentStatus.value =
            if (manual) "Capturing page manually" else "Page stable. Capturing"
        viewModelScope.launch(Dispatchers.Main) {
            val callback = guidedDocumentCaptureCallback
            if (callback == null || !_guidedDocumentEnabled.value) {
                guidedDocumentSnapshotRequested.set(false)
                _guidedDocumentStatus.value = "Camera is not ready"
                return@launch
            }
            callback()
        }
    }

    fun analyzeGuidedDocumentSnapshot(imageProxy: ImageProxy) {
        if (!_guidedDocumentEnabled.value || !guidedDocumentSnapshotRequested.get()) {
            imageProxy.close()
            guidedDocumentSnapshotRequested.set(false)
            return
        }
        val bitmap = try {
            val source = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            imageProxy.close()
            rotateBitmapIfNeeded(source, rotation)
        } catch (error: Exception) {
            imageProxy.close()
            Log.w(TAG, "Guided document still conversion failed", error)
            null
        }
        if (bitmap == null) {
            guidedDocumentSnapshotFailed()
            return
        }

        startProcessing()
        _isAnalyzing.value = true
        _guidedDocumentStatus.value = "Correcting page"
        val fallbackQuad = guidedDocumentCandidate
        viewModelScope.launch(Dispatchers.Default) {
            var corrected: Bitmap? = null
            try {
                corrected = DocumentCaptureAnalyzer.correctAndEnhance(bitmap, fallbackQuad)
                _guidedDocumentStatus.value = "Recognizing document text"
                _activeModelName.value = "ML Kit document OCR"
                val result = OcrHelper.recognize(
                    corrected,
                    preference = _instantTextScript.value,
                    minimumTextLength = 2,
                )
                val text = result.text.trim()
                _isAnalyzing.value = false
                guidedDocumentSnapshotRequested.set(false)
                if (text.isBlank()) {
                    _guidedDocumentStatus.value = "No text found. Adjust the page and try Capture"
                    guidedDocumentCandidate = null
                    guidedDocumentStableCount = 0
                    stopProcessing()
                    announceAction("No text found. Adjust the page and try Capture.")
                    return@launch
                }

                _documentMode.value = DocumentMode.IMAGE
                _totalPages.value = 1
                _currentPage.value = 0
                _documentText.value = text
                _objectDescription.value = text
                _guidedDocumentEnabled.value = false
                _guidedDocumentStatus.value = "Document captured"
                guidedDocumentCandidate = null
                guidedDocumentStableCount = 0
                speakText(text)
            } catch (error: Exception) {
                Log.e(TAG, "Guided document capture failed", error)
                _isAnalyzing.value = false
                guidedDocumentSnapshotRequested.set(false)
                _guidedDocumentStatus.value = "Unable to capture. Guided scan is still active"
                stopProcessing()
                announceAction("Unable to capture the document. Guided scan is still active.")
            } finally {
                if (corrected !== bitmap) corrected?.recycle()
                bitmap.recycle()
            }
        }
    }

    fun guidedDocumentSnapshotFailed() {
        guidedDocumentSnapshotRequested.set(false)
        guidedDocumentCandidate = null
        guidedDocumentStableCount = 0
        _guidedDocumentStatus.value = "Capture failed. Hold steady or use Capture again"
        announceAction("Document capture failed. Hold steady or use Capture again.")
    }

    fun analyzeInstantTextFrame(imageProxy: ImageProxy) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!_instantTextEnabled.value || _instantTextPaused.value ||
            instantSnapshotRequested.get() || instantSpeechActive ||
            now - lastInstantTextFrameMillis < 850 ||
            !instantTextInFlight.compareAndSet(false, true)
        ) {
            imageProxy.close()
            return
        }
        lastInstantTextFrameMillis = now

        val bitmap = try {
            // CameraX handles YUV rowStride/pixelStride differences here. The older
            // custom conversion assumes tightly packed NV21 and can produce a
            // decodable but unusable image on devices such as the Xiaomi 14T Pro.
            val source = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (now - lastInstantFrameDiagnosticMillis >= 5_000) {
                lastInstantFrameDiagnosticMillis = now
                Log.d(
                    TAG,
                    "Instant frame format=${imageProxy.format} source=${source.width}x${source.height} " +
                        "rotation=$rotation planes=${imageProxy.planes.joinToString { "${it.rowStride}/${it.pixelStride}" }}",
                )
            }
            imageProxy.close()
            rotateBitmapIfNeeded(source, rotation)
        } catch (error: Exception) {
            imageProxy.close()
            instantTextInFlight.set(false)
            Log.w(TAG, "Instant frame conversion failed", error)
            null
        }
        if (bitmap == null) {
            instantTextInFlight.set(false)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = OcrHelper.recognize(
                    bitmap,
                    preference = _instantTextScript.value,
                    minimumTextLength = 2,
                )
                Log.d(
                    TAG,
                    "Instant OCR chars=${result.text.length} lines=${result.lines.size} " +
                        "script=${result.script ?: "none"} confidence=${result.confidence} " +
                        "latencyMs=${result.processingTimeMillis}",
                )
                if (_instantTextEnabled.value && !_instantTextPaused.value) {
                    applyInstantTextResult(result, bitmap)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Instant text OCR failed", error)
                _instantTextStatus.value = "Waiting for a clear image"
            } finally {
                bitmap.recycle()
                instantTextInFlight.set(false)
            }
        }
    }

    private fun applyInstantTextResult(result: com.google.ai.edge.gallery.ui.echosense.StructuredOcrResult, bitmap: Bitmap) {
        val text = result.text.trim()
        if (text.length < 2) {
            instantEmptyResultCount++
            instantTextCandidate = ""
            instantTextCandidateCount = 0
            _instantTextStatus.value = "Looking for text"
            val imageIssue = VisualUtilityAnalyzer.textCaptureIssue(bitmap)
            if (imageIssue != null) {
                announceInstantCaptureIssueIfNeeded(imageIssue)
            } else if (instantEmptyResultCount >= 3) {
                announceInstantCaptureIssueIfNeeded(
                    "No text detected. Center printed text in the view and hold the phone steady.",
                )
            }
            return
        }

        instantEmptyResultCount = 0
        instantGuidanceCandidate = ""
        instantGuidanceCandidateCount = 0
        val normalized = normalizeInstantText(text)
        if (textSimilarity(normalized, instantTextCandidate) >= 0.78) {
            instantTextCandidateCount++
        } else {
            instantTextCandidate = normalized
            instantTextCandidateCount = 1
        }
        val scriptSuffix = result.script?.let { ", $it" }.orEmpty()
        _instantTextStatus.value =
            if (instantTextCandidateCount >= 2) "Text recognized$scriptSuffix" else "Hold steady"
        if (instantTextCandidateCount < 2 ||
            textSimilarity(normalized, normalizeInstantText(lastSpokenInstantText)) >= 0.88
        ) return

        requestInstantTextSnapshot(text)
    }

    private fun requestInstantTextSnapshot(fallbackText: String) {
        if (!instantSnapshotRequested.compareAndSet(false, true)) return
        instantSnapshotFallbackText = fallbackText
        _instantTextStatus.value = "Hold steady. Capturing text"
        viewModelScope.launch(Dispatchers.Main) {
            val callback = instantTextCaptureCallback
            if (callback == null || !_instantTextEnabled.value) {
                instantSnapshotRequested.set(false)
                return@launch
            }
            callback()
        }
    }

    fun analyzeInstantTextSnapshot(imageProxy: ImageProxy) {
        if (!_instantTextEnabled.value || !instantSnapshotRequested.get()) {
            imageProxy.close()
            instantSnapshotRequested.set(false)
            return
        }
        val bitmap = try {
            val source = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            imageProxy.close()
            rotateBitmapIfNeeded(source, rotation)
        } catch (error: Exception) {
            imageProxy.close()
            Log.w(TAG, "Instant still capture conversion failed", error)
            null
        }
        if (bitmap == null) {
            speakCapturedInstantText(instantSnapshotFallbackText)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val capturedText = try {
                OcrHelper.recognize(
                    bitmap,
                    preference = _instantTextScript.value,
                    minimumTextLength = 2,
                ).text.trim()
            } catch (error: Exception) {
                Log.w(TAG, "Instant still OCR failed", error)
                ""
            } finally {
                bitmap.recycle()
            }
            speakCapturedInstantText(
                if (capturedText.length >= 2) capturedText else instantSnapshotFallbackText,
            )
        }
    }

    fun instantTextSnapshotCaptureFailed() {
        if (!instantSnapshotRequested.get()) return
        Log.w(TAG, "Instant still capture failed; using stable live OCR result")
        speakCapturedInstantText(instantSnapshotFallbackText)
    }

    private fun speakCapturedInstantText(text: String) {
        val normalized = normalizeInstantText(text)
        if (textSimilarity(normalized, normalizeInstantText(lastSpokenInstantText)) >= 0.88) {
            instantSnapshotRequested.set(false)
            _instantTextStatus.value = "Scanning for new text"
            return
        }
        _documentMode.value = DocumentMode.IMAGE
        _documentText.value = text
        _objectDescription.value = text
        lastSpokenInstantText = text
        instantSpeechActive = true
        _instantTextStatus.value = "Reading captured text"
        speakText(text)
    }

    private fun announceInstantCaptureIssueIfNeeded(issue: String?) {
        if (issue == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (issue == instantGuidanceCandidate) {
            instantGuidanceCandidateCount++
        } else {
            instantGuidanceCandidate = issue
            instantGuidanceCandidateCount = 1
        }
        if (instantGuidanceCandidateCount < 3 || now - lastInstantGuidanceMillis < 12_000) return
        lastInstantGuidanceMillis = now
        instantGuidanceCandidateCount = 0
        instantSpeechActive = true
        _instantTextStatus.value = issue
        speakText(issue)
    }

    override fun onSpeechOutputComplete() {
        if (instantSpeechActive) {
            instantSpeechActive = false
            instantSnapshotRequested.set(false)
            instantTextCandidate = ""
            instantTextCandidateCount = 0
            viewModelScope.launch {
                kotlinx.coroutines.delay(900)
                if (_instantTextEnabled.value) {
                    _instantTextStatus.value =
                        if (_instantTextPaused.value) "Paused on recognized text" else "Scanning for new text"
                }
            }
        }
        if (guidedDocumentSpeechActive) {
            guidedDocumentSpeechActive = false
            viewModelScope.launch {
                kotlinx.coroutines.delay(600)
                if (_guidedDocumentEnabled.value) {
                    _guidedDocumentStatus.value = "Looking for a complete page"
                }
            }
        }
    }

    private fun normalizeInstantText(text: String): String =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun textSimilarity(leftText: String, rightText: String): Double {
        if (leftText.isEmpty() || rightText.isEmpty()) return 0.0
        if (leftText == rightText) return 1.0
        val left = leftText.split(" ").filter { it.isNotEmpty() }.toSet()
        val right = rightText.split(" ").filter { it.isNotEmpty() }.toSet()
        val union = left union right
        return if (union.isEmpty()) 0.0 else (left intersect right).size.toDouble() / union.size
    }

    fun identifyCenterColor(imageProxy: ImageProxy) {
        analyzeCameraUtility(imageProxy) { bitmap ->
            VisualUtilityAnalyzer.centerColor(bitmap)?.spokenDescription
        }
    }

    fun measureLightLevel(imageProxy: ImageProxy) {
        analyzeCameraUtility(imageProxy) { bitmap ->
            VisualUtilityAnalyzer.lightLevel(bitmap)?.spokenDescription
        }
    }

    fun captureMagnifierFrame(imageProxy: ImageProxy, onCaptured: (Bitmap?) -> Unit) {
        try {
            val source = imageProxyToBitmap(imageProxy)
            val rotation = imageProxy.imageInfo.rotationDegrees
            imageProxy.close()
            onCaptured(source?.let { rotateBitmapIfNeeded(it, rotation) })
        } catch (error: Exception) {
            imageProxy.close()
            Log.e(TAG, "Magnifier capture failed", error)
            onCaptured(null)
        }
    }

    fun scanBarcodeOrQrCode(imageProxy: ImageProxy) {
        try {
            val source = imageProxyToBitmap(imageProxy)
            val rotation = imageProxy.imageInfo.rotationDegrees
            imageProxy.close()
            val bitmap = source?.let { rotateBitmapIfNeeded(it, rotation) }
            if (bitmap == null) {
                announceAction("Camera frame is not ready.")
                return
            }

            _isAnalyzing.value = true
            _activeModelName.value = "ML Kit barcode scanner"
            val failureGuidance = VisualUtilityAnalyzer.codeCaptureGuidance(bitmap)
            BarcodeScannerHelper.scan(
                bitmap = bitmap,
                onSuccess = { codes ->
                    _isAnalyzing.value = false
                    bitmap.recycle()
                    if (codes.isEmpty()) {
                        val message = failureGuidance
                        _documentText.value = message
                        _objectDescription.value = message
                        speakText(message)
                        return@scan
                    }
                    _documentMode.value = DocumentMode.IMAGE
                    _totalPages.value = 1
                    _currentPage.value = 0
                    _documentText.value = codes.joinToString("\n\n") { it.displayDescription }
                    _objectDescription.value = _documentText.value
                    speakText(codes.joinToString(" ") { it.spokenDescription })
                },
                onFailure = { error ->
                    _isAnalyzing.value = false
                    bitmap.recycle()
                    Log.e(TAG, "Barcode scan failed", error)
                    _error.value = "Unable to scan the code"
                    announceAction("Unable to scan the code.")
                },
            )
        } catch (error: Exception) {
            imageProxy.close()
            _isAnalyzing.value = false
            Log.e(TAG, "Barcode capture failed", error)
            _error.value = "Unable to scan the code"
            announceAction("Unable to scan the code.")
        }
    }

    private fun analyzeCameraUtility(
        imageProxy: ImageProxy,
        analyzer: (Bitmap) -> String?,
    ) {
        try {
            val source = imageProxyToBitmap(imageProxy)
            val rotation = imageProxy.imageInfo.rotationDegrees
            imageProxy.close()
            val bitmap = source?.let { rotateBitmapIfNeeded(it, rotation) }
            val description = bitmap?.let(analyzer)
            if (description == null) {
                _error.value = "Camera frame is not ready"
                announceAction("Camera frame is not ready.")
                return
            }
            _activeModelName.value = "On-device camera"
            _documentMode.value = DocumentMode.IMAGE
            _totalPages.value = 1
            _currentPage.value = 0
            _documentText.value = description
            _objectDescription.value = description
            speakText(description)
        } catch (error: Exception) {
            imageProxy.close()
            Log.e(TAG, "Camera utility analysis failed", error)
            _error.value = "Could not analyze the camera image"
            announceAction("Could not analyze the camera image.")
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
        stopInstantText(announce = false)
        stopGuidedDocumentCapture(announce = false)
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
        stopInstantText(announce = false)
        stopGuidedDocumentCapture(announce = false)
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
