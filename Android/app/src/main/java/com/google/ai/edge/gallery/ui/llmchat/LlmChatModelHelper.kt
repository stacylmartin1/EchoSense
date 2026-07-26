/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.gallery.common.EchoSensePerformanceDiagnostics
import com.google.ai.edge.gallery.common.cleanUpMediapipeTaskErrorMessage
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException

private const val TAG = "AGLlmChatModelHelper"

typealias ResultListener = (partialResult: String, done: Boolean) -> Unit

typealias CleanUpListener = () -> Unit

data class LlmModelInstance(val engine: Engine, var conversation: Conversation)

object LlmChatModelHelper {
  // Indexed by model name.
  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
  fun initialize(
    context: Context,
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = listOf(),
    enableConversationConstrainedDecoding: Boolean = false,
  ) {
    // Prepare options.
    val maxTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
    val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
    val temperature =
      model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
    val accelerator =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    Log.d(TAG, "Initializing...")
    val shouldEnableImage = supportImage
    val shouldEnableAudio = supportAudio
    Log.d(TAG, "Enable image: $shouldEnableImage, enable audio: $shouldEnableAudio")
    val preferredBackend =
      when (accelerator) {
        Accelerator.CPU.label -> Backend.CPU()
        Accelerator.GPU.label -> Backend.GPU()
        else -> Backend.CPU()
      }
    Log.d(TAG, "Preferred backend: $preferredBackend")

    val modelPath = model.getPath(context = context)

    // Validate that the model file actually exists before passing to native engine.
    // Without this check, a missing file causes a native SIGSEGV crash.
    val modelFile = java.io.File(modelPath)
    val initializationTrace =
      EchoSensePerformanceDiagnostics.beginInitialization(
        context = context,
        modelName = model.name,
        modelBytes = modelFile.length(),
        backend = accelerator,
        visionBackend = if (shouldEnableImage) Accelerator.GPU.label else "disabled",
        maxTokens = maxTokens,
        imported = model.imported,
      )
    if (!modelFile.exists()) {
      Log.e(TAG, "Model file does not exist: $modelPath")
      val error = "Model file not found: ${modelFile.name}. Please re-import the model."
      initializationTrace.finish(success = false, error = error)
      onDone(error)
      return
    }
    Log.d(TAG, "Model file verified: $modelPath (${modelFile.length()} bytes)")

    // For imported models, use a dedicated cache directory so LiteRT shards
    // don't pollute __imports/ and stale shards can be cleaned independently.
    val cacheDir = when {
      modelPath.startsWith("/data/local/tmp") ->
        context.getExternalFilesDir(null)?.absolutePath
      model.imported -> {
        val importCacheDir = java.io.File(
          context.getExternalFilesDir(null), "__import_cache/${modelFile.nameWithoutExtension}"
        )
        // Clean stale shards if the model was re-imported (different size)
        if (importCacheDir.exists()) {
          val shards = importCacheDir.listFiles { _, name -> name.endsWith(".bin") }
          if (!shards.isNullOrEmpty()) {
            Log.d(TAG, "Cleaning ${shards.size} stale cache shards for imported model")
            shards.forEach { it.delete() }
          }
        } else {
          importCacheDir.mkdirs()
        }
        importCacheDir.absolutePath
      }
      else -> null
    }

    val engineConfig =
      EngineConfig(
        modelPath = modelPath,
        backend = preferredBackend,
        visionBackend = if (shouldEnableImage) Backend.GPU() else null,
        audioBackend = if (shouldEnableAudio) Backend.CPU() else null,
        maxNumTokens = maxTokens,
        cacheDir = cacheDir,
      )

    // Create an instance of LiteRT LM engine and conversation.
    try {
      val engine = Engine(engineConfig)
      engine.initialize()

      ExperimentalFlags.enableConversationConstrainedDecoding =
        enableConversationConstrainedDecoding
      val conversation =
        engine.createConversation(
          ConversationConfig(
            samplerConfig =
              SamplerConfig(
                topK = topK,
                topP = topP.toDouble(),
                temperature = temperature.toDouble(),
              ),
            systemInstruction = systemInstruction,
            tools = tools,
          )
        )
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      model.instance = LlmModelInstance(engine = engine, conversation = conversation)
    } catch (e: Exception) {
      val error = cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error")
      initializationTrace.finish(success = false, error = error)
      onDone(error)
      return
    }
    initializationTrace.finish(success = true)
    onDone("")
  }

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
  fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = listOf(),
    enableConversationConstrainedDecoding: Boolean = false,
  ) {
    try {
      Log.d(TAG, "Resetting conversation for model '${model.name}'")

      val instance = model.instance as LlmModelInstance? ?: return
      instance.conversation.close()

      val engine = instance.engine
      val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
      val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
      val temperature =
        model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
      val shouldEnableImage = supportImage
      val shouldEnableAudio = supportAudio
      Log.d(TAG, "Enable image: $shouldEnableImage, enable audio: $shouldEnableAudio")

      ExperimentalFlags.enableConversationConstrainedDecoding =
        enableConversationConstrainedDecoding
      val newConversation =
        engine.createConversation(
          ConversationConfig(
            samplerConfig =
              SamplerConfig(
                topK = topK,
                topP = topP.toDouble(),
                temperature = temperature.toDouble(),
              ),
            systemInstruction = systemInstruction,
            tools = tools,
          )
        )
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      instance.conversation = newConversation

      Log.d(TAG, "Resetting done")
    } catch (e: Exception) {
      Log.d(TAG, "Failed to reset conversation", e)
    }
  }

  fun cleanUp(model: Model, onDone: () -> Unit) {
    if (model.instance == null) {
      return
    }

    val instance = model.instance as LlmModelInstance

    try {
      instance.conversation.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the conversation: ${e.message}")
    }

    try {
      instance.engine.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the engine: ${e.message}")
    }

    val onCleanUp = cleanUpListeners.remove(model.name)
    if (onCleanUp != null) {
      onCleanUp()
    }
    model.instance = null

    onDone()
    Log.d(TAG, "Clean up done.")
  }

  fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit = {},
    images: List<Bitmap> = listOf(),
    audioClips: List<ByteArray> = listOf(),
  ) {
    val instance = model.instance as LlmModelInstance
    val inferenceTrace =
      EchoSensePerformanceDiagnostics.beginInference(
        modelName = model.name,
        promptChars = input.length,
        imageDimensions = images.map { it.width to it.height },
        audioBytes = audioClips.sumOf { it.size.toLong() },
      )

    // Set listener.
    if (!cleanUpListeners.containsKey(model.name)) {
      cleanUpListeners[model.name] = cleanUpListener
    }

    val conversation = instance.conversation

    val contents = mutableListOf<Content>()
    val inputPreparationStartedAtMs = SystemClock.elapsedRealtime()
    var encodedImageBytes = 0L
    for (image in images) {
      val imageBytes = image.toPngByteArray()
      encodedImageBytes += imageBytes.size
      contents.add(Content.ImageBytes(imageBytes))
    }
    for (audioClip in audioClips) {
      contents.add(Content.AudioBytes(audioClip))
    }
    // add the text after image and audio for the accurate last token
    if (input.trim().isNotEmpty()) {
      contents.add(Content.Text(input))
    }
    inferenceTrace.inputsPrepared(
      encodedImageBytes = encodedImageBytes,
      elapsedMs = SystemClock.elapsedRealtime() - inputPreparationStartedAtMs,
    )

    try {
      conversation.sendMessageAsync(
        Contents.of(contents),
        object : MessageCallback {
          override fun onMessage(message: Message) {
            val text = message.toString()
            inferenceTrace.onOutput(text)
            resultListener(text, false)
          }

          override fun onDone() {
            inferenceTrace.finish(outcome = "completed")
            resultListener("", true)
          }

          override fun onError(throwable: Throwable) {
            if (throwable is CancellationException) {
              Log.i(TAG, "The inference is cancelled.")
              inferenceTrace.finish(outcome = "cancelled")
              resultListener("", true)
            } else {
              Log.e(TAG, "onError", throwable)
              inferenceTrace.finish(outcome = "error", error = throwable.message)
              onError("Error: ${throwable.message}")
            }
          }
        },
      )
    } catch (error: Throwable) {
      inferenceTrace.finish(outcome = "launchError", error = error.message)
      throw error
    }
  }

  private fun Bitmap.toPngByteArray(): ByteArray {
    val stream = ByteArrayOutputStream()
    this.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }
}
