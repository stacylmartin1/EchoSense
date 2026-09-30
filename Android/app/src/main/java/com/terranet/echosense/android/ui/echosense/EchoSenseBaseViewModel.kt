/*
 * Copyright 2025 TerraNet Technologies LLC
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

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.terranet.echosense.android.data.Model
import com.terranet.echosense.android.ui.home.AppSettings
import com.terranet.echosense.android.ui.home.LlmResponseStyle
import com.terranet.echosense.android.ui.home.OnlineUsageMode
import com.terranet.echosense.android.ui.llmchat.LlmChatModelHelper
import com.terranet.echosense.android.ui.llmchat.LlmModelInstance
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Abstract base ViewModel that encapsulates the core camera -> bitmap -> LLM inference ->
 * streaming TTS pipeline shared by all EchoSense-derived ViewModels.
 *
 * Subclasses must implement [getAnalysisPrompt] to provide the specific prompt used for
 * LLM image analysis. They may also override [supportsCollisionAvoidance] and [supportsCamera]
 * to declare their capabilities.
 */
abstract class EchoSenseBaseViewModel(
    protected val application: Application
) : ViewModel() {

    // ---- Core state ----

    protected var currentModel: Model? = null
    protected var llmTextBuffer = StringBuilder()

    protected val sentenceChunker = StreamingSentenceChunker()
    private val currentTtsVoiceName = MutableStateFlow("")
    private val promptOnlineAfterSpeech = AtomicBoolean(false)

    protected val ttsPlayer = NativeTtsQueuePlayer(application, onAllComplete = {
        viewModelScope.launch {
            onSpeechOutputComplete()
            if (promptOnlineAfterSpeech.getAndSet(false)) {
                AppSettings.recordSuccessfulLocalAnalysis(application)
            }
            if (_isProcessing.value) {
                Log.d(TAG, "All TTS utterances complete, stopping processing")
                stopProcessing()
            }
        }
    })

    init {
        viewModelScope.launch {
            AppSettings.observeTtsVoiceName().collect { voiceName ->
                currentTtsVoiceName.value = voiceName
                ttsPlayer.updateVoice(voiceName)
            }
        }
    }

    /** The active coroutine running LLM inference so it can be cancelled. */
    protected var inferenceJob: Job? = null

    /** Generation counter used to ignore stale inference callbacks after stop/restart. */
    protected var processingGeneration = 0L

    // ---- State flows ----

    protected val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    protected val _objectDescription = MutableStateFlow("")
    val objectDescription: StateFlow<String> = _objectDescription

    protected val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    protected val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing

    private val _areSafetyAlertsSuppressed = MutableStateFlow(false)
    val areSafetyAlertsSuppressed: StateFlow<Boolean> = _areSafetyAlertsSuppressed

    protected val _activeModelName = MutableStateFlow<String?>(null)
    val activeModelName: StateFlow<String?> = _activeModelName

    protected val _voiceCommand = MutableStateFlow<String?>(null)
    val voiceCommand: StateFlow<String?> = _voiceCommand
    private val _pendingVoiceCommand = MutableStateFlow<String?>(null)
    val pendingVoiceCommand: StateFlow<String?> = _pendingVoiceCommand

    /** Store custom prompt for use in analysis. */
    protected var customPrompt: String? = null
    private var forceOnlineNext = false
    private var forceLocalNext = false

    // ---- Voice command infrastructure ----

    private val voiceCommandListener = object : VoiceCommandHelper.VoiceCommandListener {
        override fun onVoiceCommand(command: String) {
            viewModelScope.launch {
                _voiceCommand.value = command
                Log.d(TAG, "Voice command received: '$command'")

                when (command.lowercase(Locale.getDefault())) {
                    "start" -> {
                        startProcessing()
                        return@launch
                    }
                    "stop" -> {
                        stopProcessing()
                        return@launch
                    }
                }

                Log.d(TAG, "Treating voice command as custom prompt: '$command'")
                _pendingVoiceCommand.value = command
            }
        }

        override fun onError(error: Int) {
            viewModelScope.launch {
                reportOperationFailure("Voice command was not recognized. Please try again.")
            }
        }
    }

    protected val voiceCommandHelper = VoiceCommandHelper(application, voiceCommandListener)

    // ---- Image capture callback ----

    private var onCaptureImageCallback: (() -> Unit)? = null

    // ---- Status change announcement tracking ----

    private val modelStatusAnnouncements = ModelStatusAnnouncementTracker()
    protected var analyzingAnnounced = false

    // ---- Abstract / open members ----

    /**
     * Build the analysis prompt for the LLM given the current custom prompt and verbosity.
     *
     * @param customPrompt An optional user-supplied prompt (from voice command). Null means
     *   use the default scene-description prompt.
     * @param isVerbose True when [AppSettings.llmResponseStyle] is [LlmResponseStyle.VERBOSE].
     * @return The full prompt string to send to the LLM alongside the captured image.
     */
    abstract fun getAnalysisPrompt(customPrompt: String?, isVerbose: Boolean): String

    /** Called after speech queued through [speakText] has fully completed. */
    protected open fun onSpeechOutputComplete() {}

    /**
     * Called when LLM analysis (OCR) completes with the full extracted text.
     * Subclasses can override to post-process the result (e.g., translate it).
     * Default implementation does nothing — TTS is already handled by the
     * base stream.
     */
    protected open fun onAnalysisComplete(fullText: String) {}

    /** Whether this ViewModel supports collision avoidance (proximity detection). */
    open val supportsCollisionAvoidance: Boolean = false

    /** Whether this ViewModel uses the camera. */
    open val supportsCamera: Boolean = true

    /**
     * Whether raw model tokens may be shown and spoken as they arrive. Features such as
     * currency identification disable this so a structured, unvalidated model response is
     * never announced before deterministic post-processing.
     */
    protected open val streamsAnalysisToUser: Boolean = true

    // ---- Public API ----

    fun setModel(model: Model) {
        currentModel = model
        Log.d(
            TAG,
            "Model set to ${model.name}; object=${System.identityHashCode(model)} ready=${model.instance != null}",
        )
    }

    fun setImageCaptureCallback(callback: () -> Unit) {
        onCaptureImageCallback = callback
    }

    fun startProcessing(announcement: String? = null) {
        Log.d(TAG, "Starting single-shot processing")

        cancelInferenceJob()

        sentenceChunker.clear()
        suspendSafetyAlertsForAnalysis()
        ttsPlayer.stop()
        promptOnlineAfterSpeech.set(false)

        _objectDescription.value = ""
        llmTextBuffer.clear()

        analyzingAnnounced = false

        _isProcessing.value = true

        if (!announcement.isNullOrBlank()) {
            analyzingAnnounced = true
            ttsPlayer.announceStatus(announcement)
        }

        val generation = System.nanoTime()
        processingGeneration = generation

        // Note: Conversation reset is handled in analyzeImage() to avoid race conditions
        Log.d(TAG, "Single-shot processing started with clean state")
    }

    fun startOnlineProcessing(announcement: String? = null) {
        forceOnlineNext = true
        forceLocalNext = false
        startProcessing(announcement)
    }

    fun startOnDeviceProcessing(announcement: String? = null) {
        forceLocalNext = true
        forceOnlineNext = false
        startProcessing(announcement)
    }

    fun stopProcessing() {
        Log.d(TAG, "Stopping processing")
        // Invalidate current generation to ignore incoming callbacks
        processingGeneration = 0L

        // 1. Cancel the job that launched inference (this stops resultListener callbacks)
        cancelInferenceJob()

        // 2. Cancel the pending native async inference via cancelProcess().
        // Unlike close(), this does NOT destroy the conversation object, avoiding
        // a SIGSEGV crash when the native engine is still writing to the conversation.
        // The conversation will be reset in analyzeImage() before the next inference.
        currentModel?.let { model ->
            try {
                val instance = model.instance as? LlmModelInstance
                if (instance != null) {
                    Log.d(TAG, "Cancelling active inference via cancelProcess()")
                    instance.conversation.cancelProcess()
                    Log.d(TAG, "Inference cancelled successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling inference during stop", e)
            }
        }

        _isProcessing.value = false
        _isAnalyzing.value = false

        sentenceChunker.clear()
        ttsPlayer.stop()
        promptOnlineAfterSpeech.set(false)
        forceOnlineNext = false
        forceLocalNext = false
        _pendingVoiceCommand.value = null
        resumeSafetyAlertsAfterAnalysis()

        _objectDescription.value = ""
        llmTextBuffer.clear()

        analyzingAnnounced = false

        Log.d(TAG, "Processing stopped and state cleared")
    }

    fun startListening() {
        voiceCommandHelper.startListening()
    }

    fun stopSpeaking() {
        Log.d(TAG, "UI requested to stop speaking, delegating to stopProcessing()")
        stopProcessing()
        ttsPlayer.announceStatus("Stopped.")
    }

    /** Speak a brief action announcement (for actions that do not start model processing). */
    fun announceAction(message: String) {
        if (_isProcessing.value) analyzingAnnounced = true
        ttsPlayer.announceStatus(message)
    }

    /** Stop the active operation and make its failure audible as well as visible. */
    fun reportOperationFailure(message: String) {
        _error.value = message
        stopProcessing()
        ttsPlayer.announceStatus(message)
    }

    fun updateVoiceCommand(command: String?) {
        _voiceCommand.value = command
    }

    fun analyzePendingVoiceCommand(useOnline: Boolean) {
        val command = _pendingVoiceCommand.value ?: return
        _pendingVoiceCommand.value = null
        customPrompt = command
        if (useOnline) {
            startOnlineProcessing("Analyzing request")
        } else {
            startOnDeviceProcessing("Analyzing request")
        }
        onCaptureImageCallback?.invoke() ?: run {
            Log.w(TAG, "Image capture callback not set, cannot capture image for voice command")
            reportOperationFailure("Unable to capture an image for the voice request.")
        }
    }

    fun cancelPendingVoiceCommand() {
        _pendingVoiceCommand.value = null
    }

    fun checkAndAnnounceStatusChanges(
        modelName: String,
        isModelInstalled: Boolean,
        isModelDownloadInProgress: Boolean,
        isModelReady: Boolean,
        isAnalyzing: Boolean,
    ) {
        Log.d(
            TAG,
            "checkAndAnnounceStatusChanges: model=$modelName installed=$isModelInstalled " +
                "downloading=$isModelDownloadInProgress ready=$isModelReady analyzing=$isAnalyzing",
        )

        if (!isAnalyzing && !_isProcessing.value) {
            when (
                modelStatusAnnouncements.nextAnnouncement(
                    currentModelName = modelName,
                    isModelInstalled = isModelInstalled,
                    isModelDownloadInProgress = isModelDownloadInProgress,
                    isModelReady = isModelReady,
                )
            ) {
                ModelStatusAnnouncement.READY -> ttsPlayer.announceStatus(READY_ANNOUNCEMENT)
                ModelStatusAnnouncement.LOADING -> ttsPlayer.announceStatus(MODEL_LOADING_ANNOUNCEMENT)
                ModelStatusAnnouncement.DOWNLOADING -> ttsPlayer.announceStatus(MODEL_DOWNLOADING_ANNOUNCEMENT)
                ModelStatusAnnouncement.MISSING -> ttsPlayer.announceStatus(MISSING_MODEL_ANNOUNCEMENT)
                null -> Unit
            }
        }

        if (isAnalyzing && !analyzingAnnounced && _isProcessing.value) {
            Log.d(TAG, "Analysis started, announcing immediately")
            analyzingAnnounced = true
            ttsPlayer.announceStatus("Analyzing")
        }

    }

    /**
     * Re-evaluate startup status after Android runtime permission dialogs close.
     * Per-state guards ensure this never repeats an announcement already submitted to TTS.
     */
    fun retryStartupStatusAnnouncementAfterPermission(
        modelName: String,
        isModelInstalled: Boolean,
        isModelDownloadInProgress: Boolean,
        isModelReady: Boolean,
        isAnalyzing: Boolean,
    ) {
        checkAndAnnounceStatusChanges(
            modelName = modelName,
            isModelInstalled = isModelInstalled,
            isModelDownloadInProgress = isModelDownloadInProgress,
            isModelReady = isModelReady,
            isAnalyzing = isAnalyzing,
        )
    }

    fun onConfigChanged(oldConfigValues: Map<String, Any>, newConfigValues: Map<String, Any>) {
        Log.d(TAG, "Configuration changed from $oldConfigValues to $newConfigValues")
    }

    /**
     * Analyze an image from a CameraX [ImageProxy]. Converts the proxy to a [Bitmap],
     * closes the proxy immediately to release the camera buffer, and delegates to
     * [analyzeBitmap].
     */
    open fun analyzeImage(imageProxy: ImageProxy) {
        Log.d(TAG, "analyzeImage() called - isProcessing: ${_isProcessing.value}, isAnalyzing: ${_isAnalyzing.value}")

        if (!_isProcessing.value) {
            Log.d(TAG, "Processing not active, closing ImageProxy")
            imageProxy.close()
            return
        }

        if (_isAnalyzing.value) {
            Log.d(TAG, "Already analyzing, closing ImageProxy")
            imageProxy.close()
            return
        }

        val model = currentModel
        if (model == null) {
            imageProxy.close()
            reportOperationFailure("The model is not ready. Please wait and try again.")
            return
        }

        // Convert ImageProxy to Bitmap (ARGB_8888) and rotate to display orientation
        val bitmap: Bitmap
        try {
            val srcBitmap = imageProxyToBitmap(imageProxy)
            if (srcBitmap != null) {
                val rotation = try { imageProxy.imageInfo.rotationDegrees } catch (e: Exception) { 0 }
                val rotated = rotateBitmapIfNeeded(srcBitmap, rotation)
                bitmap = rotated
                Log.d(TAG, "Bitmap created: src=${srcBitmap.width}x${srcBitmap.height}, rotation=${rotation}, final=${bitmap.width}x${bitmap.height}")
                // Close camera buffer immediately after extracting bitmap to avoid gralloc unlock* warnings
                try {
                    imageProxy.close()
                    Log.d(TAG, "ImageProxy closed immediately after bitmap extraction")
                } catch (e: Exception) {
                    Log.d(TAG, "ImageProxy already closed or error closing: ${e.message}")
                }
            } else {
                Log.e(TAG, "Failed to convert ImageProxy to bitmap")
                imageProxy.close()
                reportOperationFailure("Unable to process the camera image. Please try again.")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Bitmap from ImageProxy", e)
            imageProxy.close()
            reportOperationFailure("Unable to process the camera image. Please try again.")
            return
        }

        val forceOnline = forceOnlineNext
        val forceLocal = forceLocalNext
        forceOnlineNext = false
        forceLocalNext = false
        analyzeBitmap(bitmap, forceOnline = forceOnline, forceLocal = forceLocal)
    }

    fun analyzeCapturedBitmap(bitmap: Bitmap) {
        val forceOnline = forceOnlineNext
        val forceLocal = forceLocalNext
        forceOnlineNext = false
        forceLocalNext = false
        analyzeBitmap(bitmap, forceOnline = forceOnline, forceLocal = forceLocal)
    }

    /**
     * Analyze a pre-converted [Bitmap] using the LLM. Resets the conversation, sends the
     * prompt + image to the model, and streams results through the sentence chunker to TTS.
     *
     * This method can be called directly when a bitmap is already available (e.g., from
     * proximity detection or a saved image), or indirectly via [analyzeImage].
     */
    fun analyzeBitmap(
        bitmap: Bitmap,
        forceOnline: Boolean = false,
        forceLocal: Boolean = false,
        promptOverride: String? = null,
    ) {
        val model = currentModel
        if (model == null) {
            reportOperationFailure("The model is not ready. Please wait and try again.")
            return
        }

        // Downscale if needed to avoid OOM / native crashes in the LLM engine
        val scaledBitmap = scaleBitmapToMaxSize(bitmap, LLM_MAX_IMAGE_SIZE)
        val sensorSnapshot = buildSensorPromptSnapshot()
        _analysisSensorContext.value = sensorContextSummary(sensorSnapshot)

        val canUseOnline =
            OnlineAnalysisHelper.isAvailable() && AppSettings.onlineConsentGranted.value
        if (
            canUseOnline &&
                !forceLocal &&
                (forceOnline || AppSettings.onlineUsageMode.value == OnlineUsageMode.PREFER_ONLINE)
        ) {
            analyzeBitmapOnline(scaledBitmap, sensorSnapshot = sensorSnapshot)
            return
        }

        if (!_isProcessing.value) {
            Log.d(TAG, "Processing not active, skipping bitmap analysis")
            return
        }

        if (_isAnalyzing.value) {
            Log.d(TAG, "Already analyzing, skipping bitmap analysis")
            return
        }

        inferenceJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                if (!_isProcessing.value) {
                    Log.d(TAG, "Processing stopped during setup, aborting analysis")
                    return@launch
                }

                // Double check if analyzing to avoid race condition where stopProcessing happened but job started
                if (_isAnalyzing.value) {
                    Log.w(TAG, "Analysis flag still set from previous run, resetting")
                    _isAnalyzing.value = false
                }

                // Start a new generation
                val generation = System.nanoTime()
                processingGeneration = generation

                synchronized(model) {
                    if (model.instance == null) {
                        Log.w(TAG, "Model instance is null, cannot analyze image")
                        if (
                            OnlineAnalysisHelper.isAvailable() &&
                            AppSettings.onlineConsentGranted.value &&
                            AppSettings.onlineUsageMode.value == OnlineUsageMode.FALLBACK
                        ) {
                            Log.d(TAG, "Local model unavailable; using configured online fallback")
                            announceAction("On-device analysis is unavailable. Trying online analysis.")
                            analyzeBitmapOnline(scaledBitmap, sensorSnapshot = sensorSnapshot)
                        } else {
                            reportOperationFailure("The model is still loading. Wait for ready and try again.")
                        }
                        return@launch
                    }

                    // Reset conversation HERE before starting, to clear previous state safely on background thread
                    try {
                        Log.d(TAG, "Resetting conversation before analysis")
                        LlmChatModelHelper.resetConversation(
                            model = model,
                            supportImage = true,
                            supportAudio = false
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error resetting conversation", e)
                    }
                }

                _isAnalyzing.value = true
                _activeModelName.value = model.name

                llmTextBuffer.clear()
                sentenceChunker.clear()
                Log.d(TAG, "Starting new LLM analysis (Gen: $generation) with model '${model.name}', cleared text buffer")

                val responseStyle = AppSettings.llmResponseStyle.value
                val isVerbose = responseStyle == LlmResponseStyle.VERBOSE

                val prompt = promptOverride ?: appendSensorSnapshot(
                    getAnalysisPrompt(customPrompt, isVerbose),
                    sensorSnapshot,
                )
                // Clear customPrompt after use so subsequent analyses use the default prompt
                customPrompt = null

                Log.d(TAG, "Using prompt: '$prompt'")

                // Pass bitmap directly via images parameter (LiteRT)
                LlmChatModelHelper.runInference(
                    model = model,
                    input = prompt,
                    images = listOf(scaledBitmap),
                    resultListener = { partialResult, done ->
                        viewModelScope.launch {
                            if (!_isProcessing.value || processingGeneration != generation) {
                                Log.d(TAG, "Processing stopped or generation mismatch, ignoring LLM result")
                                return@launch
                            }

                            if (partialResult.isNotEmpty()) {
                                llmTextBuffer.append(partialResult)
                                if (streamsAnalysisToUser) {
                                    _objectDescription.value = llmTextBuffer.toString()
                                    sentenceChunker.onToken(partialResult)
                                    drainChunkerToTts()
                                }
                            }

                            if (done) {
                                _isAnalyzing.value = false
                                inferenceJob = null
                                val finalText = llmTextBuffer.toString()
                                Log.d(TAG, "LLM analysis complete, final text: '$finalText'")
                                if (streamsAnalysisToUser) {
                                    sentenceChunker.onDone()
                                    drainChunkerToTts()
                                    promptOnlineAfterSpeech.set(finalText.isNotBlank())
                                    ttsPlayer.markInputComplete()
                                }
                                onAnalysisComplete(finalText)
                            }
                        }
                    },
                    cleanUpListener = {
                        _isAnalyzing.value = false
                    },
                    onError = { errorMessage ->
                        Log.e(TAG, "Error analyzing image: $errorMessage")
                        _isAnalyzing.value = false
                        if (
                            OnlineAnalysisHelper.isAvailable() &&
                            AppSettings.onlineConsentGranted.value &&
                            AppSettings.onlineUsageMode.value == OnlineUsageMode.FALLBACK
                        ) {
                            Log.d(TAG, "Local analysis failed; using configured online fallback")
                            announceAction("On-device analysis failed. Trying online analysis.")
                            analyzeBitmapOnline(scaledBitmap, prompt)
                        } else {
                            reportOperationFailure("Analysis failed. Please try again.")
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error firing usage or launching inference", e)
                _isAnalyzing.value = false
                reportOperationFailure("Unable to analyze the image. Please try again.")
            }
        }
    }

    private fun analyzeBitmapOnline(
        bitmap: Bitmap,
        promptOverride: String? = null,
        sensorSnapshot: String? = null,
    ) {
        if (!_isProcessing.value || _isAnalyzing.value) return
        if (!OnlineAnalysisHelper.isAvailable()) {
            reportOperationFailure("Online analysis is not connected.")
            return
        }
        val isVerbose = AppSettings.llmResponseStyle.value == LlmResponseStyle.VERBOSE
        val prompt = promptOverride ?: appendSensorSnapshot(
            getAnalysisPrompt(customPrompt, isVerbose),
            sensorSnapshot ?: buildSensorPromptSnapshot(),
        )
        customPrompt = null
        _isAnalyzing.value = true
        _activeModelName.value = AppSettings.onlineProvider.value.displayName
        inferenceJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = OnlineAnalysisHelper.analyzeImage(bitmap, prompt)
                if (!_isProcessing.value) return@launch
                _isAnalyzing.value = false
                inferenceJob = null
                if (streamsAnalysisToUser) {
                    _objectDescription.value = result
                    speakText(result)
                }
                onAnalysisComplete(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Online analysis failed: ${e.message}")
                _isAnalyzing.value = false
                inferenceJob = null
                if (currentModel?.instance != null && _isProcessing.value) {
                    _error.value = "Online analysis unavailable. Using the on-device model."
                    announceAction("Online analysis is unavailable. Using the on-device model.")
                    analyzeBitmap(bitmap, forceLocal = true, promptOverride = prompt)
                } else {
                    reportOperationFailure("Online analysis failed. Please try again.")
                }
            }
        }
    }

    // ---- Protected helpers ----

    /**
     * Speak text using the shared TTS player. Uses an isolated [StreamingSentenceChunker]
     * to split text into well-paced sentences, handling abbreviations and short chunks appropriately.
     */
    protected fun speakText(text: String) {
        ttsPlayer.stop()
        
        // Use an isolated chunker so we don't interfere with the streaming inference chunker
        val localChunker = StreamingSentenceChunker()
        localChunker.onToken(text)
        localChunker.onDone()
        
        while (localChunker.hasQueuedSentences()) {
            val sentence = localChunker.pollSentence() ?: break
            ttsPlayer.queueSentence(sentence)
        }
        ttsPlayer.markInputComplete()
    }

    /**
     * Drain any complete sentences from the chunker and queue them for TTS.
     * Only queues up to 3 sentences at a time to allow proximity alerts to
     * interrupt via QUEUE_FLUSH without losing too many buffered sentences.
     */
    protected fun drainChunkerToTts() {
        var queued = 0
        while (sentenceChunker.hasQueuedSentences() && queued < 3) {
            val sentence = sentenceChunker.pollSentence() ?: break
            ttsPlayer.queueSentence(sentence)
            queued++
        }
    }

    /**
     * Convert a CameraX [ImageProxy] to an ARGB_8888 [Bitmap].
     * Supports YUV_420_888 and JPEG image formats.
     */
    protected fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val image = imageProxy.image ?: return null

            when (image.format) {
                ImageFormat.YUV_420_888 -> {
                    val yBuffer = image.planes[0].buffer
                    val uBuffer = image.planes[1].buffer
                    val vBuffer = image.planes[2].buffer

                    val ySize = yBuffer.remaining()
                    val uSize = uBuffer.remaining()
                    val vSize = vBuffer.remaining()

                    val nv21 = ByteArray(ySize + uSize + vSize)

                    yBuffer.get(nv21, 0, ySize)
                    vBuffer.get(nv21, ySize, vSize)
                    uBuffer.get(nv21, ySize + vSize, uSize)

                    val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                    val out = ByteArrayOutputStream()
                    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
                    val imageBytes = out.toByteArray()

                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                    if (bitmap.config != Bitmap.Config.ARGB_8888) {
                        val rgbaBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        bitmap.recycle()
                        rgbaBitmap
                    } else {
                        bitmap
                    }
                }
                ImageFormat.JPEG -> {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)

                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                    if (bitmap?.config != Bitmap.Config.ARGB_8888) {
                        val rgbaBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        bitmap?.recycle()
                        rgbaBitmap
                    } else {
                        bitmap
                    }
                }
                else -> {
                    Log.w(TAG, "Unsupported image format: ${image.format}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting ImageProxy to Bitmap", e)
            null
        }
    }

    /**
     * Rotate a [Bitmap] by the given degrees if non-zero. Recycles the original bitmap
     * when a new rotated bitmap is created.
     */
    protected fun rotateBitmapIfNeeded(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val deg = ((rotationDegrees % 360) + 360) % 360
        if (deg == 0) return bitmap
        return try {
            val matrix = Matrix()
            matrix.postRotate(deg.toFloat())
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            rotated
        } catch (e: Exception) {
            Log.e(TAG, "rotateBitmapIfNeeded: failed to rotate by ${deg}, returning original", e)
            bitmap
        }
    }

    /**
     * Scale a [Bitmap] so its longest edge is at most [maxSize] pixels.
     * Returns the original bitmap unchanged if it is already within limits.
     * Recycles the original when a new scaled bitmap is created.
     */
    protected fun scaleBitmapToMaxSize(bitmap: Bitmap, maxSize: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxSize && h <= maxSize) return bitmap

        val scale = maxSize.toFloat() / maxOf(w, h).toFloat()
        val newW = (w * scale).toInt()
        val newH = (h * scale).toInt()
        Log.d(TAG, "Scaling bitmap from ${w}x${h} to ${newW}x${newH} (maxSize=$maxSize)")
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        if (scaled !== bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        return scaled
    }

    // ---- Private helpers ----

    private fun cancelInferenceJob() {
        try {
            inferenceJob?.cancel()
            Log.d(TAG, "Inference job cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling inferenceJob", e)
        } finally {
            inferenceJob = null
        }
    }

    // ---- Proximity / Collision Avoidance ----

    protected val detectionHelper: DetectionHelper by lazy { DetectionHelper(application) }

    protected val _proximityAlert = MutableStateFlow<ProximityAlert?>(null)
    val proximityAlert: StateFlow<ProximityAlert?> = _proximityAlert

    protected val _proximityBoxes = MutableStateFlow<List<DetectionBox>>(emptyList())
    val proximityBoxes: StateFlow<List<DetectionBox>> = _proximityBoxes

    protected val _proximityBest = MutableStateFlow<DetectionBox?>(null)
    val proximityBest: StateFlow<DetectionBox?> = _proximityBest

    private val _isCollisionAvoidanceEnabled = MutableStateFlow(false)
    val isCollisionAvoidanceEnabled: StateFlow<Boolean> = _isCollisionAvoidanceEnabled

    private val _depthSensingDescription = MutableStateFlow("Camera estimate")
    val depthSensingDescription: StateFlow<String> = _depthSensingDescription
    private val _analysisSensorContext = MutableStateFlow("")
    val analysisSensorContext: StateFlow<String> = _analysisSensorContext
    private var latestDepthObservations: List<DepthObservation> = emptyList()
    @Volatile private var lastProximityBoxesAtMs = 0L
    private val proximityAnalysisInFlight = AtomicBoolean(false)

    private var lastAlertTs: Long = 0L
    private var lastAlertBearing: Bearing? = null
    private var lastAlertSeverity: ProximitySeverity? = null
    private var lastAlertDistanceMeters: Float? = null
    private var candidateAlertBearing: Bearing? = null
    private var candidateAlertSeverity: ProximitySeverity? = null
    private var candidateAlertSinceMs: Long = 0L

    /**
     * Minimum interval before re-announcing the SAME bearing+severity combo.
     * A different bearing or escalated severity can bypass this sooner.
     */
    private val sameAlertCooldownMs: Long = 4500L

    fun toggleCollisionAvoidance() {
        _isCollisionAvoidanceEnabled.value = !_isCollisionAvoidanceEnabled.value
        announceAction(
            if (_isCollisionAvoidanceEnabled.value) {
                "Safety alerts on."
            } else {
                "Safety alerts off."
            },
        )
        if (!_isCollisionAvoidanceEnabled.value) {
            _proximityBoxes.value = emptyList()
            _proximityBest.value = null
            _proximityAlert.value = null
            latestDepthObservations = emptyList()
            lastProximityBoxesAtMs = 0L
            ttsPlayer.clearPendingSafetyAnnouncements()
            resetSafetyAnnouncementState()
        }
    }

    fun updateDepthAvailability(available: Boolean, reason: String? = null) {
        _depthSensingDescription.value = if (available) "ARCore depth" else "Camera estimate"
        if (!available) latestDepthObservations = emptyList()
        if (!available && !reason.isNullOrBlank()) Log.i(TAG, "ARCore depth unavailable: $reason")
    }

    fun updateDepthObservations(observations: List<DepthObservation>) {
        if (!supportsCollisionAvoidance || !_isCollisionAvoidanceEnabled.value) return
        latestDepthObservations = observations
        if (!_areSafetyAlertsSuppressed.value) publishMetricAlert(_proximityBoxes.value)
    }

    private fun buildSensorPromptSnapshot(nowMs: Long = System.currentTimeMillis()): String? {
        if (!supportsCollisionAvoidance || !_isCollisionAvoidanceEnabled.value) return null
        val depth = latestDepthObservations
            .filter { nowMs - it.timestampMs < 1_200L && it.confidence >= 0.20f }
            .sortedBy { it.bearing.ordinal }
        val objects = (if (nowMs - lastProximityBoxesAtMs < 1_200L) _proximityBoxes.value else emptyList())
            .filter { it.score >= 0.35f }
            .sortedWith(
                compareByDescending<DetectionBox> { it.width * it.height }
                    .thenByDescending { it.score },
            )
            .take(5)
        if (depth.isEmpty() && objects.isEmpty()) return null

        val lines = mutableListOf(
            "<SENSOR_SNAPSHOT>",
            "Captured near the image timestamp. Advisory only; reconcile it with visible evidence and do not invent precision.",
        )
        if (depth.isNotEmpty()) {
            val regions = depth.joinToString("; ") { observation ->
                val surface = if (observation.surface == DepthSurface.WALL) ", wall-like surface" else ""
                "%s %.1f m (confidence %.0f%%%s)".format(
                    Locale.US,
                    observation.bearing.name.lowercase(Locale.US),
                    observation.distanceMeters,
                    observation.confidence * 100f,
                    surface,
                )
            }
            lines += "Depth source: ${_depthSensingDescription.value}. Regions: $regions."
        }
        if (objects.isNotEmpty()) {
            val detections = objects.joinToString("; ") { box ->
                val apparentDepth = 1f - kotlin.math.min(
                    1f,
                    box.height.toFloat() / kotlin.math.max(1, box.imageHeight).toFloat(),
                )
                val proximity = when {
                    apparentDepth <= 0.20f -> "near"
                    apparentDepth <= 0.40f -> "mid-range"
                    else -> "farther"
                }
                "%s %s (%s, %.0f%%)".format(
                    Locale.US,
                    box.label.ifBlank { "obstacle" },
                    bearingFromCenterXNorm(box.centerXNormalized()).name.lowercase(Locale.US),
                    proximity,
                    box.score * 100f,
                )
            }
            lines += "Object detector: $detections. Apparent proximity is image-size based, not metric range."
        }
        lines += "Use sensor data to improve obstacle, wall, pathway, and distance guidance. If sensor and image disagree, state uncertainty briefly."
        lines += "</SENSOR_SNAPSHOT>"
        return lines.joinToString("\n")
    }

    private fun appendSensorSnapshot(prompt: String, snapshot: String?): String =
        if (snapshot.isNullOrBlank()) prompt else "$prompt\n$snapshot"

    private fun sensorContextSummary(snapshot: String?): String {
        if (snapshot.isNullOrBlank()) {
            return "Analysis context: image only — turn on Safety for range and object context"
        }
        val sources = buildList {
            if (snapshot.contains("Depth source:")) add("metric range")
            if (snapshot.contains("Object detector:")) add("detected objects")
        }
        return "Analysis context: ${sources.joinToString(" + ")}"
    }

    fun reportDepthCameraNotReady() {
        reportOperationFailure("Depth camera is starting. Please try again.")
    }

    /**
     * Analyze image for proximity/obstacles if collision avoidance is enabled.
     * This should be called from the camera stream analyzer.
     */
    fun analyzeProximity(imageProxy: ImageProxy) {
        // If feature disabled or not supported, strictly close and return
        if (!supportsCollisionAvoidance || !_isCollisionAvoidanceEnabled.value) {
            imageProxy.close()
            return
        }

        try {
            val srcBitmap = imageProxyToBitmap(imageProxy)
            if (srcBitmap == null) {
                imageProxy.close()
                return
            }
            val rotation = try { imageProxy.imageInfo.rotationDegrees } catch (e: Exception) { 0 }
            val rotatedBitmap = rotateBitmapIfNeeded(srcBitmap, rotation)

            if (!detectionHelper.isEnabled()) {
                imageProxy.close()
                return
            }

            analyzeProximityBitmap(rotatedBitmap)

        } catch (e: Exception) {
            Log.e(TAG, "analyzeProximity error: ${e.message}")
        } finally {
            try { imageProxy.close() } catch (_: Exception) {}
        }
    }

    /** Analyze an ARCore camera frame while ARCore owns the camera for Depth API use. */
    fun analyzeProximityBitmap(bitmap: Bitmap) {
        if (
            !supportsCollisionAvoidance ||
            !_isCollisionAvoidanceEnabled.value ||
            _areSafetyAlertsSuppressed.value
        ) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        if (!proximityAnalysisInFlight.compareAndSet(false, true)) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                if (!detectionHelper.isEnabled()) return@launch
                val boxes = detectionHelper.detect(bitmap)
                if (!_isCollisionAvoidanceEnabled.value || _areSafetyAlertsSuppressed.value) return@launch
                _proximityBoxes.value = boxes
                lastProximityBoxesAtMs = System.currentTimeMillis()
                if (publishMetricAlert(boxes)) return@launch

                if (boxes.isEmpty()) {
                    _proximityBest.value = null
                    _proximityAlert.value = null
                    return@launch
                }

                var best: Pair<DetectionBox, Float>? = null
                for (box in boxes) {
                    val relativeDepth = 1f - kotlin.math.min(
                        1f,
                        box.height.toFloat() / kotlin.math.max(1, box.imageHeight).toFloat(),
                    )
                    val score = (if (isInsideCorridor(box.centerXNormalized(), box.centerYNormalized())) 1f else 0.8f) *
                        (1f - relativeDepth)
                    if (best == null || score > best!!.second) best = box to score
                }
                val box = best?.first ?: return@launch
                _proximityBest.value = box
                val relativeDepth = 1f - kotlin.math.min(
                    1f,
                    box.height.toFloat() / kotlin.math.max(1, box.imageHeight).toFloat(),
                )
                val alert = ProximityAlert(
                    label = box.label.ifBlank { "Obstacle" },
                    severity = severityForRelativeDepth(relativeDepth),
                    bearing = bearingFromCenterXNorm(box.centerXNormalized()),
                    relativeDepth = relativeDepth,
                )
                maybeSpeakProximity(alert)
                _proximityAlert.value = alert
            } catch (error: Exception) {
                Log.e(TAG, "analyzeProximityBitmap failed", error)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
                proximityAnalysisInFlight.set(false)
            }
        }
    }

    private fun publishMetricAlert(boxes: List<DetectionBox>): Boolean {
        if (_areSafetyAlertsSuppressed.value) return false
        val now = System.currentTimeMillis()
        val nearest = latestDepthObservations
            .filter { now - it.timestampMs < 1_000L && it.confidence >= 0.2f }
            .minByOrNull { it.distanceMeters }
            ?: return false
        val matchingBox = boxes
            .filter { bearingFromCenterXNorm(it.centerXNormalized()) == nearest.bearing }
            .maxByOrNull { it.height }
        _proximityBest.value = matchingBox
        val label = when {
            nearest.surface == DepthSurface.WALL -> "Wall"
            matchingBox != null -> matchingBox.label.ifBlank { "Obstacle" }
            else -> "Obstacle"
        }
        val alert = ProximityAlert(
            label = label,
            severity = severityForMetric(nearest.distanceMeters),
            bearing = nearest.bearing,
            distanceMeters = nearest.distanceMeters,
        )
        maybeSpeakProximity(alert)
        _proximityAlert.value = alert
        return true
    }

    private fun maybeSpeakProximity(alert: ProximityAlert) {
        if (_areSafetyAlertsSuppressed.value) return
        val now = System.currentTimeMillis()
        val elapsed = now - lastAlertTs

        val sameBearing = alert.bearing == lastAlertBearing
        val sameSeverity = alert.severity == lastAlertSeverity
        val escalated = severityRank(alert.severity) > severityRank(lastAlertSeverity)
        val materiallyCloser = lastAlertDistanceMeters?.let { previous ->
            alert.distanceMeters?.let { current -> previous - current >= 0.5f }
        } == true

        // ── Hysteresis logic ──
        // 1. If severity escalated (e.g. INFO→WARNING or WARNING→URGENT), always speak immediately.
        // 2. If bearing changed, use a shorter cooldown.
        // 3. If same bearing+severity, use a longer cooldown.
        if (!escalated) {
            if (!sameBearing || !sameSeverity) {
                val sameCandidate = candidateAlertBearing == alert.bearing &&
                    candidateAlertSeverity == alert.severity
                if (!sameCandidate) {
                    candidateAlertBearing = alert.bearing
                    candidateAlertSeverity = alert.severity
                    candidateAlertSinceMs = now
                    return
                }
                if (now - candidateAlertSinceMs < 400L || elapsed < 1_000L) return
            } else if (materiallyCloser) {
                candidateAlertBearing = null
                candidateAlertSeverity = null
                if (elapsed < 1_200L) return
            } else if (elapsed < sameAlertCooldownMs) {
                candidateAlertBearing = null
                candidateAlertSeverity = null
                return
            } else {
                candidateAlertBearing = null
                candidateAlertSeverity = null
            }
        }
        candidateAlertBearing = null
        candidateAlertSeverity = null

        val label = if (alert.label.isNotBlank()) alert.label else "Obstacle"
        val roundedDistance = alert.distanceMeters?.let { kotlin.math.round(it * 2f) / 2f }
        val range = roundedDistance?.let { " %.1f meters".format(Locale.US, it) }.orEmpty()
        val phrase = when (alert.severity) {
            ProximitySeverity.URGENT -> when (alert.bearing) {
                Bearing.LEFT -> "Stop. $label left."
                Bearing.CENTER -> "Stop. $label ahead."
                Bearing.RIGHT -> "Stop. $label right."
            }
            ProximitySeverity.WARNING -> when (alert.bearing) {
                Bearing.LEFT -> "Caution, $label$range left."
                Bearing.CENTER -> "Caution, $label$range ahead."
                Bearing.RIGHT -> "Caution, $label$range right."
            }
            ProximitySeverity.INFO -> when (alert.bearing) {
                Bearing.LEFT -> "$label$range left."
                Bearing.CENTER -> "$label$range ahead."
                Bearing.RIGHT -> "$label$range right."
            }
        }

        playSafetyHaptic(alert.severity)
        val urgentRangeFollowUp = if (alert.severity == ProximitySeverity.URGENT) {
            roundedDistance?.let { "About %.1f meters.".format(Locale.US, it) }
        } else {
            null
        }
        ttsPlayer.announceSafety(
            phrase,
            alert.severity,
            AppSettings.safetySpeechRate.value,
            urgentRangeFollowUp,
        )

        lastAlertTs = now
        lastAlertBearing = alert.bearing
        lastAlertSeverity = alert.severity
        lastAlertDistanceMeters = alert.distanceMeters
    }

    private fun playSafetyHaptic(severity: ProximitySeverity) {
        if (severity == ProximitySeverity.INFO) return
        try {
            val vibrator = application.getSystemService(VibratorManager::class.java)?.defaultVibrator
            val effect = if (severity == ProximitySeverity.URGENT) {
                VibrationEffect.EFFECT_DOUBLE_CLICK
            } else {
                VibrationEffect.EFFECT_HEAVY_CLICK
            }
            vibrator?.vibrate(VibrationEffect.createPredefined(effect))
        } catch (error: Exception) {
            Log.w(TAG, "Unable to play Safety haptic", error)
        }
    }

    private fun suspendSafetyAlertsForAnalysis() {
        if (!supportsCollisionAvoidance) return
        _areSafetyAlertsSuppressed.value = true
        _proximityAlert.value = null
        ttsPlayer.setSafetyAnnouncementsSuspended(true)
    }

    private fun resumeSafetyAlertsAfterAnalysis() {
        if (!_areSafetyAlertsSuppressed.value) return
        _areSafetyAlertsSuppressed.value = false
        ttsPlayer.setSafetyAnnouncementsSuspended(false)
        resetSafetyAnnouncementState()
    }

    private fun resetSafetyAnnouncementState() {
        lastAlertTs = 0L
        lastAlertBearing = null
        lastAlertSeverity = null
        lastAlertDistanceMeters = null
        candidateAlertBearing = null
        candidateAlertSeverity = null
        candidateAlertSinceMs = 0L
    }

    private fun severityRank(s: ProximitySeverity?): Int = when (s) {
        null -> -1
        ProximitySeverity.INFO -> 0
        ProximitySeverity.WARNING -> 1
        ProximitySeverity.URGENT -> 2
    }

    // ---- Lifecycle ----

    override fun onCleared() {
        super.onCleared()
        voiceCommandHelper.stopListening()
        ttsPlayer.shutdown()
    }

    companion object {
        private const val TAG = "EchoSenseBaseVM"
        private const val MISSING_MODEL_ANNOUNCEMENT =
            "Download an on-device model in Settings to enable offline analysis."
        private const val MODEL_DOWNLOADING_ANNOUNCEMENT = "On-device model download in progress."
        private const val MODEL_LOADING_ANNOUNCEMENT = "On-device model loading. Wait for ready."
        private const val READY_ANNOUNCEMENT = "Ready."
        /** Maximum dimension (width or height) for images passed to the on-device LLM.
         *  Larger images are scaled down to avoid OOM / SIGSEGV in the native engine. */
        const val LLM_MAX_IMAGE_SIZE = 768
    }
}
