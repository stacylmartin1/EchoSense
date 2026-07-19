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

import android.content.Context
import com.google.ai.edge.gallery.data.DataStoreRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LlmResponseStyle {
  CONCISE,
  VERBOSE,
}

enum class OnlineProvider(val displayName: String, val storageValue: String) {
  GEMINI("Gemini", "gemini"),
  OPENAI("OpenAI", "openai"),
}

enum class OnlineUsageMode(val displayName: String, val storageValue: String) {
  ASK("Ask before use", "ask"),
  FALLBACK("Automatic fallback", "fallback"),
  PREFER_ONLINE("Prefer online", "prefer_online"),
}

object AppSettings {
  val llmResponseStyle: MutableStateFlow<LlmResponseStyle> =
    MutableStateFlow(LlmResponseStyle.CONCISE)

  val videoPreviewEnabled: MutableStateFlow<Boolean> = MutableStateFlow(true)

  val textOverlayEnabled: MutableStateFlow<Boolean> = MutableStateFlow(true)
  val showDebugOverlay: MutableStateFlow<Boolean> = MutableStateFlow(false)

  val geminiApiKey: MutableStateFlow<String> = MutableStateFlow("")
  val onlineProvider: MutableStateFlow<OnlineProvider> = MutableStateFlow(OnlineProvider.GEMINI)
  val onlineUsageMode: MutableStateFlow<OnlineUsageMode> = MutableStateFlow(OnlineUsageMode.ASK)
  val onlineConsentGranted: MutableStateFlow<Boolean> = MutableStateFlow(false)
  val cloudPromptRequested: MutableStateFlow<Boolean> = MutableStateFlow(false)
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

  fun setTtsVoiceName(name: String) {
    ttsVoiceName.value = name
  }

  fun loadFrom(repository: DataStoreRepository, context: Context) {
    val settings = repository.readEchoSenseSettings()
    videoPreviewEnabled.value = settings.videoPreviewEnabled
    textOverlayEnabled.value = settings.textOverlayEnabled
    showDebugOverlay.value = settings.showDebugOverlay
    var secureKey = CloudCredentialStore.load(context)
    val legacyKey = settings.geminiApiKey.orEmpty()
    if (secureKey.isEmpty() && legacyKey.isNotEmpty()) {
      CloudCredentialStore.save(context, legacyKey)
      secureKey = legacyKey
    }
    geminiApiKey.value = secureKey
    onlineProvider.value =
      OnlineProvider.entries.firstOrNull { it.storageValue == settings.onlineProvider }
        ?: OnlineProvider.GEMINI
    onlineUsageMode.value =
      OnlineUsageMode.entries.firstOrNull { it.storageValue == settings.onlineUsageMode }
        ?: OnlineUsageMode.ASK
    onlineConsentGranted.value = settings.onlineConsentGranted
    ttsVoiceName.value = settings.ttsVoiceName ?: ""
    llmResponseStyle.value = when (settings.llmResponseStyle) {
      "verbose" -> LlmResponseStyle.VERBOSE
      else -> LlmResponseStyle.CONCISE
    }
    if (legacyKey.isNotEmpty()) persistTo(repository)
  }

  fun persistTo(repository: DataStoreRepository) {
    repository.saveEchoSenseSettings(
      videoPreviewEnabled = videoPreviewEnabled.value,
      textOverlayEnabled = textOverlayEnabled.value,
      llmResponseStyle = when (llmResponseStyle.value) {
        LlmResponseStyle.CONCISE -> "concise"
        LlmResponseStyle.VERBOSE -> "verbose"
      },
      geminiApiKey = "",
      showDebugOverlay = showDebugOverlay.value,
      ttsVoiceName = ttsVoiceName.value,
      onlineProvider = onlineProvider.value.storageValue,
      onlineUsageMode = onlineUsageMode.value.storageValue,
      onlineConsentGranted = onlineConsentGranted.value,
    )
  }

  fun isOnlineConnected(): Boolean = geminiApiKey.value.isNotBlank()

  fun maskedOnlineKey(): String =
    if (geminiApiKey.value.isBlank()) "" else "••••${geminiApiKey.value.takeLast(4)}"

  fun saveOnlineConnection(
    context: Context,
    provider: OnlineProvider,
    apiKey: String,
    usageMode: OnlineUsageMode,
  ) {
    val normalized = apiKey.trim()
    require(normalized.isNotEmpty()) { "API key is required" }
    CloudCredentialStore.save(context, normalized)
    geminiApiKey.value = normalized
    onlineProvider.value = provider
    onlineUsageMode.value = usageMode
    if (usageMode != OnlineUsageMode.ASK) onlineConsentGranted.value = true
    dismissOnlinePrompt(context)
  }

  fun removeOnlineConnection(context: Context) {
    CloudCredentialStore.clear(context)
    geminiApiKey.value = ""
    onlineProvider.value = OnlineProvider.GEMINI
    onlineUsageMode.value = OnlineUsageMode.ASK
    onlineConsentGranted.value = false
  }

  fun recordSuccessfulLocalAnalysis(context: Context) {
    if (isOnlineConnected()) return
    val prefs = context.getSharedPreferences("online_analysis_onboarding", Context.MODE_PRIVATE)
    if (!prefs.getBoolean("dismissed", false)) cloudPromptRequested.value = true
  }

  fun beginOnlineSetup() {
    cloudPromptRequested.value = false
  }

  fun dismissOnlinePrompt(context: Context) {
    cloudPromptRequested.value = false
    context.getSharedPreferences("online_analysis_onboarding", Context.MODE_PRIVATE)
      .edit().putBoolean("dismissed", true).apply()
  }

  fun observeLlmResponseStyle(): StateFlow<LlmResponseStyle> = llmResponseStyle
  fun observeVideoPreviewEnabled(): StateFlow<Boolean> = videoPreviewEnabled
  fun observeTextOverlayEnabled(): StateFlow<Boolean> = textOverlayEnabled
  fun observeShowDebugOverlay(): StateFlow<Boolean> = showDebugOverlay
  fun observeGeminiApiKey(): StateFlow<String> = geminiApiKey
  fun observeTtsVoiceName(): StateFlow<String> = ttsVoiceName
}
