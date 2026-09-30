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

package com.terranet.echosense.android.ui.echosense.assistant

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.terranet.echosense.android.data.Model
import com.terranet.echosense.android.ui.echosense.NativeTtsQueuePlayer
import com.terranet.echosense.android.ui.echosense.ModelStatusAnnouncement
import com.terranet.echosense.android.ui.echosense.ModelStatusAnnouncementTracker
import com.terranet.echosense.android.ui.echosense.OnlineAnalysisHelper
import com.terranet.echosense.android.ui.echosense.StreamingSentenceChunker
import com.terranet.echosense.android.ui.echosense.VoiceCommandHelper
import com.terranet.echosense.android.ui.home.AppSettings
import com.terranet.echosense.android.ui.home.OnlineUsageMode
import com.terranet.echosense.android.ui.llmchat.LlmChatModelHelper
import com.terranet.echosense.android.ui.llmchat.LlmModelInstance
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
  private val modelStatusAnnouncements = ModelStatusAnnouncementTracker()
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
    if (_isProcessing.value || _isResetting.value) return
    when (
      modelStatusAnnouncements.nextAnnouncement(
        currentModelName = modelName,
        isModelInstalled = isModelInstalled,
        isModelDownloadInProgress = isModelDownloadInProgress,
        isModelReady = isModelReady,
      )
    ) {
      ModelStatusAnnouncement.READY -> tts.announceStatus("Ready.")
      ModelStatusAnnouncement.LOADING ->
        tts.announceStatus("On-device model loading. Wait for ready.")
      ModelStatusAnnouncement.DOWNLOADING ->
        tts.announceStatus("On-device model download in progress.")
      ModelStatusAnnouncement.MISSING -> tts.announceStatus(
        "Download an on-device model in Settings to enable offline analysis."
      )
      null -> Unit
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
    val message = "No voice command received. Please try again."
    _error.value = message
    tts.announceStatus(message)
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
    tts.announceStatus("Thinking.")
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
    append("You are EchoSense-AI Assistant. Answer helpfully and directly. Maintain the conversation context.\n")
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
      tts.announceStatus("No response was generated. Please try again.")
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
      tts.announceStatus("No response was generated. Please try again.")
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
    tts.announceStatus(message)
  }

  fun stop(announce: Boolean = true) {
    val wasActive = _isProcessing.value || _isListening.value
    turnVersion.incrementAndGet()
    cloudJob?.cancel()
    cloudJob = null
    speechChunker.clear()
    try { (model?.instance as? LlmModelInstance)?.conversation?.cancelProcess() } catch (_: Exception) {}
    tts.stop()
    _isProcessing.value = false
    _isListening.value = false
    if (announce && wasActive) tts.announceStatus("Stopped.")
  }

  fun newChat() {
    val waitForNativeCancellation = localGenerationActive.get()
    conversationResetRequested.set(true)
    _isResetting.value = true
    stop(announce = false)
    _messages.value = emptyList()
    _error.value = null
    _activeModelName.value =
      if (_modelMode.value == AssistantModelMode.ONLINE) AppSettings.onlineProvider.value.displayName
      else model?.name
    clearAttachment()
    attachmentSentToLocalConversation = false
    tts.announceStatus("New chat ready.")
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
        tts.announceStatus("Attachment ready.")
      } catch (e: Exception) {
        val message = "Unable to open the attachment."
        _error.value = message
        tts.announceStatus(message)
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
    stop(announce = false)
    voice.destroy()
    tts.shutdown()
    super.onCleared()
  }

  private data class Attachment(val bitmap: Bitmap?, val text: String)
  companion object { private const val MAX_DOCUMENT_CHARS = 16_000 }
}
