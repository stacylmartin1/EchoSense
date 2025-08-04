package com.google.ai.edge.gallery.ui.echosense

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.Executors

@Composable
fun CameraPreview(
    cameraController: LifecycleCameraController,
    modifier: Modifier = Modifier,
    onBitmapReady: (Bitmap) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // val context = LocalContext.current // Not strictly needed here anymore

    // Configure ImageAnalysis directly on the LifecycleCameraController
    cameraController.setImageAnalysisAnalyzer(
        Executors.newSingleThreadExecutor(),
        ImageAnalysis.Analyzer { imageProxy -> // This is your Analyzer interface implementation
            // Ensure you handle potential exceptions during bitmap conversion or processing
            try {
                val bitmap = imageProxy.toBitmap()
                onBitmapReady(bitmap)
            } finally {
                imageProxy.close() // Crucial to close the ImageProxy
            }
        }
    )
    // You can also set other ImageAnalysis configurations if needed:
    // cameraController.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
    // cameraController.imageAnalysisTargetSize = ... // If you need a specific size

    AndroidView(
        factory = {
            PreviewView(it).apply {
                this.controller = cameraController
                // Bind to lifecycle after all configurations are set
                cameraController.bindToLifecycle(lifecycleOwner)
            }
        },
        modifier = modifier
    )
}

//@Composable
//fun CameraPreview(
//    cameraController: LifecycleCameraController,
//    modifier: Modifier = Modifier,
//    onBitmapReady: (Bitmap) -> Unit
//) {
//    val lifecycleOwner = LocalLifecycleOwner.current
//    val context = LocalContext.current
//
//    val imageAnalysis = ImageAnalysis.Builder()
//        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
//        .build()
//        .also {
//            it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
//                val bitmap = imageProxy.toBitmap()
//                onBitmapReady(bitmap)
//                imageProxy.close()
//            }
//        }
//
//    AndroidView(
//        factory = {
//            PreviewView(it).apply {
//                this.controller = cameraController.apply {
//                    this.setImageAnalysisAnalyzer(
//                        Executors.newSingleThreadExecutor(),
//                        imageAnalysis
//                    )
//                }
//                cameraController.bindToLifecycle(lifecycleOwner)
//            }
//        },
//        modifier = modifier
//    )
//}