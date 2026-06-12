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
import com.google.android.gms.tflite.java.TfLite
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.InterpreterApi.Options.TfLiteRuntime
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight wrapper around a YOLO11s TFLite model for object detection.
 *
 * Notes:
 * - Expects a YOLO11s float16 (or float32) TFLite model in assets.
 * - Output tensor shape: (1, 84, 8400) — transposed to (8400, 84) for parsing.
 *   First 4 values per detection are (cx, cy, w, h) in pixel coords relative to
 *   the 640×640 input. The remaining 80 values are COCO class confidence scores.
 * - Includes IoU-based Non-Maximum Suppression (NMS) post-processing.
 * - Uses Play Services TFLite runtime (InterpreterApi).
 * - If the model is missing or fails to load, the helper remains disabled.
 */
class DetectionHelper(
    private val context: Context,
    private val modelAssetPath: String = "yolo11s_float16.tflite",
    private val scoreThreshold: Float = 0.35f,
    private val iouThreshold: Float = 0.45f,
    private val maxResults: Int = 10,
) {
    companion object {
        private const val TAG = "DetectionHelper"
        private const val INPUT_SIZE = 640
        private const val NUM_CLASSES = 80
        private const val NUM_DETECTIONS = 8400
        /** 4 bbox values + 80 class scores */
        private const val NUM_ATTRIBUTES = 4 + NUM_CLASSES
    }

    @Volatile
    private var interpreter: InterpreterApi? = null

    @Volatile
    private var enabled: Boolean = false

    /** Output tensor dimensions detected at init time */
    private var outputDim1: Int = NUM_ATTRIBUTES
    private var outputDim2: Int = NUM_DETECTIONS
    /** True if output is (1, 84, 8400) — attributes × detections */
    private var transposedOutput: Boolean = true
    /** Log sample output values once for debugging */
    private var debugLogged: Boolean = false

    init {
        try {
            Log.d(TAG, "Initializing YOLO11s DetectionHelper with model='$modelAssetPath'")

            // Initialize Play Services TFLite runtime (idempotent, safe to call multiple times)
            try {
                val initTask = TfLite.initialize(context)
                com.google.android.gms.tasks.Tasks.await(initTask)
                Log.d(TAG, "TfLite runtime initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "TfLite.initialize() failed: ${e.javaClass.simpleName}: ${e.message}", e)
                throw e
            }

            // Load model from assets
            val modelBuffer: MappedByteBuffer
            try {
                modelBuffer = loadModelFile(context, modelAssetPath)
                Log.d(TAG, "Model file loaded: $modelAssetPath (${modelBuffer.capacity()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model file '$modelAssetPath': ${e.message}", e)
                throw e
            }

            // Build interpreter options — CPU only for reliability.
            // GPU delegate via Play Services requires async checks and can be
            // flaky on some devices; CPU with 4 threads is fast enough for
            // the proximity-analysis cadence (~2-4 fps).
            val options = InterpreterApi.Options()
                .setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY)
                .setNumThreads(4)

             try {
                interpreter = InterpreterApi.create(modelBuffer, options)
                Log.d(TAG, "InterpreterApi created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "InterpreterApi.create() failed: ${e.javaClass.simpleName}: ${e.message}", e)
                throw e
            }

            // Detect output tensor shape to handle both (1,84,8400) and (1,8400,84)
            val interp = interpreter!!
            val outputTensor = interp.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            Log.d(TAG, "Output tensor shape: ${outputShape.toList()}, dtype: ${outputTensor.dataType()}")

            // Determine layout: shape is [1, A, B]
            // If A == 84 → transposed layout (1, 84, 8400) — attributes × detections
            // If B == 84 → standard layout (1, 8400, 84) — detections × attributes
            if (outputShape.size == 3) {
                outputDim1 = outputShape[1]
                outputDim2 = outputShape[2]
                transposedOutput = (outputShape[1] == NUM_ATTRIBUTES)
                Log.d(TAG, "Output layout: ${if (transposedOutput) "transposed (1,$NUM_ATTRIBUTES,$outputDim2)" else "standard (1,$outputDim1,$NUM_ATTRIBUTES)"}")
            } else {
                Log.w(TAG, "Unexpected output tensor rank: ${outputShape.size}, assuming (1, $NUM_ATTRIBUTES, $NUM_DETECTIONS)")
                outputDim1 = NUM_ATTRIBUTES
                outputDim2 = NUM_DETECTIONS
                transposedOutput = true
            }

            enabled = true
            Log.d(
                TAG,
                "YOLO11s initialized OK — model='$modelAssetPath', " +
                        "scoreThreshold=$scoreThreshold, iouThreshold=$iouThreshold, " +
                        "maxResults=$maxResults"
            )
        } catch (e: Exception) {
            enabled = false
            interpreter = null
            Log.w(
                TAG,
                "YOLO11s DISABLED — model='$modelAssetPath', " +
                        "err=${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    fun isEnabled(): Boolean = enabled

    /**
     * Run object detection on a [Bitmap].
     *
     * The bitmap is resized to 640×640 and normalized to [0, 1] before inference.
     * Returns a list of [DetectionBox] after NMS filtering.
     */
    fun detect(bitmap: Bitmap): List<DetectionBox> {
        val interp = interpreter ?: return emptyList()
        return try {
            val originalWidth = bitmap.width
            val originalHeight = bitmap.height

            // Resize to 640×640
            val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

            // Prepare input: NHWC float buffer [1, 640, 640, 3]
            val inputBuffer = bitmapToByteBuffer(resized)

            // Prepare output: [1, dim1, dim2] as flat float array
            val outputArray = Array(1) { Array(outputDim1) { FloatArray(outputDim2) } }

            // Run inference
            interp.run(inputBuffer, outputArray)

            // Diagnostic logging (only once)
            if (!debugLogged) {
                debugLogged = true

                // Log input tensor info
                val inputTensor = interp.getInputTensor(0)
                Log.d(TAG, "Input tensor shape: ${inputTensor.shape().toList()}, dtype: ${inputTensor.dataType()}")

                // Scan ALL detections for the maximum class score
                val get: (Int, Int) -> Float = { attr, det -> outputArray[0][attr][det] }
                var maxScore = 0f
                var maxScoreDet = 0
                var maxScoreClass = 0
                val numDet = if (transposedOutput) outputDim2 else outputDim1
                for (i in 0 until numDet) {
                    for (c in 0 until NUM_CLASSES) {
                        val s = if (transposedOutput) outputArray[0][4 + c][i] else outputArray[0][i][4 + c]
                        if (s > maxScore) {
                            maxScore = s
                            maxScoreDet = i
                            maxScoreClass = c
                        }
                    }
                }
                val bestLabel = if (maxScoreClass < COCO_LABELS.size) COCO_LABELS[maxScoreClass] else "?"
                // Log bbox at the best-scoring detection
                val bcx = if (transposedOutput) outputArray[0][0][maxScoreDet] else outputArray[0][maxScoreDet][0]
                val bcy = if (transposedOutput) outputArray[0][1][maxScoreDet] else outputArray[0][maxScoreDet][1]
                val bw  = if (transposedOutput) outputArray[0][2][maxScoreDet] else outputArray[0][maxScoreDet][2]
                val bh  = if (transposedOutput) outputArray[0][3][maxScoreDet] else outputArray[0][maxScoreDet][3]
                Log.d(TAG, "Max class score across all $numDet detections: $maxScore " +
                        "(class=$maxScoreClass '$bestLabel', det=$maxScoreDet, " +
                        "bbox=[cx=$bcx, cy=$bcy, w=$bw, h=$bh])")
            }

            // Parse and NMS
            val rawBoxes = parseOutput(outputArray[0], originalWidth, originalHeight)
            val nmsBoxes = nms(rawBoxes)

            Log.d(
                TAG,
                "detect(): ${rawBoxes.size} raw → ${nmsBoxes.size} after NMS " +
                        "for ${originalWidth}x${originalHeight} frame"
            )
            nmsBoxes
        } catch (e: Exception) {
            Log.e(TAG, "detect() failed: ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }
    }

    fun close() {
        try {
            interpreter?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing interpreter: ${e.message}")
        }
        interpreter = null
        enabled = false
    }

    // ---- Private helpers ----

    /**
     * Convert a 640×640 Bitmap to a [ByteBuffer] in NHWC float32 format,
     * normalized to [0, 1].
     */
    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            // RGB channels normalized to [0, 1]
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            buffer.putFloat((pixel and 0xFF) / 255.0f)           // B
        }
        buffer.rewind()
        return buffer
    }

    /**
     * Parse the YOLO output tensor into candidate [DetectionBox]es.
     *
     * Handles both output layouts:
     * - Transposed (1, 84, 8400): output[attr][det] — attributes × detections
     * - Standard  (1, 8400, 84): output[det][attr] — detections × attributes
     *
     * Attributes: [cx, cy, w, h, class0, class1, ..., class79]
     * Coordinates are normalized [0, 1] and need rescaling to the original image.
     */
    private fun parseOutput(
        output: Array<FloatArray>,
        originalWidth: Int,
        originalHeight: Int,
    ): List<DetectionBox> {
        // Helper to access value by (attribute, detection) regardless of layout
        val get: (Int, Int) -> Float = if (transposedOutput) {
            { attr, det -> output[attr][det] }  // output[attr][det]
        } else {
            { attr, det -> output[det][attr] }  // output[det][attr]
        }

        val numDetections = if (transposedOutput) outputDim2 else outputDim1
        val boxes = mutableListOf<DetectionBox>()

        for (i in 0 until numDetections) {
            // Find best class
            var bestClassIdx = 0
            var bestScore = 0f
            for (c in 0 until NUM_CLASSES) {
                val score = get(4 + c, i)
                if (score > bestScore) {
                    bestScore = score
                    bestClassIdx = c
                }
            }

            if (bestScore < scoreThreshold) continue

            // Extract bbox (center format, normalized 0..1)
            val cx = get(0, i)
            val cy = get(1, i)
            val w = get(2, i)
            val h = get(3, i)

            // Convert normalized coords to pixel coords in original image space
            val x1 = ((cx - w / 2f) * originalWidth).toInt().coerceIn(0, originalWidth)
            val y1 = ((cy - h / 2f) * originalHeight).toInt().coerceIn(0, originalHeight)
            val x2 = ((cx + w / 2f) * originalWidth).toInt().coerceIn(0, originalWidth)
            val y2 = ((cy + h / 2f) * originalHeight).toInt().coerceIn(0, originalHeight)

            val boxW = x2 - x1
            val boxH = y2 - y1
            if (boxW <= 0 || boxH <= 0) continue

            val label = if (bestClassIdx < COCO_LABELS.size) {
                COCO_LABELS[bestClassIdx]
            } else {
                "Obstacle"
            }

            boxes.add(
                DetectionBox(
                    label = label,
                    score = bestScore,
                    x = x1,
                    y = y1,
                    width = boxW,
                    height = boxH,
                    imageWidth = originalWidth,
                    imageHeight = originalHeight,
                )
            )
        }
        return boxes
    }

    /**
     * IoU-based Non-Maximum Suppression.
     * Keeps the top [maxResults] boxes after filtering overlaps.
     */
    private fun nms(boxes: List<DetectionBox>): List<DetectionBox> {
        if (boxes.isEmpty()) return emptyList()

        // Sort by score descending
        val sorted = boxes.sortedByDescending { it.score }
        val kept = mutableListOf<DetectionBox>()
        val suppressed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            kept.add(sorted[i])
            if (kept.size >= maxResults) break

            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (iou(sorted[i], sorted[j]) > iouThreshold) {
                    suppressed[j] = true
                }
            }
        }
        return kept
    }

    /**
     * Compute Intersection-over-Union between two boxes.
     */
    private fun iou(a: DetectionBox, b: DetectionBox): Float {
        val x1 = max(a.x, b.x)
        val y1 = max(a.y, b.y)
        val x2 = min(a.x + a.width, b.x + b.width)
        val y2 = min(a.y + a.height, b.y + b.height)

        val intersection = max(0, x2 - x1).toLong() * max(0, y2 - y1).toLong()
        if (intersection == 0L) return 0f

        val areaA = a.width.toLong() * a.height.toLong()
        val areaB = b.width.toLong() * b.height.toLong()
        val union = areaA + areaB - intersection
        return if (union > 0) intersection.toFloat() / union.toFloat() else 0f
    }

    /**
     * Memory-map a model file from assets for efficient loading.
     */
    private fun loadModelFile(context: Context, assetPath: String): MappedByteBuffer {
        val fd = context.assets.openFd(assetPath)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val channel = inputStream.channel
        val startOffset = fd.startOffset
        val declaredLength = fd.declaredLength
        return channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
}
