package com.google.ai.edge.gallery.ui.echosense

import com.google.ai.edge.gallery.data.AllowedModel
import com.google.ai.edge.gallery.data.BuiltInTaskId

const val ECHOSENSE_MODEL_ID = "gemma-4-e4b-it"
private const val ECHOSENSE_MODEL_SHA256 =
  "f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc"

/** Small offline catalog fallback. The LLM itself is never packaged in the application. */
fun createEchoSenseGemmaModel() =
  AllowedModel(
    id = ECHOSENSE_MODEL_ID,
    displayName = "Gemma 4 E4B",
    version = "1",
    filename = "gemma-4-e4b-it.litertlm",
    url = "https://models.echosense-ai.app/v1/gemma-4-e4b-it.litertlm",
    licenseURL = "https://models.echosense-ai.app/v1/LICENSE.txt",
    sizeBytes = 3_654_467_584L,
    sha256 = ECHOSENSE_MODEL_SHA256,
    supportedPlatforms = listOf("android"),
    minimumAndroidSDK = 31,
    minimumMemoryGB = 6,
    capabilities = listOf("text", "image"),
    recommended = true,
    taskTypes = listOf(
      BuiltInTaskId.NAVIGATION_ASSISTANCE,
      BuiltInTaskId.CURRENCY_MODE,
      BuiltInTaskId.DOCUMENT_READER,
      BuiltInTaskId.DOCUMENT_TRANSLATOR,
    ),
  ).toModel()
