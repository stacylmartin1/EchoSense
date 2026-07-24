package com.google.ai.edge.gallery.ui.echosense

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
object OcrHelper {

    private const val TAG = "OcrHelper"

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

    private data class OcrResult(
        val script: String,
        val text: String,
        val confidence: Float   // average element-level confidence, 0..1
    )

    /**
     * Extract text from a [Bitmap] by running all script recognizers in
     * parallel and returning the best quality result.
     *
     * @return extracted text, or empty string if nothing passed quality checks.
     */
    suspend fun recognizeText(bitmap: Bitmap): String = coroutineScope {
        val image = InputImage.fromBitmap(bitmap, 0)

        val results = listOf(
            async { runRecognizer("Latin", latinRecognizer, image) },
            async { runRecognizer("Chinese", chineseRecognizer, image) },
            async { runRecognizer("Japanese", japaneseRecognizer, image) },
            async { runRecognizer("Korean", koreanRecognizer, image) },
            async { runRecognizer("Devanagari", devanagariRecognizer, image) },
        ).awaitAll()

        // Filter to results that pass quality checks, then pick best.
        val viable = results.filter {
            it.text.length >= MIN_TEXT_LENGTH && passesQualityCheck(it)
        }

        if (viable.isEmpty()) {
            val best = results.maxByOrNull { it.text.length } ?: OcrResult("none", "", 0f)
            Log.d(TAG, "No recognizer passed quality check (best: ${best.script}, " +
                    "${best.text.length} chars, conf=${best.confidence})")
            return@coroutineScope ""
        }

        // Among viable results, prefer higher confidence, tie-break by length.
        val best = viable.maxByOrNull { it.confidence * 1000 + it.text.length }!!
        Log.d(TAG, "Best OCR: ${best.script} (${best.text.length} chars, conf=${best.confidence})")
        best.text
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
    private fun passesQualityCheck(result: OcrResult): Boolean {
        val text = result.text
        if (text.length < MIN_TEXT_LENGTH) return false

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
    ): OcrResult {
        return try {
            val visionText = recognizeWithRecognizer(recognizer, image)
            val confidence = computeAverageConfidence(visionText)
            OcrResult(script, visionText.text, confidence)
        } catch (e: Exception) {
            Log.w(TAG, "$script recognizer failed: ${e.message}")
            OcrResult(script, "", 0f)
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
