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

package com.terranet.echosense.android.ui.echosense

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Accumulates streaming LLM tokens and emits complete sentences via a thread-safe queue.
 *
 * Sentence boundary detection rules:
 * - Splits on `.` `!` `?` `;` `:` followed by whitespace (confirmed boundary)
 * - Skips common abbreviations (Mr., Dr., St., etc.) and numbered lists (1., 2.)
 * - Batches short sentences (<20 chars) with the next sentence to avoid choppy TTS
 * - Uses [ConcurrentLinkedQueue] for thread safety between coroutine and TTS callback threads
 */
class StreamingSentenceChunker {

    private val buffer = StringBuilder()
    private val sentenceQueue = ConcurrentLinkedQueue<String>()
    private var pendingShort = StringBuilder()

    companion object {
        private const val MIN_CHUNK_SIZE = 20

        /** Common abbreviations that end with a period but are NOT sentence boundaries. */
        private val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "ave", "blvd",
            "dept", "est", "govt", "inc", "corp", "ltd", "co", "vs", "etc",
            "approx", "ft", "vol", "no", "fig", "ref", "gen", "sgt", "cpl",
            "pvt", "lt", "col", "capt", "maj", "cmdr", "adm",
        )

        /** Regex pattern for numbered list items like "1." "12." */
        private val NUMBERED_LIST_PATTERN = Regex("""^\d+$""")
    }

    /**
     * Feed a new token (partial text) from the LLM. Any complete sentences detected
     * will be enqueued for consumption.
     */
    fun onToken(token: String) {
        buffer.append(token)
        extractSentences()
    }

    /**
     * Signal that the LLM is done generating. Flushes any remaining buffered text
     * as the final sentence.
     */
    fun onDone() {
        // Flush whatever remains in the buffer
        val remaining = buffer.toString().trim()
        buffer.clear()
        if (remaining.isNotEmpty()) {
            enqueueChunk(remaining)
        }
        // Flush any pending short text
        if (pendingShort.isNotEmpty()) {
            sentenceQueue.add(pendingShort.toString().trim())
            pendingShort.clear()
        }
    }

    /** Returns the next queued sentence, or null if none are ready. */
    fun pollSentence(): String? = sentenceQueue.poll()

    /** Returns true if there are sentences ready to be consumed. */
    fun hasQueuedSentences(): Boolean = sentenceQueue.isNotEmpty()

    /** Resets all state for a new session. */
    fun clear() {
        buffer.clear()
        sentenceQueue.clear()
        pendingShort.clear()
    }

    /**
     * Scans the buffer for sentence boundaries and moves complete sentences to the queue.
     */
    private fun extractSentences() {
        while (true) {
            val text = buffer.toString()
            val boundary = findSentenceBoundary(text) ?: break
            val sentence = text.substring(0, boundary).trim()
            buffer.delete(0, boundary)

            if (sentence.isNotEmpty()) {
                enqueueChunk(sentence)
            }
        }
    }

    /**
     * Handles minimum chunk size batching. Short sentences are held in [pendingShort]
     * and combined with the next sentence to avoid choppy TTS output.
     */
    private fun enqueueChunk(sentence: String) {
        pendingShort.append(if (pendingShort.isNotEmpty()) " " else "")
        pendingShort.append(sentence)
        if (pendingShort.length >= MIN_CHUNK_SIZE) {
            sentenceQueue.add(pendingShort.toString().trim())
            pendingShort.clear()
        }
    }

    /**
     * Finds the index of the first confirmed sentence boundary in [text].
     * Returns the index just past the boundary (i.e., the start of the next sentence),
     * or null if no boundary is found.
     *
     * A boundary is a sentence-ending punctuation mark (`.!?;:`) followed by whitespace.
     */
    private fun findSentenceBoundary(text: String): Int? {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '.' || ch == '!' || ch == '?' || ch == ';' || ch == ':') {
                // Check if there's a character after the punctuation
                val nextIdx = i + 1
                if (nextIdx >= text.length) {
                    // Punctuation at end of buffer — not confirmed yet (more tokens may come)
                    return null
                }
                val nextChar = text[nextIdx]
                if (nextChar.isWhitespace()) {
                    // Potential boundary — check for false positives
                    if (ch == '.' && isAbbreviationOrNumber(text, i)) {
                        i++
                        continue
                    }
                    // Confirmed sentence boundary — return index after the whitespace
                    return nextIdx + 1
                }
            }
            i++
        }
        return null
    }

    /**
     * Checks whether the period at [dotIndex] in [text] is part of an abbreviation
     * (e.g., "Mr.", "Dr.") or a numbered list item (e.g., "1.", "12.").
     */
    private fun isAbbreviationOrNumber(text: String, dotIndex: Int): Boolean {
        // Extract the word before the dot
        var start = dotIndex - 1
        while (start >= 0 && text[start].isLetterOrDigit()) {
            start--
        }
        start++ // move to first char of the word

        if (start >= dotIndex) return false // no word before dot
        val wordBeforeDot = text.substring(start, dotIndex)

        // Check numbered list: "1.", "23."
        if (NUMBERED_LIST_PATTERN.matches(wordBeforeDot)) return true

        // Check abbreviation
        return wordBeforeDot.lowercase() in ABBREVIATIONS
    }
}
