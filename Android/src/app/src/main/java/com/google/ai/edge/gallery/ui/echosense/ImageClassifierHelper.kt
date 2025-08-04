package com.google.ai.edge.gallery.ui.echosense

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
// import androidx.glance.layout.height
// import androidx.glance.layout.width
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifierResult

// Import MPImage
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage

class ImageClassifierHelper(
    val context: Context,
    private val modelPath: String,
    val imageClassifierListener: ClassifierListener?
) {
    private var imageClassifier: ImageClassifier? = null

    init {
        setupImageClassifier()
    }

    private fun setupImageClassifier() {
        try {
            val baseOptionsBuilder = BaseOptions.builder().setModelAssetPath(modelPath)
            val baseOptions = baseOptionsBuilder.build()
            val optionsBuilder =
                ImageClassifier.ImageClassifierOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener(this::returnLivestreamResult)
                    .setErrorListener(this::returnLivestreamError)
            val options = optionsBuilder.build()
            imageClassifier = ImageClassifier.createFromOptions(context, options)
        } catch (e: Exception) {
            imageClassifierListener?.onError(e.message ?: "An unknown error has occurred")
        }
    }

    fun classifyAsync(mpImage: MPImage, timestamp: Long) {
        imageClassifier?.classifyAsync(mpImage, timestamp)
    }

    private fun returnLivestreamResult(result: ImageClassifierResult, input: MPImage) {
        val finishTime = SystemClock.uptimeMillis()
        val inferenceTime = finishTime - result.timestampMs()

        imageClassifierListener?.onResults(
            ResultBundle(
                listOf(result),
                inferenceTime,
                input.height,
                input.width
            )
        )
    }

    private fun returnLivestreamError(error: RuntimeException) {
        imageClassifierListener?.onError(error.message ?: "An unknown error has occurred")
    }

    interface ClassifierListener {
        fun onError(error: String)
        fun onResults(resultBundle: ResultBundle)
    }

    data class ResultBundle(
        val results: List<ImageClassifierResult>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
    )
}

//class ImageClassifierHelper(
//    val context: Context,
//    val imageClassifierListener: ClassifierListener?
//) {
//    private var imageClassifier: ImageClassifier? = null
//
//    init {
//        setupImageClassifier()
//    }
//
//    private fun setupImageClassifier() {
//        val baseOptionsBuilder = BaseOptions.builder().setModelAssetPath("gemma-3n-e2b-it-int4.bin")
//        val baseOptions = baseOptionsBuilder.build()
//        val optionsBuilder =
//            ImageClassifier.ImageClassifierOptions.builder()
//                .setBaseOptions(baseOptions)
//                .setRunningMode(RunningMode.IMAGE)
//                .setResultListener(this::returnLivestreamResult)
//                .setErrorListener(this::returnLivestreamError)
//        val options = optionsBuilder.build()
//        imageClassifier = ImageClassifier.createFromOptions(context, options)
//    }
//
//    fun classify(bitmap: Bitmap) {
//        imageClassifier?.classify(bitmap)
//    }
//
//    private fun returnLivestreamResult(result: ImageClassifierResult, input: Any) {
//        val finishTime = SystemClock.uptimeMillis()
//        val inferenceTime = finishTime - result.timestampMs()
//
//        imageClassifierListener?.onResults(
//            ResultBundle(
//                listOf(result),
//                inferenceTime,
//                -1,
//                -1
//            )
//        )
//    }
//
//    private fun returnLivestreamError(error: RuntimeException) {
//        imageClassifierListener?.onError(error.message ?: "An unknown error has occurred")
//    }
//
//    interface ClassifierListener {
//        fun onError(error: String)
//        fun onResults(resultBundle: ResultBundle)
//    }
//
//    data class ResultBundle(
//        val results: List<ImageClassifierResult>,
//        val inferenceTime: Long,
//        val inputImageHeight: Int,
//        val inputImageWidth: Int,
//    )
//}