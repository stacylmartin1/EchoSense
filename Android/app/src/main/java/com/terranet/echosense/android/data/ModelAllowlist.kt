/*
 * Copyright 2025 TerraNet Technologies LLC
 * Licensed under the Apache License, Version 2.0.
 */

package com.terranet.echosense.android.data

data class DefaultConfig(
  val topK: Int? = null,
  val topP: Float? = null,
  val temperature: Float? = null,
  val accelerators: String? = null,
  val maxTokens: Int? = null,
)

/** Cross-platform model descriptor used for test or imported catalogs. */
data class AllowedModel(
  val id: String,
  val displayName: String,
  val version: String,
  val filename: String,
  val url: String,
  val licenseURL: String,
  val sizeBytes: Long,
  val sha256: String,
  val supportedPlatforms: List<String> = emptyList(),
  val minimumAndroidSDK: Int? = null,
  val minimumMemoryGB: Int? = null,
  val capabilities: List<String> = emptyList(),
  val recommended: Boolean = false,
  val defaultConfig: DefaultConfig? = null,
  val taskTypes: List<String>? = null,
) {
  fun validateForAndroid() {
    require(id.isNotBlank() && version.isNotBlank() && filename.isNotBlank()) {
      "Model id, version, and filename are required"
    }
    require(url.startsWith("https://") && licenseURL.startsWith("https://")) {
      "Model and license URLs must use HTTPS"
    }
    require(sizeBytes > 0) { "Model size must be positive" }
    require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Model SHA-256 is invalid" }
    require(supportedPlatforms.contains("android")) { "Model does not support Android" }
  }

  fun toModel(): Model {
    validateForAndroid()
    val config = defaultConfig
    val accelerators =
      config?.accelerators?.split(",")?.mapNotNull {
        when (it.trim()) {
          "cpu" -> Accelerator.CPU
          "gpu" -> Accelerator.GPU
          else -> null
        }
      }?.ifEmpty { null } ?: DEFAULT_ACCELERATORS
    val taskIds =
      taskTypes ?: listOf(
        BuiltInTaskId.NAVIGATION_ASSISTANCE,
        BuiltInTaskId.CURRENCY_MODE,
        BuiltInTaskId.DOCUMENT_READER,
        BuiltInTaskId.DOCUMENT_TRANSLATOR,
      )

    return Model(
      name = id,
      displayName = displayName,
      info = "Downloaded on-device multimodal model. Supports text and image input.",
      url = url,
      version = version,
      downloadFileName = filename,
      sizeInBytes = sizeBytes,
      sha256 = sha256.lowercase(),
      licenseUrl = licenseURL,
      minDeviceMemoryInGb = minimumMemoryGB,
      configs = createLlmChatConfigs(
        defaultTopK = config?.topK ?: 40,
        defaultTopP = config?.topP ?: 0.95f,
        defaultTemperature = config?.temperature ?: 1.0f,
        defaultMaxToken = config?.maxTokens ?: 4096,
        accelerators = accelerators,
      ),
      showBenchmarkButton = false,
      showRunAgainButton = false,
      llmSupportImage = capabilities.contains("image"),
      llmSupportAudio = capabilities.contains("audio"),
      bestForTaskIds = taskIds,
      learnMoreUrl = licenseURL,
    )
  }
}

data class ModelAllowlist(
  val schemaVersion: Int,
  val models: List<AllowedModel>,
)
