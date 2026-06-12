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

package com.google.ai.edge.gallery.ui.echosense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamingSentenceChunkerTest {

    private lateinit var chunker: StreamingSentenceChunker

    @Before
    fun setUp() {
        chunker = StreamingSentenceChunker()
    }

    @Test
    fun `token at a time produces correct sentences`() {
        val text = "There is a chair ahead. Watch your step. "
        for (char in text) {
            chunker.onToken(char.toString())
        }
        // "There is a chair ahead." is 23 chars >= 20, should be emitted
        val first = chunker.pollSentence()
        assertEquals("There is a chair ahead.", first)

        // "Watch your step." is 16 chars < 20, still buffered in pendingShort
        // Not emitted yet because it's below minimum chunk size
        assertFalse(chunker.hasQueuedSentences())

        // Call onDone to flush
        chunker.onDone()
        val second = chunker.pollSentence()
        assertEquals("Watch your step.", second)
    }

    @Test
    fun `abbreviations do not cause false splits`() {
        val text = "Dr. Smith is here. "
        chunker.onToken(text)
        // "Dr." should NOT split; "Dr. Smith is here." (18 chars) is below 20 min
        // No complete sentence above min chunk size from the queue
        // Let's call onDone to flush
        chunker.onDone()
        val result = chunker.pollSentence()
        assertEquals("Dr. Smith is here.", result)
    }

    @Test
    fun `numbered lists do not cause false splits`() {
        val text = "1. First item on the left side. 2. Second item on the right. "
        chunker.onToken(text)
        // "1." should not split. The real boundary is after "left side."
        val first = chunker.pollSentence()
        assertEquals("1. First item on the left side.", first)

        chunker.onDone()
        val second = chunker.pollSentence()
        assertEquals("2. Second item on the right.", second)
    }

    @Test
    fun `onDone flushes incomplete trailing text`() {
        chunker.onToken("This text has no ending punctuation")
        assertFalse(chunker.hasQueuedSentences())

        chunker.onDone()
        assertTrue(chunker.hasQueuedSentences())
        val result = chunker.pollSentence()
        assertEquals("This text has no ending punctuation", result)
    }

    @Test
    fun `long text produces multiple chunks`() {
        // Build a long text with multiple sentences
        val sentence = "This is a reasonably long sentence that should exceed the minimum chunk size easily. "
        val longText = sentence.repeat(50)  // ~4250 chars
        chunker.onToken(longText)
        chunker.onDone()

        val chunks = mutableListOf<String>()
        while (chunker.hasQueuedSentences()) {
            chunker.pollSentence()?.let { chunks.add(it) }
        }

        assertTrue("Should produce multiple chunks", chunks.size > 1)
        // Verify all text is accounted for (join and compare stripped)
        val rejoined = chunks.joinToString(" ")
        // Each chunk should be non-empty
        for (chunk in chunks) {
            assertTrue("Chunk should not be empty", chunk.isNotEmpty())
        }
    }

    @Test
    fun `minimum chunk size batches short sentences`() {
        // "OK." is 3 chars, well below minimum of 20
        chunker.onToken("OK. Sure thing. Absolutely right on that point. ")
        // "OK." (3 chars) and "Sure thing." (11 chars) are both short, should batch
        // Together "OK. Sure thing." = 15 chars, still < 20
        // Adding "Absolutely right on that point." (30 chars) makes 46 chars total >= 20

        val first = chunker.pollSentence()
        assertTrue(
            "Short sentences should be batched together",
            first != null && first.length >= 20
        )
    }

    @Test
    fun `clear resets all state`() {
        chunker.onToken("Some text in the buffer right now. ")
        assertTrue(chunker.hasQueuedSentences())

        chunker.clear()
        assertFalse(chunker.hasQueuedSentences())
        assertNull(chunker.pollSentence())

        // Should work fresh after clear
        chunker.onToken("Fresh start with a new sentence. ")
        assertTrue(chunker.hasQueuedSentences())
    }

    @Test
    fun `exclamation and question marks are boundaries`() {
        chunker.onToken("Watch out for the obstacle! Are you okay? ")
        val first = chunker.pollSentence()
        assertEquals("Watch out for the obstacle!", first)

        chunker.onDone()
        val second = chunker.pollSentence()
        assertEquals("Are you okay?", second)
    }

    @Test
    fun `semicolons and colons are boundaries`() {
        chunker.onToken("First part of description; second part follows. ")
        val first = chunker.pollSentence()
        assertEquals("First part of description;", first)

        chunker.onDone()
        val second = chunker.pollSentence()
        assertEquals("second part follows.", second)
    }

    @Test
    fun `punctuation at end of buffer waits for more tokens`() {
        chunker.onToken("This is a sentence.")
        // Period at end of buffer, no whitespace after — should NOT emit yet
        assertFalse(chunker.hasQueuedSentences())

        // Now add whitespace confirming the boundary
        chunker.onToken(" Next sentence starts here. ")
        assertTrue(chunker.hasQueuedSentences())
        val first = chunker.pollSentence()
        assertEquals("This is a sentence.", first)
    }

    @Test
    fun `empty tokens are handled gracefully`() {
        chunker.onToken("")
        chunker.onToken("")
        chunker.onToken("Hello world. ")
        assertFalse(chunker.hasQueuedSentences())  // "Hello world." is 12 chars < 20
        chunker.onDone()
        assertEquals("Hello world.", chunker.pollSentence())
    }

    @Test
    fun `multiple onDone calls are safe`() {
        chunker.onToken("Test sentence here. ")
        chunker.onDone()
        chunker.onDone()  // Should not crash or produce extra items
        val result = chunker.pollSentence()
        assertEquals("Test sentence here.", result)
        assertNull(chunker.pollSentence())
    }

    @Test
    fun `Mrs and Mr abbreviations are handled`() {
        chunker.onToken("Mrs. Johnson lives on St. Main Avenue. ")
        // "Mrs." and "St." should not cause splits
        val result = chunker.pollSentence()
        assertEquals("Mrs. Johnson lives on St. Main Avenue.", result)
    }
}
