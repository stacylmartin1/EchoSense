package com.google.ai.edge.gallery.ui.echosense

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.ai.edge.gallery.ui.home.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Helper for calling the Gemini REST API with a user-provided API key (BYOK).
 *
 * Uses `gemini-2.0-flash` (free tier: 1,500 requests/day).
 * No Firebase or external SDK needed — just HTTP calls.
 */
object GeminiHelper {

    private const val TAG = "GeminiHelper"
    private const val MODEL = "gemini-3-flash-preview"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    private const val TIMEOUT_MS = 30_000

    /**
     * Whether a Gemini API key is configured.
     */
    fun isAvailable(): Boolean = AppSettings.geminiApiKey.value.isNotBlank()

    /**
     * Extract text from a [Bitmap] using Gemini's vision capabilities.
     * Returns the recognized text, or throws on failure.
     */
    suspend fun recognizeText(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val apiKey = AppSettings.geminiApiKey.value
        require(apiKey.isNotBlank()) { "Gemini API key is not set" }

        val base64Image = bitmapToBase64(bitmap)

        val requestBody = buildJsonRequest(
            prompt = "Extract all text from this image exactly as written. " +
                    "Preserve the original language and formatting. " +
                    "Return only the extracted text, nothing else. " +
                    "If no text is found, return an empty string.",
            base64Image = base64Image,
            mimeType = "image/jpeg"
        )

        val responseText = callGeminiApi(apiKey, requestBody)
        Log.d(TAG, "Gemini OCR extracted ${responseText.length} chars")
        responseText
    }

    /**
     * General-purpose Gemini vision query (can be used for scene understanding,
     * currency detection, etc. in the future).
     */
    suspend fun analyzeImage(bitmap: Bitmap, prompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = AppSettings.geminiApiKey.value
        require(apiKey.isNotBlank()) { "Gemini API key is not set" }

        val base64Image = bitmapToBase64(bitmap)
        val requestBody = buildJsonRequest(prompt, base64Image, "image/jpeg")
        callGeminiApi(apiKey, requestBody)
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun buildJsonRequest(prompt: String, base64Image: String, mimeType: String): String {
        val request = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        // Text prompt
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                        // Image data
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", mimeType)
                                put("data", base64Image)
                            })
                        })
                    })
                })
            })
            // Generation config
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 4096)
            })
        }
        return request.toString()
    }

    private fun callGeminiApi(apiKey: String, requestBody: String): String {
        val url = URL("$BASE_URL/$MODEL:generateContent?key=$apiKey")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true

            // Send request
            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "Gemini API error ($responseCode): $errorBody")
                throw RuntimeException("Gemini API error ($responseCode): ${parseErrorMessage(errorBody)}")
            }

            // Parse response
            val responseBody = connection.inputStream.bufferedReader().readText()
            return parseGeminiResponse(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseGeminiResponse(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            val candidates = json.getJSONArray("candidates")
            if (candidates.length() == 0) return ""

            val content = candidates.getJSONObject(0).getJSONObject("content")
            val parts = content.getJSONArray("parts")

            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val text = parts.getJSONObject(i).optString("text", "")
                if (text.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(text)
                }
            }
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gemini response: ${e.message}")
            throw RuntimeException("Failed to parse Gemini response: ${e.message}")
        }
    }

    private fun parseErrorMessage(errorBody: String): String {
        return try {
            val json = JSONObject(errorBody)
            val error = json.getJSONObject("error")
            error.optString("message", "Unknown error")
        } catch (_: Exception) {
            errorBody.take(200)
        }
    }
}
