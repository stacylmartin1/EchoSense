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

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device OCR using Tesseract 5 via Tesseract4Android.
 *
 * Covers scripts that ML Kit does not support:
 *   Thai, Khmer, Lao, Burmese, Arabic
 *
 * Trained-data files are bundled in `assets/tessdata/` and copied to the
 * app's internal storage on first use. The [TessBaseAPI] instance is reused
 * across calls (guarded by a [Mutex] since it is not thread-safe).
 */
object TesseractOcrHelper {

    private const val TAG = "TesseractOcrHelper"

    /** All Tesseract language codes we ship, joined for multi-language init. */
    private const val LANGUAGES = "tha+khm+lao+mya+ara"

    /** Individual language files shipped in assets/tessdata/. */
    private val TRAINED_DATA_FILES = listOf(
        "tha.traineddata",
        "khm.traineddata",
        "lao.traineddata",
        "mya.traineddata",
        "ara.traineddata",
    )

    /** The parent directory that contains the `tessdata/` subfolder. */
    private var dataPath: String? = null

    private var tessApi: TessBaseAPI? = null
    private val mutex = Mutex()
    private var initialized = false

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Recognize text from a [Bitmap].
     *
     * Thread-safe — only one recognition runs at a time.  The first call
     * triggers asset extraction and Tesseract initialization.
     *
     * @return extracted text, or empty string on failure.
     */
    suspend fun recognizeText(context: Context, bitmap: Bitmap): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                ensureInitialized(context)

                val api = tessApi ?: run {
                    Log.e(TAG, "TessBaseAPI is null after init")
                    return@withContext ""
                }

                api.setImage(bitmap)
                val text = api.utF8Text ?: ""
                Log.d(TAG, "Tesseract recognized ${text.length} chars")
                text.trim()
            } catch (e: Exception) {
                Log.e(TAG, "Tesseract recognition failed", e)
                ""
            }
        }
    }

    /**
     * Release native Tesseract resources. Call from Application.onTerminate
     * or similar lifecycle endpoint if desired.
     */
    fun shutdown() {
        try {
            tessApi?.recycle()
        } catch (_: Exception) { }
        tessApi = null
        initialized = false
    }

    // ── Internals ─────────────────────────────────────────────────────

    private fun ensureInitialized(context: Context) {
        if (initialized && tessApi != null) return

        // 1. Copy trained data files from assets → internal storage
        val tesseractDir = File(context.filesDir, "tesseract")
        val tessDataDir = File(tesseractDir, "tessdata")
        if (!tessDataDir.exists()) {
            tessDataDir.mkdirs()
        }

        for (fileName in TRAINED_DATA_FILES) {
            val destFile = File(tessDataDir, fileName)
            if (destFile.exists()) continue  // already extracted

            Log.d(TAG, "Extracting $fileName from assets...")
            try {
                context.assets.open("tessdata/$fileName").use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract $fileName", e)
            }
        }

        dataPath = tesseractDir.absolutePath

        // 2. Initialize TessBaseAPI
        val api = TessBaseAPI()
        if (!api.init(dataPath, LANGUAGES)) {
            Log.e(TAG, "Tesseract init failed for languages: $LANGUAGES")
            api.recycle()
            return
        }

        // Set Tesseract to use the LSTM-only engine for best accuracy
        // PageSegMode.AUTO lets Tesseract detect layout automatically
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO

        tessApi = api
        initialized = true
        Log.d(TAG, "Tesseract initialized with languages: $LANGUAGES")
    }
}
