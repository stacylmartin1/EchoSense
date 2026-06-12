package com.google.ai.edge.gallery.ui.echosense

import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.createLlmChatConfigs

const val BUNDLED_LLM_ASSET_NAME = "gemma-4-E4B-it.litertlm"
const val BUNDLED_LLM_SIZE = 3_654_467_584L

fun createBundledGemma3nForChat(): Model {
  return Model(
    name = "Gemma 4 4B (Bundled)",
    displayName = "Gemma 4 4B (Embedded)",
    info = "Embedded multimodal model with 4B parameters. Supports text and image input.",
    downloadFileName = BUNDLED_LLM_ASSET_NAME,
    sizeInBytes = BUNDLED_LLM_SIZE,
    localFileRelativeDirPathOverride = "bundled",
    configs = createLlmChatConfigs(
      defaultTopK = 40,
      defaultTopP = 0.95f,
      defaultTemperature = 1.0f,
      // Keep at 4096 — higher values (e.g. 16000) cause GPU OOM on most devices
      // because the KV cache for 4B model scales linearly with token count.
      defaultMaxToken = 4096,
      accelerators = listOf(Accelerator.GPU, Accelerator.CPU)
    ),
    showBenchmarkButton = false,
    showRunAgainButton = false,
    llmSupportImage = true,
    llmSupportAudio = true,
    bestForTaskIds = listOf(
      BuiltInTaskId.NAVIGATION_ASSISTANCE,
//      BuiltInTaskId.COLLISION_AVOIDANCE,
      BuiltInTaskId.CURRENCY_MODE,
      BuiltInTaskId.DOCUMENT_READER,
      BuiltInTaskId.DOCUMENT_TRANSLATOR,
    ),
  )
}
