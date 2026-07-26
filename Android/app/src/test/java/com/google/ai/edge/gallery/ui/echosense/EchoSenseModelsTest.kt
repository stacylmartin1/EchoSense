package com.google.ai.edge.gallery.ui.echosense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoSenseModelsTest {
  @Test
  fun `official Gemma choices use pinned public downloads`() {
    val models = createEchoSenseGemmaModels()

    assertEquals(setOf(ECHOSENSE_E4B_MODEL_ID, ECHOSENSE_E2B_MODEL_ID), models.map { it.name }.toSet())
    assertEquals(2, models.map { it.url }.distinct().size)
    models.forEach { model ->
      assertTrue(model.url.startsWith("https://huggingface.co/litert-community/"))
      assertTrue(model.url.contains("/resolve/"))
      assertTrue(model.url.endsWith("?download=true"))
      assertEquals(64, model.sha256.length)
      assertTrue(model.sizeInBytes > 2_000_000_000L)
    }
  }

  @Test
  fun `all tasks receive the same canonical model runtime objects`() {
    val firstTaskModels = createEchoSenseGemmaModels()
    val secondTaskModels = createEchoSenseGemmaModels()

    assertSame(firstTaskModels[0], secondTaskModels[0])
    assertSame(firstTaskModels[1], secondTaskModels[1])
  }
}
