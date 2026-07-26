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

package com.terranet.echosense.android.ui.echosense

import com.terranet.echosense.android.data.AllowedModel
import com.terranet.echosense.android.data.BuiltInTaskId

const val ECHOSENSE_E4B_MODEL_ID = "gemma-4-e4b-it"
const val ECHOSENSE_E2B_MODEL_ID = "gemma-4-e2b-it"
val ECHOSENSE_MODEL_IDS = setOf(ECHOSENSE_E4B_MODEL_ID, ECHOSENSE_E2B_MODEL_ID)
private const val ECHOSENSE_E4B_MODEL_SHA256 =
  "f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc"
private const val ECHOSENSE_E2B_MODEL_SHA256 =
  "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42"

/**
 * Canonical model objects shared by every EchoSense task.
 *
 * Model runtime state lives on [com.terranet.echosense.android.data.Model.instance]. Returning the
 * same objects prevents one task from marking a model name initialized while another task's
 * same-named object still has no native instance.
 */
private val sharedEchoSenseGemmaModels by lazy { listOf(
  AllowedModel(
    id = ECHOSENSE_E4B_MODEL_ID,
    displayName = "Gemma 4 E4B",
    version = "1",
    // Keep EchoSense's existing local filename so current installations remain valid.
    filename = "gemma-4-e4b-it.litertlm",
    url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/9695417f248178c63a9f318c6e0c56cb917cb837/gemma-4-E4B-it.litertlm?download=true",
    licenseURL = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
    sizeBytes = 3_654_467_584L,
    sha256 = ECHOSENSE_E4B_MODEL_SHA256,
    supportedPlatforms = listOf("android"),
    minimumAndroidSDK = 31,
    minimumMemoryGB = 8,
    capabilities = listOf("text", "image"),
    recommended = true,
    taskTypes = listOf(
      BuiltInTaskId.NAVIGATION_ASSISTANCE,
      BuiltInTaskId.ASSISTANT,
      BuiltInTaskId.CURRENCY_MODE,
      BuiltInTaskId.DOCUMENT_READER,
      BuiltInTaskId.DOCUMENT_TRANSLATOR,
    ),
  ).toModel(),
  AllowedModel(
    id = ECHOSENSE_E2B_MODEL_ID,
    displayName = "Gemma 4 E2B",
    version = "1",
    filename = "gemma-4-e2b-it.litertlm",
    url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db/gemma-4-E2B-it.litertlm?download=true",
    licenseURL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
    sizeBytes = 2_583_085_056L,
    sha256 = ECHOSENSE_E2B_MODEL_SHA256,
    supportedPlatforms = listOf("android"),
    minimumAndroidSDK = 31,
    minimumMemoryGB = 6,
    capabilities = listOf("text", "image"),
    recommended = false,
    taskTypes = listOf(
      BuiltInTaskId.NAVIGATION_ASSISTANCE,
      BuiltInTaskId.ASSISTANT,
      BuiltInTaskId.CURRENCY_MODE,
      BuiltInTaskId.DOCUMENT_READER,
      BuiltInTaskId.DOCUMENT_TRANSLATOR,
    ),
  ).toModel(),
) }

/** Returns a new list container backed by the canonical shared model objects. */
fun createEchoSenseGemmaModels() = sharedEchoSenseGemmaModels.toList()
