/*
 * Copyright 2025 Google LLC
 * Modified from TerraNet Technologies LLC implementation
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

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * Lightweight wrapper around MediaPipe ObjectDetector for collision avoidance.
 *
 * This experiment uses the bundled EfficientDet Lite2 task instead of the
 * previous YOLO11s TFLite path. The rest of the collision avoidance pipeline
 * still consumes [DetectionBox] values, so this class adapts MediaPipe
 * detections into the existing shape.
 */
class DetectionHelper(
    private val context: Context,
    private val modelAssetPath: String = "efficientdet_lite2.task",
    private val scoreThreshold: Float = 0.35f,
    private val maxResults: Int = 10,
) {
    companion object {
        private const val TAG = "DetectionHelper"
    }

    @Volatile
    private var objectDetector: ObjectDetector? = null

    @Volatile
    private var enabled: Boolean = false

    init {
        try {
            Log.d(TAG, "Initializing EfficientDet DetectionHelper with model='$modelAssetPath'")

            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelAssetPath)
                .build()
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(maxResults)
                .setScoreThreshold(scoreThreshold)
                .build()

            objectDetector = ObjectDetector.createFromOptions(context, options)
            enabled = true
            Log.d(
                TAG,
                "EfficientDet initialized OK — model='$modelAssetPath', " +
                    "scoreThreshold=$scoreThreshold, maxResults=$maxResults",
            )
        } catch (e: Exception) {
            enabled = false
            objectDetector = null
            Log.w(
                TAG,
                "EfficientDet DISABLED — model='$modelAssetPath', " +
                    "err=${e.javaClass.simpleName}: ${e.message}",
                e,
            )
        }
    }

    fun isEnabled(): Boolean = enabled

    fun detect(bitmap: Bitmap): List<DetectionBox> {
        val detector = objectDetector ?: return emptyList()
        return try {
            BitmapImageBuilder(bitmap).build().use { image ->
                val result = detector.detect(image)
                val boxes = result.detections().mapNotNull { detection ->
                    val category = detection.categories().maxByOrNull { it.score() }
                    val score = category?.score() ?: return@mapNotNull null
                    val rect = detection.boundingBox()

                    val x = rect.left.toInt().coerceIn(0, bitmap.width)
                    val y = rect.top.toInt().coerceIn(0, bitmap.height)
                    val right = rect.right.toInt().coerceIn(0, bitmap.width)
                    val bottom = rect.bottom.toInt().coerceIn(0, bitmap.height)
                    val width = right - x
                    val height = bottom - y
                    if (width <= 0 || height <= 0) return@mapNotNull null

                    DetectionBox(
                        label = category.categoryName().ifBlank { "Obstacle" },
                        score = score,
                        x = x,
                        y = y,
                        width = width,
                        height = height,
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                    )
                }

                Log.d(
                    TAG,
                    "detect(): ${boxes.size} EfficientDet boxes for " +
                        "${bitmap.width}x${bitmap.height} frame",
                )
                boxes
            }
        } catch (e: Exception) {
            Log.e(TAG, "detect() failed: ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }
    }

    fun close() {
        try {
            objectDetector?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing object detector: ${e.message}")
        }
        objectDetector = null
        enabled = false
    }
}
