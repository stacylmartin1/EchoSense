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

package com.google.ai.edge.gallery.ui.home

import com.google.ai.edge.gallery.data.DataStoreRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LlmResponseStyle {
  CONCISE,
  VERBOSE,
}

object AppSettings {
  val llmResponseStyle: MutableStateFlow<LlmResponseStyle> =
    MutableStateFlow(LlmResponseStyle.CONCISE)

  val videoPreviewEnabled: MutableStateFlow<Boolean> = MutableStateFlow(true)

  val textOverlayEnabled: MutableStateFlow<Boolean> = MutableStateFlow(true)
  val showDebugOverlay: MutableStateFlow<Boolean> = MutableStateFlow(false)

  val geminiApiKey: MutableStateFlow<String> = MutableStateFlow("")
  val ttsVoiceName: MutableStateFlow<String> = MutableStateFlow("")

  fun setLlmResponseStyle(style: LlmResponseStyle) {
    llmResponseStyle.value = style
  }

  fun setVideoPreviewEnabled(enabled: Boolean) {
    videoPreviewEnabled.value = enabled
  }

  fun setTextOverlayEnabled(enabled: Boolean) {
    textOverlayEnabled.value = enabled
  }

  fun setShowDebugOverlay(enabled: Boolean) {
    showDebugOverlay.value = enabled
  }

  fun setGeminiApiKey(key: String) {
    geminiApiKey.value = key
  }

  fun setTtsVoiceName(name: String) {
    ttsVoiceName.value = name
  }

  fun loadFrom(repository: DataStoreRepository) {
    val settings = repository.readEchoSenseSettings()
    videoPreviewEnabled.value = settings.videoPreviewEnabled
    textOverlayEnabled.value = settings.textOverlayEnabled
    showDebugOverlay.value = settings.showDebugOverlay
    geminiApiKey.value = settings.geminiApiKey ?: ""
    ttsVoiceName.value = settings.ttsVoiceName ?: ""
    llmResponseStyle.value = when (settings.llmResponseStyle) {
      "verbose" -> LlmResponseStyle.VERBOSE
      else -> LlmResponseStyle.CONCISE
    }
  }

  fun persistTo(repository: DataStoreRepository) {
    repository.saveEchoSenseSettings(
      videoPreviewEnabled = videoPreviewEnabled.value,
      textOverlayEnabled = textOverlayEnabled.value,
      llmResponseStyle = when (llmResponseStyle.value) {
        LlmResponseStyle.CONCISE -> "concise"
        LlmResponseStyle.VERBOSE -> "verbose"
      },
      geminiApiKey = geminiApiKey.value,
      showDebugOverlay = showDebugOverlay.value,
      ttsVoiceName = ttsVoiceName.value
    )
  }

  fun observeLlmResponseStyle(): StateFlow<LlmResponseStyle> = llmResponseStyle
  fun observeVideoPreviewEnabled(): StateFlow<Boolean> = videoPreviewEnabled
  fun observeTextOverlayEnabled(): StateFlow<Boolean> = textOverlayEnabled
  fun observeShowDebugOverlay(): StateFlow<Boolean> = showDebugOverlay
  fun observeGeminiApiKey(): StateFlow<String> = geminiApiKey
  fun observeTtsVoiceName(): StateFlow<String> = ttsVoiceName
}
