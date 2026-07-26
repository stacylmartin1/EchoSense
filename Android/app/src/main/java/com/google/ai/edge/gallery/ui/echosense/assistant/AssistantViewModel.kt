package com.google.ai.edge.gallery.ui.echosense.assistant

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.echosense.NativeTtsQueuePlayer
import com.google.ai.edge.gallery.ui.echosense.OnlineAnalysisHelper
import com.google.ai.edge.gallery.ui.echosense.StreamingSentenceChunker
import com.google.ai.edge.gallery.ui.echosense.VoiceCommandHelper
import com.google.ai.edge.gallery.ui.home.AppSettings
import com.google.ai.edge.gallery.ui.home.OnlineUsageMode
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AssistantMessage(val id: Long, val isUser: Boolean, val text: String)

enum class AssistantModelMode {
  ON_DEVICE,
  ONLINE,
}

@HiltViewModel
class AssistantViewModel @Inject constructor(private val app: Application) : AndroidViewModel(app), VoiceCommandHelper.VoiceCommandListener {
  private val ids = AtomicLong()
  private val turnVersion = AtomicLong()
  private val localGenerationActive = AtomicBoolean(false)
  private val conversationResetRequested = AtomicBoolean(false)
  private var model: Model? = null
  private var cloudJob: Job? = null
  private var resetFallbackJob: Job? = null
  private var missingModelAnnounced = false
  private var modelDownloadAnnounced = false
  private var modelLoadingAnnounced = false
  private var modelReadyAnnounced = false
  private var statusAnnouncementModelName: String? = null
  private var documentText = ""
  private var attachmentSentToLocalConversation = false
  private val speechChunker = StreamingSentenceChunker()

  private val _messages = MutableStateFlow<List<AssistantMessage>>(emptyList())
  val messages: StateFlow<List<AssistantMessage>> = _messages
  private val _isProcessing = MutableStateFlow(false)
  val isProcessing: StateFlow<Boolean> = _isProcessing
  private val _isResetting = MutableStateFlow(false)
  val isResetting: StateFlow<Boolean> = _isResetting
  private val _isListening = MutableStateFlow(false)
  val isListening: StateFlow<Boolean> = _isListening
  private val _attachmentName = MutableStateFlow<String?>(null)
  val attachmentName: StateFlow<String?> = _attachmentName
  private val _attachmentBitmap = MutableStateFlow<Bitmap?>(null)
  val attachmentBitmap: StateFlow<Bitmap?> = _attachmentBitmap
  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error
  private val _activeModelName = MutableStateFlow<String?>(null)
  val activeModelName: StateFlow<String?> = _activeModelName
  private val _modelMode = MutableStateFlow(
    if (AppSettings.onlineUsageMode.value == OnlineUsageMode.PREFER_ONLINE &&
      AppSettings.isOnlineConnected()
    ) AssistantModelMode.ONLINE else AssistantModelMode.ON_DEVICE
  )
  val modelMode: StateFlow<AssistantModelMode> = _modelMode

  private val voice = VoiceCommandHelper(app, this)
  private val tts = NativeTtsQueuePlayer(app) { _isProcessing.value = false }

  init { PDFBoxResourceLoader.init(app) }

  fun setModel(value: Model) {
    if (model?.name != value.name) attachmentSentToLocalConversation = false
    model = value
  }

  fun announceStartupModelStatus(
    modelName: String,
    isModelInstalled: Boolean,
    isModelDownloadInProgress: Boolean,
    isModelReady: Boolean,
  ) {
    if (statusAnnouncementModelName != modelName) {
      statusAnnouncementModelName = modelName
      missingModelAnnounced = false
      modelDownloadAnnounced = false
      modelLoadingAnnounced = false
      modelReadyAnnounced = false
    }
    when {
      isModelReady && !modelReadyAnnounced -> {
        modelReadyAnnounced = true
        tts.announceStatus("Ready.")
      }
      isModelInstalled && !modelLoadingAnnounced -> {
        modelLoadingAnnounced = true
        tts.announceStatus("On-device model loading. Wait for ready.")
      }
      isModelDownloadInProgress && !modelDownloadAnnounced -> {
        modelDownloadAnnounced = true
        tts.announceStatus("On-device model download in progress.")
      }
      !isModelInstalled && !isModelDownloadInProgress && !missingModelAnnounced -> {
        missingModelAnnounced = true
        tts.announceStatus(
          "Download an on-device model in Settings to enable offline analysis."
        )
      }
    }
  }

  fun setModelMode(value: AssistantModelMode) {
    if (_isProcessing.value) return
    _modelMode.value = value
    _activeModelName.value =
      if (value == AssistantModelMode.ONLINE) AppSettings.onlineProvider.value.displayName
      else model?.name
  }

  fun toggleVoice() {
    if (_isListening.value) {
      voice.stopListening()
      _isListening.value = false
    } else {
      _error.value = null
      _isListening.value = true
      voice.startListening()
    }
  }

  override fun onVoiceCommand(command: String) {
    _isListening.value = false
    send(command)
  }

  override fun onError(error: Int) {
    _isListening.value = false
    _error.value = "No voice command received"
  }

  fun send(rawText: String) {
    val text = rawText.trim()
    if (text.isEmpty() || _isProcessing.value || _isResetting.value) return
    val user = AssistantMessage(ids.incrementAndGet(), true, text)
    val assistant = AssistantMessage(ids.incrementAndGet(), false, "")
    _messages.value = _messages.value + user + assistant
    _error.value = null
    _isProcessing.value = true
    val version = turnVersion.incrementAndGet()
    tts.stop()
    speechChunker.clear()

    val canCloud = OnlineAnalysisHelper.isAvailable() && AppSettings.onlineConsentGranted.value
    if (_modelMode.value == AssistantModelMode.ONLINE) {
      if (!canCloud) {
        failTurn(assistant.id, "Connect an online provider before using online chat.")
        return
      }
      runCloud(assistant.id, version)
      return
    }
    val localModel = model
    if (localModel?.instance != null) runLocal(localModel, text, assistant.id, version)
    else if (canCloud && AppSettings.onlineUsageMode.value == OnlineUsageMode.FALLBACK) runCloud(assistant.id, version)
    else failTurn(assistant.id, "The on-device model is not ready. Download or select a model in Settings.")
  }

  private fun runLocal(localModel: Model, text: String, assistantId: Long, version: Long) {
    _activeModelName.value = localModel.name
    val context = buildString {
      if (!attachmentSentToLocalConversation && documentText.isNotBlank()) {
        append("Use this attached document as context:\n")
        append(documentText.take(MAX_DOCUMENT_CHARS))
        append("\n\n")
      }
      append(text)
    }
    val images = if (!attachmentSentToLocalConversation) listOfNotNull(_attachmentBitmap.value) else emptyList()
    attachmentSentToLocalConversation = attachmentSentToLocalConversation || images.isNotEmpty() || documentText.isNotBlank()
    val response = StringBuilder()
    try {
      localGenerationActive.set(true)
      LlmChatModelHelper.runInference(
        model = localModel,
        input = context,
        images = images,
        resultListener = { partial, done ->
          if (done) {
            localGenerationActive.set(false)
            resetConversationIfRequested()
          }
          if (turnVersion.get() != version) return@runInference
          if (partial.isNotEmpty()) {
            response.append(partial)
            updateMessage(assistantId, response.toString())
            speechChunker.onToken(partial)
            speakReadyChunks()
          }
          if (done) finishLocalTurn(response.toString(), version)
        },
        cleanUpListener = {},
        onError = { message ->
          localGenerationActive.set(false)
          resetConversationIfRequested()
          val canFallback = OnlineAnalysisHelper.isAvailable() && AppSettings.onlineConsentGranted.value &&
            AppSettings.onlineUsageMode.value == OnlineUsageMode.FALLBACK
          if (turnVersion.get() != version) return@runInference
          if (canFallback) {
            tts.stop()
            speechChunker.clear()
            updateMessage(assistantId, "")
            runCloud(assistantId, version)
          } else {
            failTurn(assistantId, message)
          }
        },
      )
    } catch (e: Exception) {
      localGenerationActive.set(false)
      resetConversationIfRequested()
      failTurn(assistantId, e.message ?: "Unable to start the on-device model")
    }
  }

  private fun runCloud(assistantId: Long, version: Long) {
    _activeModelName.value = AppSettings.onlineProvider.value.displayName
    cloudJob = viewModelScope.launch {
      try {
        val reply = OnlineAnalysisHelper.generate(buildCloudPrompt(assistantId), _attachmentBitmap.value)
        if (turnVersion.get() != version) return@launch
        updateMessage(assistantId, reply)
        finishTurn(reply, version)
      } catch (e: Exception) {
        failTurn(assistantId, e.message ?: "Online assistant failed")
      }
    }
  }

  private fun buildCloudPrompt(assistantId: Long): String = buildString {
    append("You are EchoSense Assistant. Answer helpfully and directly. Maintain the conversation context.\n")
    if (documentText.isNotBlank()) append("Attached document:\n${documentText.take(MAX_DOCUMENT_CHARS)}\n\n")
    append("Conversation:\n")
    _messages.value.filter { it.id != assistantId }.takeLast(12).forEach {
      append(if (it.isUser) "User: " else "Assistant: ").append(it.text).append('\n')
    }
    append("Assistant:")
  }

  private fun updateMessage(id: Long, text: String) {
    _messages.value = _messages.value.map { if (it.id == id) it.copy(text = text) else it }
  }

  private fun finishTurn(response: String, version: Long) {
    if (turnVersion.get() != version) return
    if (response.isBlank()) {
      _isProcessing.value = false
      return
    }
    tts.queueSentence(response)
    tts.markInputComplete()
  }

  private fun finishLocalTurn(response: String, version: Long) {
    if (turnVersion.get() != version) return
    speechChunker.onDone()
    speakReadyChunks()
    if (response.isBlank()) {
      _isProcessing.value = false
      return
    }
    tts.markInputComplete()
  }

  private fun speakReadyChunks() {
    while (true) {
      val sentence = speechChunker.pollSentence() ?: return
      tts.queueSentence(sentence)
    }
  }

  private fun failTurn(id: Long, message: String) {
    updateMessage(id, message)
    _error.value = message
    _isProcessing.value = false
  }

  fun stop() {
    turnVersion.incrementAndGet()
    cloudJob?.cancel()
    cloudJob = null
    speechChunker.clear()
    try { (model?.instance as? LlmModelInstance)?.conversation?.cancelProcess() } catch (_: Exception) {}
    tts.stop()
    _isProcessing.value = false
  }

  fun newChat() {
    val waitForNativeCancellation = localGenerationActive.get()
    conversationResetRequested.set(true)
    _isResetting.value = true
    stop()
    _messages.value = emptyList()
    _error.value = null
    _activeModelName.value =
      if (_modelMode.value == AssistantModelMode.ONLINE) AppSettings.onlineProvider.value.displayName
      else model?.name
    clearAttachment()
    attachmentSentToLocalConversation = false
    if (waitForNativeCancellation) {
      resetFallbackJob?.cancel()
      resetFallbackJob = viewModelScope.launch {
        delay(5_000)
        resetConversationIfRequested()
      }
    } else {
      resetConversationIfRequested()
    }
  }

  private fun resetConversationIfRequested() {
    viewModelScope.launch {
      if (!conversationResetRequested.compareAndSet(true, false)) return@launch
      resetFallbackJob?.cancel()
      resetFallbackJob = null
      model?.takeIf { it.instance != null }?.let {
        withContext(Dispatchers.Default) {
          LlmChatModelHelper.resetConversation(it, supportImage = true, supportAudio = false)
        }
      }
      _isResetting.value = false
    }
  }

  fun clearAttachment() {
    _attachmentName.value = null
    _attachmentBitmap.value = null
    documentText = ""
    attachmentSentToLocalConversation = false
  }

  fun attach(uri: Uri) {
    viewModelScope.launch {
      try {
        val resolver = app.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Attachment"
        val result = withContext(Dispatchers.IO) {
          when {
            mime.startsWith("image/") -> Attachment(BitmapFactory.decodeStream(resolver.openInputStream(uri)), "")
            mime == "application/pdf" -> loadPdf(uri)
            else -> Attachment(null, resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty())
          }
        }
        _attachmentBitmap.value = result.bitmap
        documentText = result.text.take(MAX_DOCUMENT_CHARS)
        _attachmentName.value = name
        attachmentSentToLocalConversation = false
        _error.value = null
      } catch (e: Exception) {
        _error.value = "Unable to open attachment: ${e.message}"
      }
    }
  }

  private fun loadPdf(uri: Uri): Attachment {
    app.contentResolver.openInputStream(uri)?.use { input ->
      PDDocument.load(input).use { doc ->
        val text = PDFTextStripper().getText(doc).trim()
        if (text.isNotEmpty()) return Attachment(null, text)
      }
    }
    val descriptor = app.contentResolver.openFileDescriptor(uri, "r") ?: return Attachment(null, "")
    descriptor.use {
      PdfRenderer(it).use { renderer ->
        renderer.openPage(0).use { page ->
          val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
          page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          return Attachment(bitmap, "")
        }
      }
    }
  }

  override fun onCleared() {
    resetFallbackJob?.cancel()
    stop()
    voice.destroy()
    tts.shutdown()
    super.onCleared()
  }

  private data class Attachment(val bitmap: Bitmap?, val text: String)
  companion object { private const val MAX_DOCUMENT_CHARS = 16_000 }
}
