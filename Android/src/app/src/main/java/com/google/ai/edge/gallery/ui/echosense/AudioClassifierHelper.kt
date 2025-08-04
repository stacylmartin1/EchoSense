package com.google.ai.edge.gallery.ui.echosense

import android.content.Context
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifierResult
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import java.util.concurrent.ScheduledThreadPoolExecutor

class AudioClassifierHelper(
    val context: Context,
    val listener: ClassifierListener,
    var modelName: String = "gemma-3n-e2b-it-int4.bin"
) {
    private lateinit var classifier: AudioClassifier
    private lateinit var executor: ScheduledThreadPoolExecutor

    init {
        initClassifier()
    }

    private fun initClassifier() {
        val baseOptionsBuilder = BaseOptions.builder()
            .setModelAssetPath(modelName)
            .setDelegate(Delegate.CPU)

        val baseOptions = baseOptionsBuilder.build()

        val options = AudioClassifier.AudioClassifierOptions.builder()
            .setBaseOptions(baseOptions)
            .setResultListener { result -> // CORRECT: Lambda now takes only one argument
                // The 'result' here is the AudioClassifierResult

                // Now, you need to figure out how to get the timestamp.
                // 1. Check if 'AudioClassifierResult' has a timestamp property/method:
                //    (This is the ideal scenario if the library designers moved it here)
                //    e.g., if result has a method like .timestampMillis() or a property .timestampMs
                //    val currentTimestampMs = result.timestampMillis() // Example name

                // 2. If the result object does NOT contain a timestamp:
                //    You might need to use System.currentTimeMillis() if an approximate
                //    timestamp is acceptable for when the result is processed in the listener.
                //    This won't be the exact inference timestamp from MediaPipe.
                val currentTimestampMs = System.currentTimeMillis() // Fallback if not in result

                returnLivestreamResult(result, currentTimestampMs)
            }
            .setErrorListener { error -> // Assuming setErrorListener still takes two args
                listener.onError(
                    error.message ?: "An unknown error has occurred"
                )
            }
            .build()

        classifier = AudioClassifier.createFromOptions(context, options)
    }

/*    private fun initClassifier() {
        val baseOptionsBuilder = BaseOptions.builder()
            .setModelAssetPath(modelName)
            .setDelegate(Delegate.CPU)

        val baseOptions = baseOptionsBuilder.build()

        val options = AudioClassifier.AudioClassifierOptions.builder()
            .setBaseOptions(baseOptions)
            .setResultListener { result, timestampMs ->
                returnLivestreamResult(result, timestampMs)
            }
            .setErrorListener { error ->
                returnLivestreamError(error)
            }
            .build()
        classifier = AudioClassifier.createFromOptions(context, options)
    }*/

    private fun returnLivestreamResult(result: AudioClassifierResult, timestampMs: Long) {
        listener.onResults(
            ResultBundle(
                listOf(result),
                timestampMs,
            )
        )
    }

//    private fun returnLivestreamError(error: RuntimeException) {
//        listener.onError(error.message ?: "An unknown error has occurred")
//    }

    interface ClassifierListener {
        fun onError(error: String)
        fun onResults(resultBundle: ResultBundle)
    }

    data class ResultBundle(
        val results: List<AudioClassifierResult>,
        val inferenceTime: Long,
    )
}