package com.google.ai.edge.gallery.ui.echosense

import android.app.Application
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import com.google.mediapipe.framework.image.MPImage
import android.util.Log
import android.media.AudioAttributes
import java.util.Locale

@HiltViewModel
class EchoSenseViewModel @Inject constructor(
    private val application: Application
) : ViewModel(), TextToSpeech.OnInitListener {

    private lateinit var textToSpeech: TextToSpeech
    private var currentModel: Model? = null
    private var isTtsReady = false
    private var llmTextBuffer = StringBuilder()
    
    // Variables for continuous TTS functionality
    private var llmStartTime: Long = 0
    private var ttsTriggered = false
    private var llmCompleted = false
    private var ttsCompleted = false
    private var totalTextSpoken = 0 // Track total characters spoken so far
    private var isContinuousTtsActive = false // Track if continuous TTS is running
    
    // Store custom prompt for use in analysis
    private var customPrompt: String? = null
    
    // Add callback for triggering image capture from voice commands
    private var onCaptureImageCallback: (() -> Unit)? = null
    
    // Track previous states for status change detection
    private var previousModelReady = false
    private var previousAnalyzing = false

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _voiceCommand = MutableStateFlow<String?>(null)
    val voiceCommand: StateFlow<String?> = _voiceCommand

    private val _objectDescription = MutableStateFlow<String>("")
    val objectDescription: StateFlow<String> = _objectDescription

    private val _soundDescription = MutableStateFlow<String>("")
    val soundDescription: StateFlow<String> = _soundDescription

    private val _isAudioProcessing = MutableStateFlow(false)
    val isAudioProcessing: StateFlow<Boolean> = _isAudioProcessing

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Add throttling to prevent concurrent LLM requests
    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing

    private val voiceCommandListener = object : VoiceCommandHelper.VoiceCommandListener {
        override fun onVoiceCommand(command: String) {
            viewModelScope.launch {
                _voiceCommand.value = command
                Log.d("EchoSenseViewModel", "Voice command received: '$command'")
                
                // Check for predefined commands first
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
                
                // For any other command, treat it as a custom prompt and trigger image analysis
                Log.d("EchoSenseViewModel", "Treating voice command as custom prompt: '$command'")
                startProcessingWithCustomPrompt(command)
            }
        }

        override fun onError(error: Int) {
            viewModelScope.launch {
                _error.value = "Voice command error: $error"
            }
        }
    }

    init {
        Log.d("EchoSenseViewModel", "Initializing TTS...")
        textToSpeech = TextToSpeech(application, this)
    }

    private val voiceCommandHelper = VoiceCommandHelper(application, voiceCommandListener)

    fun startProcessing() {
        Log.d("EchoSenseViewModel", "Starting single-shot processing")
        
        // Stop any ongoing TTS (safe to do on main thread)
        textToSpeech.stop()
        
        // Clear previous state (safe to do on main thread)
        _objectDescription.value = ""
        llmTextBuffer.clear()
        
        // Start processing immediately
        _isProcessing.value = true
        
        // Reset session in background thread to avoid ANR
        viewModelScope.launch(Dispatchers.Default) {
            currentModel?.let { model ->
                // Synchronize access to model instance to prevent race conditions
                synchronized(model) {
                    if (model.instance != null) {
                        Log.d("EchoSenseViewModel", "Resetting LLM session for fresh start")
                        try {
                            LlmChatModelHelper.resetSession(model)
                            Log.d("EchoSenseViewModel", "LLM session reset completed")
                        } catch (e: Exception) {
                            Log.e("EchoSenseViewModel", "Error resetting LLM session", e)
                            // Clear the instance if reset fails to prevent further crashes
                            model.instance = null
                        }
                    }
                }
            }
        }
        
        Log.d("EchoSenseViewModel", "Single-shot processing started with clean state")
    }

    fun stopProcessing() {
        Log.d("EchoSenseViewModel", "Stopping processing")
        _isProcessing.value = false
        _isAnalyzing.value = false
        textToSpeech.stop()
        
        // Clear any ongoing analysis
        _objectDescription.value = ""
        llmTextBuffer.clear()
        
        Log.d("EchoSenseViewModel", "Processing stopped and state cleared")
    }

    fun startListening() {
        voiceCommandHelper.startListening()
    }

    fun setModel(model: Model) {
        currentModel = model
        Log.d("EchoSenseViewModel", "Model set to: ${model.name}")
    }
    
    fun setImageCaptureCallback(callback: () -> Unit) {
        onCaptureImageCallback = callback
    }
    
    private fun startProcessingWithCustomPrompt(userPrompt: String) {
        Log.d("EchoSenseViewModel", "Starting processing with custom prompt: '$userPrompt'")
        
        // Store the custom prompt for later use
        customPrompt = userPrompt
        
        // Start processing
        startProcessing()
        
        // Trigger image capture
        onCaptureImageCallback?.invoke() ?: run {
            Log.w("EchoSenseViewModel", "Image capture callback not set, cannot capture image for voice command")
            _error.value = "Unable to capture image for voice command"
            stopProcessing()
        }
    }
    
    fun checkAndAnnounceStatusChanges(isModelReady: Boolean, isAnalyzing: Boolean) {
        // Announce when model becomes ready (Start button becomes available)
        if (isModelReady && !previousModelReady && !isAnalyzing && !_isProcessing.value) {
            Log.d("EchoSenseViewModel", "Model became ready, announcing 'Ready'")
            speak("Ready")
        }
        
        // Announce when analysis begins
        if (isAnalyzing && !previousAnalyzing) {
            Log.d("EchoSenseViewModel", "Analysis started, announcing 'Analyzing'")
            speak("Analyzing")
        }
        
        // Update previous states
        previousModelReady = isModelReady
        previousAnalyzing = isAnalyzing
    }

    fun onConfigChanged(oldConfigValues: Map<String, Any>, newConfigValues: Map<String, Any>) {
        Log.d("EchoSenseViewModel", "Configuration changed from $oldConfigValues to $newConfigValues")
        // The model will be automatically reinitialized by ModelPageAppBar if needed
        // No additional action required here as LlmChatModelHelper will use the new config values
    }

    fun analyzeImage(bitmap: Bitmap, customUserPrompt: String? = null) {
        // Check if processing is still active
        if (!_isProcessing.value) {
            Log.d("EchoSenseViewModel", "Processing stopped, skipping image analysis")
            return
        }
        
        // Check if already analyzing to prevent concurrent requests
        if (_isAnalyzing.value) {
            Log.d("EchoSenseViewModel", "Already analyzing, skipping new request")
            return
        }
        
        val model = currentModel
        if (model == null) {
            _error.value = "Model not set. Please wait for model to be selected."
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                // Double-check processing state before starting analysis
                if (!_isProcessing.value) {
                    Log.d("EchoSenseViewModel", "Processing stopped during setup, aborting analysis")
                    return@launch
                }
                
                // Synchronize access to model instance to prevent race conditions
                val modelInstance = synchronized(model) {
                    if (model.instance == null) {
                        Log.w("EchoSenseViewModel", "Model instance is null, cannot analyze image")
                        _error.value = "Model not ready. Please wait for model to initialize."
                        return@launch
                    }
                    model.instance
                }
                
                // Set analyzing flag to prevent concurrent requests
                _isAnalyzing.value = true
                
                // Clear the buffer for new analysis and reset TTS timing
                llmTextBuffer.clear()
                llmStartTime = 0
                ttsTriggered = false
                llmCompleted = false
                ttsCompleted = false
                totalTextSpoken = 0
                isContinuousTtsActive = false
                Log.d("EchoSenseViewModel", "Starting new LLM analysis, cleared text buffer and reset TTS timing")
                
                // Use custom prompt if provided, otherwise use default
                val prompt = if (customUserPrompt != null || customPrompt != null) {
                    val userPrompt = customUserPrompt ?: customPrompt
                    customPrompt = null // Clear after use
                    "Analyze the objects in the image to $userPrompt . Limit response descripions to what is specifically requested in the prompt but provide enough detail for a person with visual impairments to find specific objects in the environment."
                } else {
                    "Describe what you see in this image for a visually impaired person who needs to navigate safely indoors. Use complete sentences with proper punctuation. Mention important objects like furniture, obstacles, doorways, and pathways. Describe their locations and any potential hazards. Keep it concise but natural and conversational."
                }
                
                Log.d("EchoSenseViewModel", "Using prompt: '$prompt'")

                LlmChatModelHelper.runInference(
                    model = model,
                    input = prompt,
                    images = listOf(bitmap),
                    resultListener = { partialResult, done ->
                        viewModelScope.launch {
                            // Check if processing is still active before handling results
                            if (!_isProcessing.value) {
                                Log.d("EchoSenseViewModel", "Processing stopped, ignoring LLM result")
                                return@launch
                            }

                            Log.d("EchoSenseViewModel", "LLM result: done=$done, partialResult='$partialResult', buffer length=${llmTextBuffer.length}")
                            
                            // Record start time when first response arrives
                            if (llmStartTime == 0L && partialResult.isNotEmpty()) {
                                llmStartTime = System.currentTimeMillis()
                                Log.d("EchoSenseViewModel", "LLM started responding, recording start time: $llmStartTime")
                                
                                // Schedule TTS to start after 5 seconds
                                viewModelScope.launch {
                                    delay(5000) // 5 seconds
                                    if (_isProcessing.value && !ttsTriggered && llmTextBuffer.isNotEmpty()) {
                                        ttsTriggered = true
                                        isContinuousTtsActive = true
                                        Log.d("EchoSenseViewModel", "5 seconds elapsed, starting continuous TTS")
                                        viewModelScope.launch(Dispatchers.Main) {
                                            if (_isProcessing.value) {
                                                startContinuousTts()
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // Simply append each new token/partial result from the LLM streaming output
                            if (partialResult.isNotEmpty()) {
                                llmTextBuffer.append(partialResult)
                                Log.d("EchoSenseViewModel", "Appended to buffer: '$partialResult', total length: ${llmTextBuffer.length}")
                            }
                            
                            // Update UI with complete buffered text (shows all accumulated text)
                            val bufferedText = llmTextBuffer.toString()
                            _objectDescription.value = bufferedText
                            Log.d("EchoSenseViewModel", "Updated UI with buffered text: '$bufferedText'")
                            
                            if (done) {
                                // Clear analyzing flag when done
                                _isAnalyzing.value = false
                                llmCompleted = true
                                
                                if (_isProcessing.value) {
                                    val finalText = llmTextBuffer.toString()
                                    Log.d("EchoSenseViewModel", "LLM analysis complete, final buffered text: '$finalText'")
                                    
                                    // If TTS hasn't been triggered yet (LLM completed in less than 5 seconds), trigger it now
                                    if (!ttsTriggered && finalText.isNotBlank()) {
                                        ttsTriggered = true
                                        Log.d("EchoSenseViewModel", "LLM completed before 5 seconds, starting TTS immediately with complete text")
                                        // Ensure TTS runs on main thread
                                        viewModelScope.launch(Dispatchers.Main) {
                                            // Final check before speaking
                                            if (_isProcessing.value) {
                                                Log.d("EchoSenseViewModel", "About to call speak() from main thread with complete text: '$finalText'")
                                                speakAndStopWhenDone(finalText)
                                            } else {
                                                Log.d("EchoSenseViewModel", "Processing stopped, skipping TTS")
                                            }
                                        }
                                    } else if (finalText.isBlank()) {
                                        Log.w("EchoSenseViewModel", "LLM completed but buffered text is blank, stopping processing")
                                        stopProcessing()
                                    } else {
                                        Log.d("EchoSenseViewModel", "Continuous TTS active, LLM analysis complete")
                                        // LLM is done, continuous TTS will handle the final text when current utterance completes
                                        // No need to do anything here, the TTS completion listener will handle it
                                    }
                                } else {
                                    Log.d("EchoSenseViewModel", "Processing stopped, analysis complete but not speaking")
                                }
                            }
                        }
                    },
                    cleanUpListener = {
                        // Clear analyzing flag on cleanup
                        _isAnalyzing.value = false
                    }
                )
            } catch (e: Exception) {
                Log.e("EchoSenseViewModel", "Error analyzing image", e)
                _error.value = "Error analyzing image: ${e.message}"
                // Clear analyzing flag on error
                _isAnalyzing.value = false
            }
        }
    }

    fun updateVoiceCommand(command: String?) {
        _voiceCommand.value = command
    }

    override fun onCleared() {
        super.onCleared()
        voiceCommandHelper.stopListening()
        textToSpeech.stop()
        textToSpeech.shutdown()
        // Model cleanup is handled by ModelManagerViewModel
    }

    override fun onInit(status: Int) {
        Log.d("EchoSenseViewModel", "TTS onInit called with status: $status")
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale.US)
            Log.d("EchoSenseViewModel", "TTS setLanguage result: $result")
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("EchoSenseViewModel", "TTS language not supported - LANG_MISSING_DATA: ${result == TextToSpeech.LANG_MISSING_DATA}, LANG_NOT_SUPPORTED: ${result == TextToSpeech.LANG_NOT_SUPPORTED}")
                _error.value = "TTS language not supported"
                isTtsReady = false
            } else {
                // Configure TTS settings
                textToSpeech.setSpeechRate(1.0f)
                textToSpeech.setPitch(1.0f)
                
                // Set audio attributes for accessibility
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    textToSpeech.setAudioAttributes(audioAttributes)
                }
                
                isTtsReady = true
                Log.d("EchoSenseViewModel", "TTS initialized successfully, marking as ready")
                
                // Test TTS immediately after initialization
                Log.d("EchoSenseViewModel", "Testing TTS with sample text")
                speak("Text to speech is now ready")
            }
        } else {
            Log.e("EchoSenseViewModel", "TTS initialization failed with status: $status")
            _error.value = "TTS initialization failed"
            isTtsReady = false
        }
        Log.d("EchoSenseViewModel", "TTS ready state: $isTtsReady")
    }

    private fun speak(text: String) {
        Log.d("EchoSenseViewModel", "speak() called with text: '$text', isTtsReady: $isTtsReady")
        
        if (!isTtsReady) {
            Log.w("EchoSenseViewModel", "TTS not ready, cannot speak: $text")
            return
        }
        
        if (text.isBlank()) {
            Log.w("EchoSenseViewModel", "Empty text provided to speak")
            return
        }
        
        Log.d("EchoSenseViewModel", "Attempting to speak: $text")
        
        // Check if TTS is still available
        if (!::textToSpeech.isInitialized) {
            Log.e("EchoSenseViewModel", "TextToSpeech not initialized")
            return
        }
        
        val result = textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        Log.d("EchoSenseViewModel", "TTS speak result: $result")
        
        if (result == TextToSpeech.ERROR) {
            Log.e("EchoSenseViewModel", "TTS speak failed with ERROR")
        } else if (result == TextToSpeech.SUCCESS) {
            Log.d("EchoSenseViewModel", "TTS speak succeeded")
        } else {
            Log.w("EchoSenseViewModel", "TTS speak returned unexpected result: $result")
        }
    }

    private fun speakAndStopWhenDone(text: String) {
        Log.d("EchoSenseViewModel", "speakAndStopWhenDone() called with text: '$text', isTtsReady: $isTtsReady")
        
        if (!isTtsReady) {
            Log.w("EchoSenseViewModel", "TTS not ready, stopping processing")
            stopProcessing()
            return
        }
        
        if (text.isBlank()) {
            Log.w("EchoSenseViewModel", "Empty text provided to speak, stopping processing")
            stopProcessing()
            return
        }
        
        Log.d("EchoSenseViewModel", "Attempting to speak and then stop: $text")
        
        // Check if TTS is still available
        if (!::textToSpeech.isInitialized) {
            Log.e("EchoSenseViewModel", "TextToSpeech not initialized, stopping processing")
            stopProcessing()
            return
        }
        
        // Set up a listener to stop processing when TTS completes
        val utteranceId = "echosense_${System.currentTimeMillis()}"
        textToSpeech.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d("EchoSenseViewModel", "TTS started for utterance: $utteranceId")
            }
            
            override fun onDone(utteranceId: String?) {
                Log.d("EchoSenseViewModel", "TTS completed for utterance: $utteranceId, stopping processing")
                viewModelScope.launch {
                    stopProcessing()
                }
            }
            
            override fun onError(utteranceId: String?) {
                Log.e("EchoSenseViewModel", "TTS error for utterance: $utteranceId, stopping processing")
                viewModelScope.launch {
                    stopProcessing()
                }
            }
        })
        
        val result = textToSpeech.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Log.d("EchoSenseViewModel", "TTS speak result: $result")
        
        if (result == android.speech.tts.TextToSpeech.ERROR) {
            Log.e("EchoSenseViewModel", "TTS speak failed with ERROR, stopping processing")
            stopProcessing()
        } else if (result == android.speech.tts.TextToSpeech.SUCCESS) {
            Log.d("EchoSenseViewModel", "TTS speak succeeded, will stop when done")
        } else {
            Log.w("EchoSenseViewModel", "TTS speak returned unexpected result: $result, stopping processing")
            stopProcessing()
        }
    }

    private fun startContinuousTts() {
        Log.d("EchoSenseViewModel", "startContinuousTts() called")
        continueTtsWithNewText()
    }
    
    private fun continueTtsWithNewText() {
        if (!_isProcessing.value || !isContinuousTtsActive) {
            Log.d("EchoSenseViewModel", "Processing stopped or continuous TTS not active, stopping TTS continuation")
            return
        }
        
        val currentText = llmTextBuffer.toString()
        val newTextToSpeak = if (currentText.length > totalTextSpoken) {
            currentText.substring(totalTextSpoken).trim()
        } else {
            ""
        }
        
        if (newTextToSpeak.isNotEmpty()) {
            Log.d("EchoSenseViewModel", "Speaking new text: '$newTextToSpeak' (total spoken so far: $totalTextSpoken chars)")
            speakContinuous(newTextToSpeak)
        } else if (llmCompleted) {
            Log.d("EchoSenseViewModel", "No new text and LLM completed, finishing continuous TTS")
            ttsCompleted = true
            isContinuousTtsActive = false
            checkAndStopIfComplete()
        } else {
            Log.d("EchoSenseViewModel", "No new text available yet, waiting for more LLM output")
            // Schedule a check for new text after a short delay
            viewModelScope.launch {
                delay(500) // Wait 500ms and check again
                continueTtsWithNewText()
            }
        }
    }
    
    private fun speakContinuous(text: String) {
        Log.d("EchoSenseViewModel", "speakContinuous() called with text: '$text', isTtsReady: $isTtsReady")
        
        if (!isTtsReady) {
            Log.w("EchoSenseViewModel", "TTS not ready, cannot speak continuous")
            return
        }
        
        if (text.isBlank()) {
            Log.w("EchoSenseViewModel", "Empty text provided to speak continuous")
            continueTtsWithNewText() // Continue checking for new text
            return
        }
        
        Log.d("EchoSenseViewModel", "Attempting to speak continuous: $text")
        
        // Check if TTS is still available
        if (!::textToSpeech.isInitialized) {
            Log.e("EchoSenseViewModel", "TextToSpeech not initialized")
            return
        }
        
        // Update the total text spoken counter
        totalTextSpoken += text.length
        
        // Set up a listener to continue with next text when this completes
        val utteranceId = "echosense_continuous_${System.currentTimeMillis()}"
        textToSpeech.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d("EchoSenseViewModel", "Continuous TTS started for utterance: $utteranceId")
            }
            
            override fun onDone(utteranceId: String?) {
                Log.d("EchoSenseViewModel", "Continuous TTS completed for utterance: $utteranceId")
                viewModelScope.launch {
                    if (llmCompleted) {
                        Log.d("EchoSenseViewModel", "LLM completed, checking for final text")
                        val finalText = llmTextBuffer.toString()
                        val remainingText = if (finalText.length > totalTextSpoken) {
                            finalText.substring(totalTextSpoken).trim()
                        } else {
                            ""
                        }
                        
                        if (remainingText.isNotEmpty()) {
                            Log.d("EchoSenseViewModel", "Speaking final remaining text: '$remainingText'")
                            speakCompleteAndStop(remainingText)
                        } else {
                            Log.d("EchoSenseViewModel", "No remaining text, continuous TTS complete")
                            ttsCompleted = true
                            isContinuousTtsActive = false
                            checkAndStopIfComplete()
                        }
                    } else {
                        Log.d("EchoSenseViewModel", "LLM still generating, continuing TTS with new text")
                        continueTtsWithNewText()
                    }
                }
            }
            
            override fun onError(utteranceId: String?) {
                Log.e("EchoSenseViewModel", "Continuous TTS error for utterance: $utteranceId")
                viewModelScope.launch {
                    if (llmCompleted) {
                        ttsCompleted = true
                        isContinuousTtsActive = false
                        checkAndStopIfComplete()
                    } else {
                        continueTtsWithNewText()
                    }
                }
            }
        })
        
        val result = textToSpeech.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Log.d("EchoSenseViewModel", "Continuous TTS speak result: $result")
        
        if (result == android.speech.tts.TextToSpeech.ERROR) {
            Log.e("EchoSenseViewModel", "Continuous TTS speak failed with ERROR")
            if (llmCompleted) {
                ttsCompleted = true
                isContinuousTtsActive = false
                checkAndStopIfComplete()
            } else {
                continueTtsWithNewText()
            }
        } else if (result == android.speech.tts.TextToSpeech.SUCCESS) {
            Log.d("EchoSenseViewModel", "Continuous TTS speak succeeded")
        } else {
            Log.w("EchoSenseViewModel", "Continuous TTS speak returned unexpected result: $result")
            if (llmCompleted) {
                ttsCompleted = true
                isContinuousTtsActive = false
                checkAndStopIfComplete()
            } else {
                continueTtsWithNewText()
            }
        }
    }

    private fun speakCompleteAndStop(text: String) {
        Log.d("EchoSenseViewModel", "speakCompleteAndStop() called with text: '$text', isTtsReady: $isTtsReady")
        
        if (!isTtsReady) {
            Log.w("EchoSenseViewModel", "TTS not ready, stopping processing")
            stopProcessing()
            return
        }
        
        if (text.isBlank()) {
            Log.w("EchoSenseViewModel", "Empty text provided to speak complete, stopping processing")
            stopProcessing()
            return
        }
        
        Log.d("EchoSenseViewModel", "Attempting to speak complete text and then stop: $text")
        
        // Check if TTS is still available
        if (!::textToSpeech.isInitialized) {
            Log.e("EchoSenseViewModel", "TextToSpeech not initialized, stopping processing")
            stopProcessing()
            return
        }
        
        // Set up a listener to stop processing when TTS completes
        val utteranceId = "echosense_complete_${System.currentTimeMillis()}"
        textToSpeech.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d("EchoSenseViewModel", "Complete TTS started for utterance: $utteranceId")
            }
            
            override fun onDone(utteranceId: String?) {
                Log.d("EchoSenseViewModel", "Complete TTS completed for utterance: $utteranceId, stopping processing")
                viewModelScope.launch {
                    ttsCompleted = true
                    stopProcessing()
                }
            }
            
            override fun onError(utteranceId: String?) {
                Log.e("EchoSenseViewModel", "Complete TTS error for utterance: $utteranceId, stopping processing")
                viewModelScope.launch {
                    ttsCompleted = true
                    stopProcessing()
                }
            }
        })
        
        val result = textToSpeech.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Log.d("EchoSenseViewModel", "Complete TTS speak result: $result")
        
        if (result == android.speech.tts.TextToSpeech.ERROR) {
            Log.e("EchoSenseViewModel", "Complete TTS speak failed with ERROR, stopping processing")
            ttsCompleted = true
            stopProcessing()
        } else if (result == android.speech.tts.TextToSpeech.SUCCESS) {
            Log.d("EchoSenseViewModel", "Complete TTS speak succeeded, will stop when done")
        } else {
            Log.w("EchoSenseViewModel", "Complete TTS speak returned unexpected result: $result, stopping processing")
            ttsCompleted = true
            stopProcessing()
        }
    }

    private fun checkAndStopIfComplete() {
        Log.d("EchoSenseViewModel", "checkAndStopIfComplete() - LLM completed: $llmCompleted, TTS completed: $ttsCompleted")
        if (llmCompleted && ttsCompleted && _isProcessing.value) {
            Log.d("EchoSenseViewModel", "Both LLM and TTS completed, stopping processing")
            stopProcessing()
        }
    }
}

