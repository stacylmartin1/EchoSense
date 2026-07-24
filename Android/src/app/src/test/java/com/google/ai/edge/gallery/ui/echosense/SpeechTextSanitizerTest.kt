package com.google.ai.edge.gallery.ui.echosense

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextSanitizerTest {
  @Test
  fun removesMarkdownFormattingWhileKeepingWords() {
    val input = """
      ## What I found
      - **Keys:** Try the *table*.
      - See [the guide](https://example.com).
    """.trimIndent()

    assertEquals(
      "What I found. Keys: Try the table. See the guide.",
      SpeechTextSanitizer.sanitize(input),
    )
  }

  @Test
  fun keepsHyphensInsideWords() {
    assertEquals(
      "Use the on-device model.",
      SpeechTextSanitizer.sanitize("Use the on-device model."),
    )
  }

  @Test
  fun removesCodeAndTablePunctuation() {
    val input = "```kotlin\nval answer = 4\n```\nName | Value"

    assertEquals("val answer = 4. Name , Value", SpeechTextSanitizer.sanitize(input))
  }
}
