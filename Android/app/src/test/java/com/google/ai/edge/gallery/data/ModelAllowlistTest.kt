package com.google.ai.edge.gallery.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAllowlistTest {
  private val validManifest =
    """
      {
        "schemaVersion": 1,
        "models": [{
          "id": "gemma-4-e4b-it",
          "displayName": "Gemma 4 E4B",
          "version": "1",
          "filename": "gemma-4-e4b-it.litertlm",
          "url": "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/9695417f248178c63a9f318c6e0c56cb917cb837/gemma-4-E4B-it.litertlm?download=true",
          "licenseURL": "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
          "sizeBytes": 3654467584,
          "sha256": "f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc",
          "supportedPlatforms": ["ios", "android"],
          "minimumAndroidSDK": 31,
          "minimumMemoryGB": 6,
          "capabilities": ["text", "image"],
          "recommended": true
        }]
      }
    """.trimIndent()

  @Test
  fun `production manifest schema creates downloadable Android model`() {
    val catalog = Gson().fromJson(validManifest, ModelAllowlist::class.java)
    val model = catalog.models.single().toModel()

    assertEquals(1, catalog.schemaVersion)
    assertEquals("gemma-4-e4b-it", model.name)
    assertEquals(3_654_467_584L, model.sizeInBytes)
    assertTrue(model.llmSupportImage)
    assertTrue(model.configs.isNotEmpty())
    assertTrue(model.bestForTaskIds.contains(BuiltInTaskId.NAVIGATION_ASSISTANCE))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `placeholder checksum is rejected`() {
    val descriptor = Gson().fromJson(validManifest, ModelAllowlist::class.java).models.single()
    descriptor.copy(sha256 = "REAL_SHA256").toModel()
  }
}
