/*
 * Copyright 2026 TerraNet Technologies LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.terranet.echosense.android.ui.echosense

/**
 * Produces one useful audible announcement for each model lifecycle stage.
 *
 * UI state can briefly regress while Compose receives download, initialization, and model-instance
 * updates from separate flows. Once a model has announced that it is ready, those transient updates
 * must not announce that the same model is loading again.
 */
internal class ModelStatusAnnouncementTracker {
    private var modelName: String? = null
    private var missingAnnounced = false
    private var downloadingAnnounced = false
    private var loadingAnnounced = false
    private var readyAnnounced = false

    fun nextAnnouncement(
        currentModelName: String,
        isModelInstalled: Boolean,
        isModelDownloadInProgress: Boolean,
        isModelReady: Boolean,
    ): ModelStatusAnnouncement? {
        if (modelName != currentModelName) {
            modelName = currentModelName
            missingAnnounced = false
            downloadingAnnounced = false
            loadingAnnounced = false
            readyAnnounced = false
        }

        if (isModelReady) {
            if (readyAnnounced) return null
            readyAnnounced = true
            loadingAnnounced = true
            return ModelStatusAnnouncement.READY
        }

        // Initialization and download flows sometimes publish an older value after READY.
        // Treat READY as terminal for this model selection so speech never runs backwards.
        if (readyAnnounced) return null

        return when {
            isModelInstalled && !loadingAnnounced -> {
                loadingAnnounced = true
                ModelStatusAnnouncement.LOADING
            }
            isModelDownloadInProgress && !downloadingAnnounced -> {
                downloadingAnnounced = true
                ModelStatusAnnouncement.DOWNLOADING
            }
            !isModelInstalled && !isModelDownloadInProgress && !missingAnnounced -> {
                missingAnnounced = true
                ModelStatusAnnouncement.MISSING
            }
            else -> null
        }
    }
}

internal enum class ModelStatusAnnouncement {
    MISSING,
    DOWNLOADING,
    LOADING,
    READY,
}
