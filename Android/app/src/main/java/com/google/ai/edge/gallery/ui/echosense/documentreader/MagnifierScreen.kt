package com.google.ai.edge.gallery.ui.echosense.documentreader

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.util.Log
import android.widget.ImageView
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.ui.echosense.EchoSenseActionButton
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagnifierScreen(
    viewModel: DocumentReaderViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var frozenImage by remember { mutableStateOf<Bitmap?>(null) }
    var frozenAtZoom by remember { mutableFloatStateOf(1f) }
    var zoom by remember { mutableFloatStateOf(2f) }
    var maxZoom by remember { mutableFloatStateOf(8f) }
    var contrast by remember { mutableStateOf(false) }
    var inverted by remember { mutableStateOf(false) }
    var grayscale by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }

    val effect = remember(contrast, inverted, grayscale) {
        magnifierRenderEffect(contrast, inverted, grayscale)
    }

    DisposableEffect(Unit) {
        onDispose {
            camera?.cameraControl?.enableTorch(false)
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (_: Exception) {
            }
            frozenImage?.recycle()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Magnifier") },
                actions = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.semantics {
                            contentDescription = "Close magnifier"
                        },
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (frozenImage == null) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).also { previewView ->
                                // TextureView-backed compatibility mode allows Android
                                // RenderEffect filters to apply to the live preview.
                                previewView.implementationMode =
                                    PreviewView.ImplementationMode.COMPATIBLE
                                previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                                try {
                                    val provider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }
                                    provider.unbindAll()
                                    val boundCamera = provider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageCapture,
                                    )
                                    camera = boundCamera
                                    val supportedMax =
                                        boundCamera.cameraInfo.zoomState.value?.maxZoomRatio ?: 8f
                                    maxZoom = min(8f, supportedMax)
                                    zoom = zoom.coerceIn(1f, maxZoom)
                                    boundCamera.cameraControl.setZoomRatio(zoom)
                                } catch (error: Exception) {
                                    Log.e(MAGNIFIER_TAG, "Unable to bind magnifier camera", error)
                                }
                            }
                        },
                        update = { previewView ->
                            previewView.setRenderEffect(effect)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = "Live magnified camera view" },
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            ImageView(ctx).also {
                                it.scaleType = ImageView.ScaleType.CENTER_CROP
                            }
                        },
                        update = { imageView ->
                            imageView.setImageBitmap(frozenImage)
                            val relativeZoom = (zoom / frozenAtZoom).coerceAtLeast(1f)
                            imageView.scaleX = relativeZoom
                            imageView.scaleY = relativeZoom
                            imageView.setRenderEffect(effect)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = "Frozen magnified image" },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111111))
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    text = "Zoom ${"%.1f".format(zoom)} times",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Slider(
                    value = zoom,
                    onValueChange = { value ->
                        zoom = value
                        if (frozenImage == null) {
                            camera?.cameraControl?.setZoomRatio(value)
                        }
                    },
                    valueRange = 1f..maxZoom.coerceAtLeast(1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .semantics { contentDescription = "Magnification zoom" },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EchoSenseActionButton(
                        icon = if (frozenImage == null) Icons.Default.Pause else Icons.Default.PlayArrow,
                        label = if (frozenImage == null) "Freeze" else "Live",
                        contentDescription =
                            if (frozenImage == null) "Freeze magnified image" else "Return to live camera",
                        onClick = {
                            if (frozenImage != null) {
                                frozenImage?.recycle()
                                frozenImage = null
                                camera?.cameraControl?.setZoomRatio(zoom)
                            } else {
                                imageCapture.takePicture(
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            viewModel.captureMagnifierFrame(image) { bitmap ->
                                                frozenAtZoom = zoom
                                                frozenImage = bitmap
                                            }
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            Log.e(MAGNIFIER_TAG, "Unable to freeze image", exception)
                                        }
                                    },
                                )
                            }
                        },
                    )
                    EchoSenseActionButton(
                        icon = Icons.Default.Contrast,
                        label = "Contrast",
                        contentDescription = "Toggle high contrast",
                        onClick = { contrast = !contrast },
                        highlighted = contrast,
                    )
                    EchoSenseActionButton(
                        icon = Icons.Default.InvertColors,
                        label = "Invert",
                        contentDescription = "Toggle inverted colors",
                        onClick = { inverted = !inverted },
                        highlighted = inverted,
                    )
                    EchoSenseActionButton(
                        icon = Icons.Default.Tonality,
                        label = "Gray",
                        contentDescription = "Toggle grayscale",
                        onClick = { grayscale = !grayscale },
                        highlighted = grayscale,
                    )
                    EchoSenseActionButton(
                        icon = Icons.Default.FlashlightOn,
                        label = "Light",
                        contentDescription = "Toggle flashlight",
                        onClick = {
                            torchEnabled = !torchEnabled
                            camera?.cameraControl?.enableTorch(torchEnabled)
                        },
                        highlighted = torchEnabled,
                        enabled = camera?.cameraInfo?.hasFlashUnit() != false,
                    )
                }
            }
        }
    }
}

private fun magnifierRenderEffect(
    highContrast: Boolean,
    inverted: Boolean,
    grayscale: Boolean,
): RenderEffect? {
    if (!highContrast && !inverted && !grayscale) return null

    val combined = ColorMatrix()
    if (grayscale) {
        combined.postConcat(ColorMatrix().apply { setSaturation(0f) })
    }
    if (highContrast) {
        val scale = 1.65f
        val offset = (1f - scale) * 127.5f
        combined.postConcat(
            ColorMatrix(
                floatArrayOf(
                    scale, 0f, 0f, 0f, offset,
                    0f, scale, 0f, 0f, offset,
                    0f, 0f, scale, 0f, offset,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
    if (inverted) {
        combined.postConcat(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
    return RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(combined))
}

private const val MAGNIFIER_TAG = "MagnifierScreen"
