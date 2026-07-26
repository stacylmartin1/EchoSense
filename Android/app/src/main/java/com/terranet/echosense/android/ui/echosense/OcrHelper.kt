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

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrTextLine(
    val text: String,
    val boundingBox: android.graphics.Rect?,
    val confidence: Float,
)

data class StructuredOcrResult(
    val text: String,
    val lines: List<OcrTextLine>,
    val script: String?,
    val confidence: Float,
    val processingTimeMillis: Long,
)

interface OcrBackend {
    val name: String
    suspend fun recognize(bitmap: Bitmap): StructuredOcrResult
}

enum class OcrScriptPreference(val displayName: String) {
    AUTOMATIC("Automatic"),
    LATIN("Latin"),
    CHINESE("Chinese"),
    JAPANESE("Japanese"),
    KOREAN("Korean"),
    DEVANAGARI("Devanagari"),
}

/**
 * On-device OCR using Google ML Kit Text Recognition.
 *
 * Runs all five script recognizers in parallel on the image and returns
 * the best quality result. Includes quality checks to prevent gibberish
 * from script misrecognition (e.g., Latin recognizer misinterpreting Thai).
 *
 * Supported scripts:
 *   Latin  – English, French, Vietnamese, etc.
 *   Chinese – Simplified & Traditional
 *   Japanese – Hiragana, Katakana, Kanji
 *   Korean – Hangul
 *   Devanagari – Hindi, Sanskrit, Marathi
 *
 * Returns an empty string if no text passes quality checks (caller should
 * fall back to Gemini or LLM OCR for unsupported scripts like Thai, Arabic).
 */
object OcrHelper : OcrBackend {

    private const val TAG = "OcrHelper"
    override val name = "Google ML Kit"

    /** Minimum characters for an OCR result to be considered valid. */
    private const val MIN_TEXT_LENGTH = 10

    // Create recognizers lazily — they remain alive for the app lifetime.
    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val chineseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val japaneseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val koreanRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
    private val devanagariRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    private data class RecognizerResult(
        val script: String,
        val visionText: Text?,
        val confidence: Float,
    ) {
        val text: String get() = visionText?.text.orEmpty()
    }

    /**
     * Extract text from a [Bitmap] by running all script recognizers in
     * parallel and returning the best quality result.
     *
     * @return extracted text, or empty string if nothing passed quality checks.
     */
    suspend fun recognizeText(bitmap: Bitmap): String = recognize(bitmap).text

    override suspend fun recognize(bitmap: Bitmap): StructuredOcrResult =
        recognize(bitmap, OcrScriptPreference.AUTOMATIC)

    suspend fun recognize(
        bitmap: Bitmap,
        preference: OcrScriptPreference,
        minimumTextLength: Int = MIN_TEXT_LENGTH,
    ): StructuredOcrResult = coroutineScope {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val image = InputImage.fromBitmap(bitmap, 0)

        val recognizers = when (preference) {
            OcrScriptPreference.AUTOMATIC -> listOf(
                "Latin" to latinRecognizer,
                "Chinese" to chineseRecognizer,
                "Japanese" to japaneseRecognizer,
                "Korean" to koreanRecognizer,
                "Devanagari" to devanagariRecognizer,
            )
            OcrScriptPreference.LATIN -> listOf("Latin" to latinRecognizer)
            OcrScriptPreference.CHINESE -> listOf("Chinese" to chineseRecognizer)
            OcrScriptPreference.JAPANESE -> listOf("Japanese" to japaneseRecognizer)
            OcrScriptPreference.KOREAN -> listOf("Korean" to koreanRecognizer)
            OcrScriptPreference.DEVANAGARI -> listOf("Devanagari" to devanagariRecognizer)
        }
        val results = recognizers.map { (script, recognizer) ->
            async { runRecognizer(script, recognizer, image) }
        }.awaitAll()

        // Filter to results that pass quality checks, then pick best.
        val viable = results.filter {
            it.text.length >= minimumTextLength && passesQualityCheck(it, minimumTextLength)
        }

        if (viable.isEmpty()) {
            val best = results.maxByOrNull { it.text.length }
            Log.d(TAG, "No recognizer passed quality check (best: ${best?.script ?: "none"}, " +
                    "${best?.text?.length ?: 0} chars, conf=${best?.confidence ?: 0f})")
            return@coroutineScope StructuredOcrResult(
                text = "",
                lines = emptyList(),
                script = best?.script,
                confidence = best?.confidence ?: 0f,
                processingTimeMillis = android.os.SystemClock.elapsedRealtime() - startedAt,
            )
        }

        // Among viable results, prefer higher confidence, tie-break by length.
        val best = viable.maxByOrNull { it.confidence * 1000 + it.text.length }!!
        Log.d(TAG, "Best OCR: ${best.script} (${best.text.length} chars, conf=${best.confidence})")
        StructuredOcrResult(
            text = best.text.trim(),
            lines = best.visionText?.textBlocks.orEmpty().flatMap { block ->
                block.lines.map { line ->
                    OcrTextLine(
                        text = line.text,
                        boundingBox = line.boundingBox,
                        confidence = line.elements
                            .map { it.confidence }
                            .filter { it > 0f }
                            .average()
                            .takeUnless { it.isNaN() }
                            ?.toFloat() ?: 0f,
                    )
                }
            },
            script = best.script,
            confidence = best.confidence,
            processingTimeMillis = android.os.SystemClock.elapsedRealtime() - startedAt,
        )
    }

    /**
     * Whether the OCR result is usable. Callers use this to decide
     * whether to fall back to Gemini/LLM OCR.
     */
    fun isUsableResult(text: String): Boolean = text.length >= MIN_TEXT_LENGTH

    // ── Quality checks ──────────────────────────────────────────────────

    /**
     * Detect garbage output from script misrecognition (e.g., Latin recognizer
     * matching Thai script shapes to Latin lookalike characters).
     */
    private fun passesQualityCheck(
        result: RecognizerResult,
        minimumTextLength: Int = MIN_TEXT_LENGTH,
    ): Boolean {
        val text = result.text
        if (text.length < minimumTextLength) return false

        // Check 1: Confidence must be reasonable (ML Kit returns 0..1)
        // If text is very short, require higher confidence to avoid gibberish
        val minConfidence = if (text.length < 15) 0.6f else 0.4f
        if (result.confidence > 0f && result.confidence < minConfidence) {
            Log.d(TAG, "${result.script}: low confidence ${result.confidence}")
            return false
        }

        // Check 2: Script-specific character ratio
        if (!hasExpectedCharacters(result.script, text)) {
            Log.d(TAG, "${result.script}: text doesn't match expected script characters")
            return false
        }

        // Check 3: For Latin text, check that words look real
        if (result.script == "Latin" && !hasReasonableLatinWords(text)) {
            Log.d(TAG, "Latin: detected gibberish word patterns")
            return false
        }

        return true
    }

    /**
     * Check that text contains characters expected for the claimed script.
     * At least 40% of letter/digit characters must belong to the script.
     */
    private fun hasExpectedCharacters(script: String, text: String): Boolean {
        val letterChars = text.filter { it.isLetterOrDigit() }
        if (letterChars.isEmpty()) return false

        val ratio = when (script) {
            "Latin" -> letterChars.count {
                it in '\u0000'..'\u024F' || it.isDigit()
            }.toFloat() / letterChars.length
            "Chinese" -> letterChars.count {
                it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' || it.isDigit()
            }.toFloat() / letterChars.length
            "Japanese" -> letterChars.count {
                it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' ||
                        it in '\u4E00'..'\u9FFF' || it.isDigit()
            }.toFloat() / letterChars.length
            "Korean" -> letterChars.count {
                it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' || it.isDigit()
            }.toFloat() / letterChars.length
            "Devanagari" -> letterChars.count {
                it in '\u0900'..'\u097F' || it.isDigit()
            }.toFloat() / letterChars.length
            else -> 1f
        }

        return ratio >= 0.4f
    }

    /**
     * For Latin-script OCR, check that the text looks like real words
     * rather than gibberish from misrecognized non-Latin scripts.
     *
     * Gibberish indicators:
     * - Very few spaces (Thai rendered as solid Latin wall)
     * - Extremely long "words" (real Latin words are 1-20 chars)
     * - Almost no vowels (consonant soup from misrecognition)
     */
    private fun hasReasonableLatinWords(text: String): Boolean {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return false

        // Average word length (gibberish often produces very long "words")
        val avgWordLen = words.sumOf { it.length }.toFloat() / words.size
        if (avgWordLen > 25) return false

        // Space ratio (real text has spaces; misrecognised Thai often doesn't)
        val spaceRatio = text.count { it.isWhitespace() }.toFloat() / text.length
        if (text.length > 20 && spaceRatio < 0.02f) return false

        // Consonant soup check: Reject texts with long strings of consonants
        if (Regex("(?i)[bcdfghjklmnpqrstvwxz]{7,}").containsMatchIn(text)) {
            Log.d(TAG, "Latin: detected consonant soup")
            return false
        }

        // Vowel ratio (real Latin text has ~30-45% vowels;
        // gibberish from Thai misrecognition has unusual ratios)
        // Only apply vowel check if there are lowercase letters, to allow uppercase acronyms (e.g. "PDF")
        val lowercaseLetters = text.filter { it.isLowerCase() }
        if (lowercaseLetters.isNotEmpty()) {
            val latinLetters = text.filter { it.isLetter() }
            val vowels = latinLetters.count { it.lowercaseChar() in "aeiouy" }
            val vowelRatio = vowels.toFloat() / latinLetters.length
            if (vowelRatio < 0.08f) return false
        }

        return true
    }

    // ── Internal ────────────────────────────────────────────────────────

    private suspend fun runRecognizer(
        script: String,
        recognizer: TextRecognizer,
        image: InputImage
    ): RecognizerResult {
        return try {
            val visionText = recognizeWithRecognizer(recognizer, image)
            val confidence = computeAverageConfidence(visionText)
            RecognizerResult(script, visionText, confidence)
        } catch (e: Exception) {
            Log.w(TAG, "$script recognizer failed: ${e.message}")
            RecognizerResult(script, null, 0f)
        }
    }

    /**
     * Average confidence across all text elements.
     * Returns 0 if ML Kit doesn't provide confidence scores.
     */
    private fun computeAverageConfidence(visionText: Text): Float {
        val blocks = visionText.textBlocks
        if (blocks.isEmpty()) return 0f

        var totalConf = 0f
        var count = 0
        for (block in blocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    totalConf += element.confidence
                    count++
                }
            }
        }
        return if (count > 0) totalConf / count else 0f
    }

    private suspend fun recognizeWithRecognizer(
        recognizer: TextRecognizer,
        image: InputImage
    ): Text = suspendCancellableCoroutine { cont ->
        recognizer.process(image)
            .addOnSuccessListener { visionText -> cont.resume(visionText) }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }
}
