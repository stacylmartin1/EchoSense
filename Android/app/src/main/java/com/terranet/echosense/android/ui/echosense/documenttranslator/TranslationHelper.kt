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

package com.terranet.echosense.android.ui.echosense.documenttranslator

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Singleton managing the validated offline ML Kit translation path and language packs.
 * Online translation routing is handled by [DocumentTranslatorViewModel].
 */
object TranslationHelper {

    private const val TAG = "TranslationHelper"
    private val validatedOfflineLanguages = setOf("en", "es", "fr", "de")

    private val _downloadedLanguages = MutableStateFlow<Set<String>>(emptySet())
    val downloadedLanguages: StateFlow<Set<String>> = _downloadedLanguages

    private val _isDownloading = MutableStateFlow<Set<String>>(emptySet())
    val isDownloading: StateFlow<Set<String>> = _isDownloading

    /** Refresh the set of downloaded language packs. */
    fun refreshDownloadedLanguages() {
        RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                val langs = models.map { it.language }.toSet()
                _downloadedLanguages.value = langs
                Log.d(TAG, "Downloaded languages: $langs")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error listing downloaded models", e)
            }
    }

    /** Languages validated for this app's offline Latin-OCR translation path. */
    fun getAllSupportedLanguages(): List<LanguageInfo> {
        return validatedOfflineLanguages.map { code ->
            LanguageInfo(
                code = code,
                displayName = java.util.Locale.forLanguageTag(code).displayLanguage
            )
        }.sortedBy { it.displayName }
    }

    /** Download a language pack for offline use. */
    fun downloadLanguagePack(
        languageCode: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        require(languageCode in validatedOfflineLanguages) {
            "Offline translation is not validated for '$languageCode'."
        }
        _isDownloading.value = _isDownloading.value + languageCode
        val model = TranslateRemoteModel.Builder(languageCode).build()
        val conditions = DownloadConditions.Builder().build()

        RemoteModelManager.getInstance().download(model, conditions)
            .addOnSuccessListener {
                Log.d(TAG, "Downloaded language pack: $languageCode")
                _isDownloading.value = _isDownloading.value - languageCode
                refreshDownloadedLanguages()
                onSuccess()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to download $languageCode", e)
                _isDownloading.value = _isDownloading.value - languageCode
                onFailure(e)
            }
    }

    /** Delete a downloaded language pack. */
    fun deleteLanguagePack(
        languageCode: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val model = TranslateRemoteModel.Builder(languageCode).build()
        RemoteModelManager.getInstance().deleteDownloadedModel(model)
            .addOnSuccessListener {
                Log.d(TAG, "Deleted language pack: $languageCode")
                refreshDownloadedLanguages()
                onSuccess()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to delete $languageCode", e)
                onFailure(e)
            }
    }

    /** Detect the language of [text] using ML Kit Language Identification. */
    suspend fun detectLanguage(text: String): String {
        return suspendCancellableCoroutine { cont ->
            val identifier = LanguageIdentification.getClient()
            identifier.identifyLanguage(text)
                .addOnSuccessListener { languageCode ->
                    if (languageCode == "und") {
                        // Undetermined — default to assuming non-English
                        cont.resume("und")
                    } else {
                        cont.resume(languageCode)
                    }
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(e)
                }
        }
    }

    /**
     * Translate [text] from [sourceLanguage] to [targetLanguage] using ML Kit.
     * Both parameters should be BCP-47 language codes (e.g. "th", "en").
     * Returns the translated text.
     *
     * @throws IllegalStateException if the required language pack is not downloaded.
     */
    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String = TranslateLanguage.ENGLISH
    ): String {
        // Verify the source language is supported by ML Kit
        val mlKitSource = TranslateLanguage.fromLanguageTag(sourceLanguage)
        if (mlKitSource == null) {
            throw IllegalStateException("Unsupported source language: $sourceLanguage")
        }

        val mlKitTarget = TranslateLanguage.fromLanguageTag(targetLanguage)
        if (mlKitTarget == null) {
            throw IllegalStateException("Unsupported target language: $targetLanguage")
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(mlKitSource)
            .setTargetLanguage(mlKitTarget)
            .build()

        val translator = Translation.getClient(options)

        return suspendCancellableCoroutine { cont ->
            // Ensure model is available (will use cached if already downloaded)
            translator.downloadModelIfNeeded()
                .addOnSuccessListener {
                    translator.translate(text)
                        .addOnSuccessListener { translatedText ->
                            cont.resume(translatedText)
                            translator.close()
                        }
                        .addOnFailureListener { e ->
                            translator.close()
                            cont.resumeWithException(e)
                        }
                }
                .addOnFailureListener { e ->
                    translator.close()
                    cont.resumeWithException(
                        IllegalStateException(
                            "Language pack for '$sourceLanguage' not downloaded. " +
                            "Please download it in the Languages menu while connected to the internet.",
                            e
                        )
                    )
                }
        }
    }

    /**
     * Convenience: detect language then translate to English.
     * Returns a [TranslationResult] with both detected language and translated text.
     */
    suspend fun detectAndTranslate(
        text: String,
        targetLanguage: String = TranslateLanguage.ENGLISH
    ): TranslationResult {
        val detectedLang = detectLanguage(text)

        if (detectedLang == "und") {
            throw IllegalStateException(
                "The source language could not be identified. Try a clearer image or select a supported source language."
            )
        }
        if (detectedLang !in validatedOfflineLanguages) {
            throw IllegalStateException(
                "Offline translation currently supports English, Spanish, French, and German. " +
                    "Use online translation for $detectedLang."
            )
        }

        // If already in the target language, return as-is.
        if (detectedLang == targetLanguage) {
            return TranslationResult(
                detectedLanguage = detectedLang,
                translatedText = text,
                wasTranslated = false
            )
        }

        val translated = translate(text, detectedLang, targetLanguage)
        return TranslationResult(
            detectedLanguage = detectedLang,
            translatedText = translated,
            wasTranslated = true
        )
    }
}

data class LanguageInfo(
    val code: String,
    val displayName: String
)

data class TranslationResult(
    val detectedLanguage: String,
    val translatedText: String,
    val wasTranslated: Boolean
)
