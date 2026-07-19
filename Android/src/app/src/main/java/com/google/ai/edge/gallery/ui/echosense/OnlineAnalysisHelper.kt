package com.google.ai.edge.gallery.ui.echosense

import android.graphics.Bitmap
import android.util.Base64
import com.google.ai.edge.gallery.ui.home.AppSettings
import com.google.ai.edge.gallery.ui.home.OnlineProvider
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Direct provider adapters for optional, user-authorized online image analysis. */
object OnlineAnalysisHelper {
  private const val TIMEOUT_MS = 60_000

  fun isAvailable(): Boolean = AppSettings.isOnlineConnected()

  suspend fun validateKey(provider: OnlineProvider, apiKey: String) = withContext(Dispatchers.IO) {
    val connection =
      when (provider) {
        OnlineProvider.GEMINI ->
          (URL("https://generativelanguage.googleapis.com/v1beta/models").openConnection() as HttpURLConnection)
            .apply { setRequestProperty("x-goog-api-key", apiKey) }
        OnlineProvider.OPENAI ->
          (URL("https://api.openai.com/v1/models").openConnection() as HttpURLConnection)
            .apply { setRequestProperty("Authorization", "Bearer $apiKey") }
      }
    try {
      configure(connection, readTimeout = 20_000)
      connection.connect()
      requireSuccess(connection)
    } finally {
      connection.disconnect()
    }
  }

  suspend fun analyzeImage(bitmap: Bitmap, prompt: String): String = withContext(Dispatchers.IO) {
    val apiKey = AppSettings.geminiApiKey.value
    require(apiKey.isNotBlank()) { "Online analysis is not connected" }
    when (AppSettings.onlineProvider.value) {
      OnlineProvider.GEMINI -> analyzeGemini(bitmap, prompt, apiKey)
      OnlineProvider.OPENAI -> analyzeOpenAI(bitmap, prompt, apiKey)
    }
  }

  private fun analyzeGemini(bitmap: Bitmap, prompt: String, apiKey: String): String {
    val parts = JSONArray()
      .put(JSONObject().put("text", prompt))
      .put(
        JSONObject().put(
          "inline_data",
          JSONObject()
            .put("mime_type", "image/jpeg")
            .put("data", bitmapToBase64(bitmap)),
        )
      )
    val requestBody = JSONObject().apply {
      put("contents", JSONArray().put(JSONObject().put("parts", parts)))
      put("generationConfig", JSONObject().put("temperature", 0.1).put("maxOutputTokens", 4096))
    }
    val connection =
      URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent")
        .openConnection() as HttpURLConnection
    try {
      configurePost(connection)
      connection.setRequestProperty("x-goog-api-key", apiKey)
      connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }
      requireSuccess(connection)
      val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
      val candidates = response.optJSONArray("candidates") ?: throw IllegalStateException("No response from Gemini")
      if (candidates.length() == 0) throw IllegalStateException("No response from Gemini")
      val parts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
      return (0 until parts.length()).mapNotNull { parts.getJSONObject(it).optString("text").takeIf(String::isNotBlank) }
        .joinToString("\n").ifBlank { throw IllegalStateException("No response from Gemini") }
    } finally {
      connection.disconnect()
    }
  }

  private fun analyzeOpenAI(bitmap: Bitmap, prompt: String, apiKey: String): String {
    val content = JSONArray()
      .put(JSONObject().put("type", "input_text").put("text", prompt))
      .put(
        JSONObject()
          .put("type", "input_image")
          .put("image_url", "data:image/jpeg;base64,${bitmapToBase64(bitmap)}")
      )
    val requestBody = JSONObject().apply {
      put("model", "gpt-5.4-mini")
      put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
      put("max_output_tokens", 4096)
    }
    val connection = URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection
    try {
      configurePost(connection)
      connection.setRequestProperty("Authorization", "Bearer $apiKey")
      connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }
      requireSuccess(connection)
      val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
      val output = response.optJSONArray("output") ?: throw IllegalStateException("No response from OpenAI")
      val text = mutableListOf<String>()
      for (i in 0 until output.length()) {
        val content = output.getJSONObject(i).optJSONArray("content") ?: continue
        for (j in 0 until content.length()) {
          val item = content.getJSONObject(j)
          if (item.optString("type") == "output_text") item.optString("text").takeIf(String::isNotBlank)?.let(text::add)
        }
      }
      return text.joinToString("\n").ifBlank { throw IllegalStateException("No response from OpenAI") }
    } finally {
      connection.disconnect()
    }
  }

  private fun configure(connection: HttpURLConnection, readTimeout: Int = TIMEOUT_MS) {
    connection.connectTimeout = 30_000
    connection.readTimeout = readTimeout
    connection.instanceFollowRedirects = true
  }

  private fun configurePost(connection: HttpURLConnection) {
    configure(connection)
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/json")
  }

  private fun requireSuccess(connection: HttpURLConnection) {
    val code = connection.responseCode
    if (code in 200..299) return
    if (code == 401 || code == 403) throw IllegalArgumentException("The provider rejected this API key")
    val message = connection.errorStream?.bufferedReader()?.use { it.readText() }
      ?.let(::providerErrorMessage)?.takeIf { it.isNotBlank() }
    throw IllegalStateException(message ?: "Online analysis failed (HTTP $code)")
  }

  private fun providerErrorMessage(body: String): String =
    try { JSONObject(body).optJSONObject("error")?.optString("message")?.take(240).orEmpty() }
    catch (_: Exception) { "" }

  private fun bitmapToBase64(bitmap: Bitmap): String {
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, stream)
    return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
  }
}
