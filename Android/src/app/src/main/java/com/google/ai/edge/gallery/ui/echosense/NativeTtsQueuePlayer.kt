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

import android.app.Application
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections

/**
 * Queue-based wrapper around [android.speech.tts.TextToSpeech] for streaming TTS.
 *
 * Key features:
 * - [queueSentence]: speaks with [TextToSpeech.QUEUE_ADD] (chains after current speech).
 *   Auto-splits text >3900 chars at sentence boundaries.
 * - [announceStatus]: speaks with [TextToSpeech.QUEUE_FLUSH] (interrupts for alerts/status).
 * - [stop]: cancels all queued speech.
 * - [markInputComplete]: when the last queued utterance finishes, fires [onAllComplete].
 * - [shutdown]: releases TTS resources.
 *
 * Encapsulates TTS initialization with retry logic (3 attempts), language setup,
 * audio attributes (USAGE_ASSISTANCE_ACCESSIBILITY), and [UtteranceProgressListener] management.
 */
class NativeTtsQueuePlayer(
    private val application: Application,
    private val onAllComplete: () -> Unit,
) {
    private data class PendingSafetyAnnouncement(
        val text: String,
        val severity: ProximitySeverity,
        val rate: Float,
        val expiresAtMs: Long,
    )

    private var tts: TextToSpeech? = null
    var selectedVoiceName: String = "" // Set externally or implicitly read
    private val isReady = AtomicBoolean(false)
    private val initAttempts = AtomicInteger(0)
    private val inputComplete = AtomicBoolean(false)
    private val utteranceCounter = AtomicInteger(0)
    private val pendingUtterances = AtomicInteger(0)
    private val activeUtteranceIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val safetyLock = Any()
    private val safetyAnnouncementsSuspended = AtomicBoolean(false)
    private var activeSafetyUtteranceId: String? = null
    private var activeSafetySeverity: ProximitySeverity? = null
    private var pendingSafetyAnnouncement: PendingSafetyAnnouncement? = null

    // Deferred announcements that arrived before TTS was ready
    private val deferredAnnouncements = mutableListOf<String>()

    companion object {
        private const val TAG = "NativeTtsQueuePlayer"
        private const val MAX_INIT_ATTEMPTS = 3
        private const val MAX_TTS_LENGTH = 3900
    }

    init {
        initializeTts()
    }

    val isTtsReady: Boolean get() = isReady.get()

    private fun initializeTts() {
        val attempt = initAttempts.incrementAndGet()
        Log.d(TAG, "TTS initialization attempt $attempt/$MAX_INIT_ATTEMPTS")
        try {
            tts?.shutdown()
            tts = TextToSpeech(application) { status -> onTtsInit(status) }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating TextToSpeech instance", e)
            retryInit()
        }
    }

    private fun onTtsInit(status: Int) {
        Log.d(TAG, "TTS onInit status=$status")
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS init failed with status $status")
            retryInit()
            return
        }
        val engine = tts ?: return
        
        if (selectedVoiceName.isNotEmpty()) {
            val matchedVoice = engine.voices?.find { it.name == selectedVoiceName }
            if (matchedVoice != null) {
                engine.voice = matchedVoice
                Log.d(TAG, "Selected custom voice: $selectedVoiceName")
            } else {
                Log.w(TAG, "Voice $selectedVoiceName not found, falling back")
                applyDefaultLocale(engine)
            }
        } else {
            applyDefaultLocale(engine)
        }

        engine.setSpeechRate(1.0f)
        engine.setPitch(1.0f)

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        engine.setAudioAttributes(audioAttrs)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "Utterance started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "Utterance done: $utteranceId, pending=${pendingUtterances.get()}")
                completeUtterance(utteranceId)
                safetyUtteranceEnded(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "Utterance error: $utteranceId")
                completeUtterance(utteranceId)
                safetyUtteranceEnded(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                Log.d(TAG, "Utterance stopped: $utteranceId, interrupted=$interrupted")
                completeUtterance(utteranceId)
                safetyUtteranceEnded(utteranceId)
            }
        })

        isReady.set(true)
        Log.d(TAG, "TTS initialized successfully")

        // Deliver any deferred announcements
        synchronized(deferredAnnouncements) {
            for (text in deferredAnnouncements) {
                speakInternal(text, TextToSpeech.QUEUE_FLUSH)
            }
            deferredAnnouncements.clear()
        }
        maybeStartPendingSafety()
    }

    private fun applyDefaultLocale(engine: TextToSpeech) {
        val langResult = engine.setLanguage(Locale.US)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "US English not available (result=$langResult), trying default locale")
            val fallback = engine.setLanguage(Locale.getDefault())
            if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "No TTS language available")
                retryInit()
            }
        }
    }

    /**
     * Update the voice dynamically if TTS is already running.
     */
    fun updateVoice(voiceName: String) {
        selectedVoiceName = voiceName
        val engine = tts ?: return
        if (voiceName.isNotEmpty()) {
            val matchedVoice = engine.voices?.find { it.name == voiceName }
            if (matchedVoice != null) {
                engine.voice = matchedVoice
                Log.d(TAG, "Updated to custom voice: $voiceName")
                return
            }
        }
        applyDefaultLocale(engine)
    }

    private fun retryInit() {
        if (initAttempts.get() < MAX_INIT_ATTEMPTS) {
            Log.w(TAG, "Retrying TTS init in 2s...")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                initializeTts()
            }, 2000)
        } else {
            Log.e(TAG, "TTS initialization failed after $MAX_INIT_ATTEMPTS attempts")
        }
    }

    /**
     * Queue a sentence to be spoken after any currently playing/queued speech.
     * Auto-splits text longer than [MAX_TTS_LENGTH] at sentence boundaries.
     */
    fun queueSentence(text: String) {
        val clean = sanitize(text) ?: return
        if (clean.length <= MAX_TTS_LENGTH) {
            speakInternal(clean, TextToSpeech.QUEUE_ADD)
        } else {
            splitAndQueue(clean)
        }
    }

    /**
     * Interrupt all queued speech and speak this text immediately.
     * Used for proximity alerts and status announcements.
     */
    fun announceStatus(text: String) {
        val clean = sanitize(text) ?: return
        inputComplete.set(false)
        if (!isReady.get()) {
            Log.w(TAG, "TTS not ready; deferring announcement: '$clean'")
            synchronized(deferredAnnouncements) {
                deferredAnnouncements.clear()
                deferredAnnouncements.add(clean)
            }
            return
        }
        // QUEUE_FLUSH interrupts current speech and clears the queue
        synchronized(safetyLock) {
            activeSafetyUtteranceId = null
            activeSafetySeverity = null
            pendingSafetyAnnouncement = null
        }
        resetPendingUtterances()
        speakInternal(clean, TextToSpeech.QUEUE_FLUSH)
    }

    /**
     * Speaks a collision warning while protecting an active Safety phrase from
     * successive sensor updates. Only the freshest pending update is retained.
     */
    fun announceSafety(
        text: String,
        severity: ProximitySeverity,
        rate: Float,
        followUpText: String? = null,
    ) {
        if (safetyAnnouncementsSuspended.get()) return
        val clean = sanitize(text) ?: return
        val announcement = PendingSafetyAnnouncement(
            text = clean,
            severity = severity,
            rate = rate.coerceIn(0.8f, 1.4f),
            expiresAtMs = System.currentTimeMillis() + 1_250L,
        )
        val followUp = followUpText?.trim()?.takeIf { it.isNotEmpty() }?.let {
            PendingSafetyAnnouncement(
                text = it,
                severity = severity,
                rate = rate.coerceIn(0.8f, 1.4f),
                expiresAtMs = System.currentTimeMillis() + 2_000L,
            )
        }
        synchronized(safetyLock) {
            if (!isReady.get()) {
                pendingSafetyAnnouncement = announcement
                return
            }
            val activeSeverity = activeSafetySeverity
            if (activeSeverity != null) {
                when {
                    severity.ordinal > activeSeverity.ordinal -> {
                        activeSafetyUtteranceId = null
                        activeSafetySeverity = null
                        pendingSafetyAnnouncement = null
                        resetPendingUtterances()
                        tts?.stop()
                        startSafetyLocked(announcement)
                        pendingSafetyAnnouncement = followUp
                    }
                    severity == activeSeverity -> {
                        pendingSafetyAnnouncement = announcement
                    }
                    else -> Unit // Do not follow an urgent phrase with a stale downgrade.
                }
                return
            }
            if (severity == ProximitySeverity.INFO && pendingUtterances.get() > 0) {
                pendingSafetyAnnouncement = announcement
                return
            }
            startSafetyLocked(announcement)
            pendingSafetyAnnouncement = followUp
        }
    }

    /**
     * Suppresses new Safety speech and discards any pending update. Callers
     * stop current speech before suspending when beginning result narration.
     */
    fun setSafetyAnnouncementsSuspended(suspended: Boolean) {
        safetyAnnouncementsSuspended.set(suspended)
        if (suspended) clearPendingSafetyAnnouncements()
    }

    /** Drops Safety updates that have not started without disturbing narration. */
    fun clearPendingSafetyAnnouncements() {
        synchronized(safetyLock) {
            pendingSafetyAnnouncement = null
        }
    }

    /**
     * Queue a short status update after current speech. Used when preserving
     * an in-progress status phrase matters more than immediate interruption.
     */
    fun queueStatus(text: String) {
        val clean = sanitize(text) ?: return
        inputComplete.set(false)
        if (!isReady.get()) {
            Log.w(TAG, "TTS not ready; deferring queued status: '$clean'")
            synchronized(deferredAnnouncements) {
                deferredAnnouncements.add(clean)
            }
            return
        }
        speakInternal(clean, TextToSpeech.QUEUE_ADD)
    }

    /** Stop all speech and clear the queue. */
    fun stop() {
        inputComplete.set(false)
        synchronized(safetyLock) {
            activeSafetyUtteranceId = null
            activeSafetySeverity = null
            pendingSafetyAnnouncement = null
        }
        resetPendingUtterances()
        tts?.stop()
    }

    /**
     * Signal that no more sentences will be queued. When the last pending utterance
     * finishes, [onAllComplete] will be called.
     */
    fun markInputComplete() {
        inputComplete.set(true)
        // If nothing is pending (e.g., empty response), fire immediately
        if (pendingUtterances.get() <= 0) {
            Log.d(TAG, "markInputComplete: no pending utterances, firing onAllComplete immediately")
            onAllComplete()
        }
    }

    /** Release all TTS resources. Call from ViewModel.onCleared(). */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady.set(false)
        resetPendingUtterances()
    }

    private fun speakInternal(text: String, queueMode: Int, rate: Float = 1.0f): String? {
        if (!isReady.get()) {
            Log.w(TAG, "TTS not ready, cannot speak: '$text'")
            return null
        }
        val engine = tts ?: return null
        val id = "echosense_${utteranceCounter.incrementAndGet()}"
        activeUtteranceIds.add(id)
        pendingUtterances.incrementAndGet()
        engine.setSpeechRate(rate)
        val result = engine.speak(text, queueMode, null, id)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS speak failed (result=$result) for: '$text'")
            completeUtterance(id)
            return null
        } else {
            Log.d(TAG, "TTS queued (mode=${if (queueMode == TextToSpeech.QUEUE_ADD) "ADD" else "FLUSH"}): '$text'")
        }
        return id
    }

    private fun startSafetyLocked(announcement: PendingSafetyAnnouncement) {
        inputComplete.set(false)
        resetPendingUtterances()
        val id = speakInternal(announcement.text, TextToSpeech.QUEUE_FLUSH, announcement.rate)
        if (id != null) {
            activeSafetyUtteranceId = id
            activeSafetySeverity = announcement.severity
        }
    }

    private fun safetyUtteranceEnded(utteranceId: String?) {
        synchronized(safetyLock) {
            if (utteranceId != activeSafetyUtteranceId) {
                if (pendingUtterances.get() <= 0) startPendingSafetyLocked()
                return
            }
            activeSafetyUtteranceId = null
            activeSafetySeverity = null
            val pending = pendingSafetyAnnouncement
            pendingSafetyAnnouncement = null
            if (pending != null && pending.expiresAtMs > System.currentTimeMillis()) {
                // Safety remains ahead of narration queued while the protected
                // phrase was playing. Flush that stale narration continuation.
                resetPendingUtterances()
                startSafetyLocked(pending)
            }
        }
    }

    private fun maybeStartPendingSafety() {
        synchronized(safetyLock) { startPendingSafetyLocked() }
    }

    private fun startPendingSafetyLocked() {
        val pending = pendingSafetyAnnouncement ?: return
        if (activeSafetyUtteranceId != null || pendingUtterances.get() > 0) return
        pendingSafetyAnnouncement = null
        if (pending.expiresAtMs > System.currentTimeMillis()) startSafetyLocked(pending)
    }

    private fun resetPendingUtterances() {
        activeUtteranceIds.clear()
        pendingUtterances.set(0)
    }

    private fun completeUtterance(utteranceId: String?) {
        if (utteranceId == null || !activeUtteranceIds.remove(utteranceId)) {
            Log.d(TAG, "Ignoring stale TTS callback for utterance: $utteranceId")
            return
        }

        val remaining = pendingUtterances.decrementAndGet().coerceAtLeast(0)
        if (remaining <= 0) {
            pendingUtterances.set(0)
        }

        if (remaining <= 0 && inputComplete.get()) {
            Log.d(TAG, "All utterances complete and input marked done, firing onAllComplete")
            onAllComplete()
        }
    }

    /**
     * Split long text into chunks ≤ [MAX_TTS_LENGTH] at sentence boundaries,
     * then queue each chunk.
     */
    private fun splitAndQueue(text: String) {
        var remaining = text
        while (remaining.isNotEmpty()) {
            if (remaining.length <= MAX_TTS_LENGTH) {
                speakInternal(remaining, TextToSpeech.QUEUE_ADD)
                break
            }
            // Find the last sentence-ending punctuation before the limit
            val searchRange = remaining.substring(0, MAX_TTS_LENGTH)
            val splitIdx = findLastSentenceBoundary(searchRange)
            if (splitIdx > 0) {
                speakInternal(remaining.substring(0, splitIdx).trim(), TextToSpeech.QUEUE_ADD)
                remaining = remaining.substring(splitIdx).trim()
            } else {
                // No sentence boundary found — split at last space
                val spaceIdx = searchRange.lastIndexOf(' ')
                if (spaceIdx > 0) {
                    speakInternal(remaining.substring(0, spaceIdx).trim(), TextToSpeech.QUEUE_ADD)
                    remaining = remaining.substring(spaceIdx).trim()
                } else {
                    // No space either — force split at limit
                    speakInternal(remaining.substring(0, MAX_TTS_LENGTH), TextToSpeech.QUEUE_ADD)
                    remaining = remaining.substring(MAX_TTS_LENGTH)
                }
            }
        }
    }

    private fun findLastSentenceBoundary(text: String): Int {
        val endings = charArrayOf('.', '!', '?', ';')
        var lastIdx = -1
        for (ending in endings) {
            val idx = text.lastIndexOf(ending)
            if (idx > lastIdx) {
                lastIdx = idx
            }
        }
        return if (lastIdx >= 0) lastIdx + 1 else -1
    }

    private fun sanitize(text: String): String? {
        val clean = SpeechTextSanitizer.sanitize(text)
        if (clean.isBlank()) {
            Log.w(TAG, "Empty text provided to TTS")
            return null
        }
        return clean
    }
}
