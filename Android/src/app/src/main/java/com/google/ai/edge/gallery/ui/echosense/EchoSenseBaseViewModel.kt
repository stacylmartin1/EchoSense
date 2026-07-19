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

package com.google.ai.edge.gallery.ui.echosense

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.home.AppSettings
import com.google.ai.edge.gallery.ui.home.LlmResponseStyle
import com.google.ai.edge.gallery.ui.home.OnlineUsageMode
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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

    protected val _activeModelName = MutableStateFlow<String?>(null)
    val activeModelName: StateFlow<String?> = _activeModelName

    protected val _voiceCommand = MutableStateFlow<String?>(null)
    val voiceCommand: StateFlow<String?> = _voiceCommand

    /** Store custom prompt for use in analysis. */
    protected var customPrompt: String? = null
    private var forceOnlineNext = false

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
                startProcessingWithCustomPrompt(command)
            }
        }

        override fun onError(error: Int) {
            viewModelScope.launch {
                _error.value = "Voice command error: $error"
            }
        }
    }

    protected val voiceCommandHelper = VoiceCommandHelper(application, voiceCommandListener)

    // ---- Image capture callback ----

    private var onCaptureImageCallback: (() -> Unit)? = null

    // ---- Status change announcement tracking ----

    private var previousModelReady = false
    private var previousAnalyzing = false
    private var modelLoadingAnnounced = false
    private var readyAnnounced = false
    private var modelLoadingRetryJob: Job? = null
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

    // ---- Public API ----

    fun setModel(model: Model) {
        currentModel = model
        Log.d(TAG, "Model set to: ${model.name}")
    }

    fun setImageCaptureCallback(callback: () -> Unit) {
        onCaptureImageCallback = callback
    }

    fun startProcessing() {
        Log.d(TAG, "Starting single-shot processing")

        cancelInferenceJob()

        sentenceChunker.clear()
        ttsPlayer.stop()
        promptOnlineAfterSpeech.set(false)

        _objectDescription.value = ""
        llmTextBuffer.clear()

        analyzingAnnounced = false

        _isProcessing.value = true

        val generation = System.nanoTime()
        processingGeneration = generation

        // Note: Conversation reset is handled in analyzeImage() to avoid race conditions
        Log.d(TAG, "Single-shot processing started with clean state")
    }

    fun startOnlineProcessing() {
        forceOnlineNext = true
        startProcessing()
    }

    fun stopProcessing() {
        Log.d(TAG, "Stopping processing")
        stopModelLoadingAnnouncementRetries()

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
    }

    /** Speak a brief action announcement (e.g. "Translating") with QUEUE_FLUSH.
     *  Also marks the analyzing announcement as done so the generic "Analyzing"
     *  announcement from [checkAndAnnounceStatusChanges] is suppressed. */
    fun announceAction(message: String) {
        analyzingAnnounced = true
        ttsPlayer.announceStatus(message)
    }

    fun updateVoiceCommand(command: String?) {
        _voiceCommand.value = command
    }

    fun checkAndAnnounceStatusChanges(isModelReady: Boolean, isAnalyzing: Boolean) {
        Log.d(TAG, "checkAndAnnounceStatusChanges: isModelReady=$isModelReady, isAnalyzing=$isAnalyzing, previousModelReady=$previousModelReady, previousAnalyzing=$previousAnalyzing")

        if (!isModelReady && !modelLoadingAnnounced && !isAnalyzing && !_isProcessing.value) {
            Log.d(TAG, "Model loading detected, attempting announcement")
            readyAnnounced = false
            ttsPlayer.announceStatus(MODEL_LOADING_ANNOUNCEMENT)
            modelLoadingAnnounced = true
            startModelLoadingAnnouncementRetries()
        }

        if (isModelReady && !readyAnnounced && !isAnalyzing && !_isProcessing.value) {
            Log.d(TAG, "Model became ready, announcing immediately")
            stopModelLoadingAnnouncementRetries()
            modelLoadingAnnounced = false
            ttsPlayer.announceStatus(READY_ANNOUNCEMENT)
            readyAnnounced = true
        }

        if (isAnalyzing && !analyzingAnnounced && _isProcessing.value) {
            stopModelLoadingAnnouncementRetries()
            Log.d(TAG, "Analysis started, announcing immediately")
            analyzingAnnounced = true
            ttsPlayer.announceStatus("Analyzing")
        }

        if (!isAnalyzing && analyzingAnnounced) {
            Log.d(TAG, "Analysis stopped, resetting analyzing announcement flag")
            analyzingAnnounced = false
        }

        previousModelReady = isModelReady
        previousAnalyzing = isAnalyzing
    }

    /**
     * Retry the startup status announcement after Android runtime permission dialogs close.
     * On first install, those dialogs can steal focus while TTS accepts but never audibly
     * plays the initial loading/ready phrase.
     */
    fun retryStartupStatusAnnouncementAfterPermission(isModelReady: Boolean, isAnalyzing: Boolean) {
        if (isAnalyzing || _isProcessing.value) return

        if (isModelReady) {
            Log.d(TAG, "Retrying ready announcement after permission dialog")
            stopModelLoadingAnnouncementRetries()
            modelLoadingAnnounced = false
            readyAnnounced = true
            ttsPlayer.announceStatus(READY_ANNOUNCEMENT)
        } else {
            Log.d(TAG, "Retrying model loading announcement after permission dialog")
            readyAnnounced = false
            modelLoadingAnnounced = true
            ttsPlayer.announceStatus(MODEL_LOADING_ANNOUNCEMENT)
            startModelLoadingAnnouncementRetries()
        }
    }

    private fun startModelLoadingAnnouncementRetries() {
        if (modelLoadingRetryJob?.isActive == true) return

        modelLoadingRetryJob = viewModelScope.launch {
            repeat(MODEL_LOADING_RETRY_COUNT) { attempt ->
                delay(MODEL_LOADING_RETRY_DELAY_MS)
                if (previousModelReady || _isProcessing.value || _isAnalyzing.value) {
                    Log.d(TAG, "Stopping model loading announcement retries")
                    return@launch
                }

                Log.d(TAG, "Retrying model loading announcement (${attempt + 1}/$MODEL_LOADING_RETRY_COUNT)")
                ttsPlayer.announceStatus(MODEL_LOADING_ANNOUNCEMENT)
            }
        }
    }

    private fun stopModelLoadingAnnouncementRetries() {
        modelLoadingRetryJob?.cancel()
        modelLoadingRetryJob = null
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
            _error.value = "Model not set. Please wait for model to be selected."
            imageProxy.close()
            return
        }

        // Convert ImageProxy to Bitmap (ARGB_8888) and rotate to display orientation
        val bitmap: Bitmap
        try {
            val srcBitmap = imageProxyToBitmap(imageProxy)
            if (srcBitmap != null) {
                val rotation = try { imageProxy.imageInfo.rotationDegrees } catch (e: Exception) { 0 }
                val rotated = rotateBitmapIfNeeded(srcBitmap, rotation)
                bitmap = scaleBitmapToMaxSize(rotated, LLM_MAX_IMAGE_SIZE)
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
                _error.value = "Unable to process camera image"
                imageProxy.close()
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Bitmap from ImageProxy", e)
            _error.value = "Error processing camera image: ${e.message}"
            imageProxy.close()
            return
        }

        val forceOnline = forceOnlineNext
        forceOnlineNext = false
        analyzeBitmap(bitmap, forceOnline)
    }

    /**
     * Analyze a pre-converted [Bitmap] using the LLM. Resets the conversation, sends the
     * prompt + image to the model, and streams results through the sentence chunker to TTS.
     *
     * This method can be called directly when a bitmap is already available (e.g., from
     * proximity detection or a saved image), or indirectly via [analyzeImage].
     */
    fun analyzeBitmap(bitmap: Bitmap, forceOnline: Boolean = false) {
        val model = currentModel
        if (model == null) {
            _error.value = "Model not set. Please wait for model to be selected."
            return
        }

        // Downscale if needed to avoid OOM / native crashes in the LLM engine
        val scaledBitmap = scaleBitmapToMaxSize(bitmap, LLM_MAX_IMAGE_SIZE)

        val canUseOnline =
            OnlineAnalysisHelper.isAvailable() && AppSettings.onlineConsentGranted.value
        if (canUseOnline && (forceOnline || AppSettings.onlineUsageMode.value == OnlineUsageMode.PREFER_ONLINE)) {
            analyzeBitmapOnline(scaledBitmap)
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
                        _error.value = "Model not ready. Please wait for model to initialize."
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

                val prompt = getAnalysisPrompt(customPrompt, isVerbose)
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
                                _objectDescription.value = llmTextBuffer.toString()
                                sentenceChunker.onToken(partialResult)
                                drainChunkerToTts()
                            }

                            if (done) {
                                _isAnalyzing.value = false
                                inferenceJob = null
                                sentenceChunker.onDone()
                                drainChunkerToTts()
                                val finalText = llmTextBuffer.toString()
                                Log.d(TAG, "LLM analysis complete, final text: '$finalText'")
                                promptOnlineAfterSpeech.set(finalText.isNotBlank())
                                ttsPlayer.markInputComplete()
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
                            analyzeBitmapOnline(scaledBitmap, prompt)
                        } else {
                            _error.value = "Error: $errorMessage"
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error firing usage or launching inference", e)
                _error.value = "Error analyzing image: ${e.message}"
                _isAnalyzing.value = false
            }
        }
    }

    private fun analyzeBitmapOnline(bitmap: Bitmap, promptOverride: String? = null) {
        if (!_isProcessing.value || _isAnalyzing.value || !OnlineAnalysisHelper.isAvailable()) return
        val isVerbose = AppSettings.llmResponseStyle.value == LlmResponseStyle.VERBOSE
        val prompt = promptOverride ?: getAnalysisPrompt(customPrompt, isVerbose)
        customPrompt = null
        _isAnalyzing.value = true
        _activeModelName.value = AppSettings.onlineProvider.value.displayName
        inferenceJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = OnlineAnalysisHelper.analyzeImage(bitmap, prompt)
                if (!_isProcessing.value) return@launch
                _objectDescription.value = result
                _isAnalyzing.value = false
                inferenceJob = null
                speakText(result)
                onAnalysisComplete(result)
            } catch (e: Exception) {
                Log.w(TAG, "Online analysis failed: ${e.message}")
                _error.value = e.message ?: "Online analysis failed"
                _isAnalyzing.value = false
                inferenceJob = null
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

    private fun startProcessingWithCustomPrompt(userPrompt: String) {
        Log.d(TAG, "Starting processing with custom prompt: '$userPrompt'")

        customPrompt = userPrompt

        startProcessing()

        onCaptureImageCallback?.invoke() ?: run {
            Log.w(TAG, "Image capture callback not set, cannot capture image for voice command")
            _error.value = "Unable to capture image for voice command"
            stopProcessing()
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

    private var lastAlertTs: Long = 0L
    private var lastAlertBearing: Bearing? = null
    private var lastAlertSeverity: ProximitySeverity? = null

    /**
     * Minimum interval before re-announcing the SAME bearing+severity combo.
     * A different bearing or escalated severity can bypass this sooner.
     */
    private val sameAlertCooldownMs: Long = 3500L
    /** Shorter cooldown when the bearing changes (obstacle moved left→right etc.) */
    private val differentBearingCooldownMs: Long = 1500L

    fun toggleCollisionAvoidance() {
        _isCollisionAvoidanceEnabled.value = !_isCollisionAvoidanceEnabled.value
        if (!_isCollisionAvoidanceEnabled.value) {
            _proximityBoxes.value = emptyList()
            _proximityBest.value = null
            _proximityAlert.value = null
        }
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

            val boxes = detectionHelper.detect(rotatedBitmap)
            _proximityBoxes.value = boxes
            
            if (boxes.isEmpty()) {
                _proximityBest.value = null
                imageProxy.close()
                return
            }

            // Find "best" (most urgent) box based on corridor and depth
            var best: Pair<DetectionBox, Float>? = null
            for (b in boxes) {
                val cx = b.centerXNormalized()
                val cy = b.centerYNormalized()
                val inCorridor = isInsideCorridor(cx, cy)
                val relDepth = 1f - kotlin.math.min(1f, b.height.toFloat() / kotlin.math.max(1, b.imageHeight).toFloat())
                // Score favors objects in corridor and closer (lower relDepth)
                val score = (if (inCorridor) 1.0f else 0.8f) * (1.0f - relDepth)
                if (best == null || score > best.second) {
                    best = Pair(b, score)
                }
            }

            val (box, _) = best ?: run {
                _proximityBest.value = null
                imageProxy.close()
                return
            }

            _proximityBest.value = box

            val cxn = box.centerXNormalized()
            val bearing = bearingFromCenterXNorm(cxn)
            val rel = 1f - kotlin.math.min(1f, box.height.toFloat() / kotlin.math.max(1, box.imageHeight).toFloat())
            val severity = severityForRelativeDepth(rel)
            val label = if (box.label.isNotBlank()) box.label else "Obstacle"

            val alert = ProximityAlert(
                label = label,
                severity = severity,
                bearing = bearing,
                distanceMeters = null,
                relativeDepth = rel,
            )

            maybeSpeakProximity(alert)
            _proximityAlert.value = alert

        } catch (e: Exception) {
            Log.e(TAG, "analyzeProximity error: ${e.message}")
        } finally {
            try { imageProxy.close() } catch (_: Exception) {}
        }
    }

    private fun maybeSpeakProximity(alert: ProximityAlert) {
        val now = System.currentTimeMillis()
        val elapsed = now - lastAlertTs

        val sameBearing = alert.bearing == lastAlertBearing
        val sameSeverity = alert.severity == lastAlertSeverity
        val escalated = severityRank(alert.severity) > severityRank(lastAlertSeverity)

        // ── Hysteresis logic ──
        // 1. If severity escalated (e.g. INFO→WARNING or WARNING→URGENT), always speak immediately.
        // 2. If bearing changed, use a shorter cooldown.
        // 3. If same bearing+severity, use a longer cooldown.
        if (!escalated) {
            if (sameBearing && sameSeverity && elapsed < sameAlertCooldownMs) return
            if (!sameBearing && elapsed < differentBearingCooldownMs) return
        }

        val label = if (alert.label.isNotBlank()) alert.label else "Obstacle"
        val phrase = when (alert.severity) {
            ProximitySeverity.URGENT -> when (alert.bearing) {
                Bearing.LEFT -> "Stop. $label left."
                Bearing.CENTER -> "Stop. $label ahead."
                Bearing.RIGHT -> "Stop. $label right."
            }
            ProximitySeverity.WARNING -> when (alert.bearing) {
                Bearing.LEFT -> "Caution, $label left."
                Bearing.CENTER -> "Caution, $label ahead."
                Bearing.RIGHT -> "Caution, $label right."
            }
            ProximitySeverity.INFO -> when (alert.bearing) {
                Bearing.LEFT -> "$label left."
                Bearing.CENTER -> "$label ahead."
                Bearing.RIGHT -> "$label right."
            }
        }

        // Always flush-speak so the newest proximity alert wins over stale speech.
        ttsPlayer.announceStatus(phrase)

        lastAlertTs = now
        lastAlertBearing = alert.bearing
        lastAlertSeverity = alert.severity
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
        stopModelLoadingAnnouncementRetries()
        voiceCommandHelper.stopListening()
        ttsPlayer.shutdown()
    }

    companion object {
        private const val TAG = "EchoSenseBaseVM"
        private const val MODEL_LOADING_ANNOUNCEMENT = "Model loading wait for ready"
        private const val READY_ANNOUNCEMENT = "Ready."
        private const val MODEL_LOADING_RETRY_COUNT = 3
        private const val MODEL_LOADING_RETRY_DELAY_MS = 2_000L
        /** Maximum dimension (width or height) for images passed to the on-device LLM.
         *  Larger images are scaled down to avoid OOM / SIGSEGV in the native engine. */
        const val LLM_MAX_IMAGE_SIZE = 768
    }
}
