package com.google.ai.edge.gallery.ui.echosense

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview as ComposePreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.background
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualAssistanceScreen(
    onNavigateUp: () -> Unit = {},
    viewModel: EchoSenseViewModel,
    modelManagerViewModel: com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasAudioPermission = granted
        }
    )

    val isProcessing by viewModel.isProcessing.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val objectDescription by viewModel.objectDescription.collectAsState()
    
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    
    // Get model initialization status from the passed ModelManagerViewModel
    val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
    val selectedModel = modelManagerUiState.selectedModel
    val modelInitStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
    val isModelReady = modelInitStatus?.status == ModelInitializationStatusType.INITIALIZED
    //val isModelReady = true //SLM

    // Function to capture a single image and analyze it
    fun captureAndAnalyze() {
        imageCapture?.let { capture ->
            Log.d("VisualAssistanceScreen", "Taking single picture for analysis")
            val outputFileOptions = ImageCapture.OutputFileOptions.Builder(
                context.cacheDir.resolve("temp_image_${System.currentTimeMillis()}.jpg")
            ).build()
            
            capture.takePicture(
                outputFileOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        Log.d("VisualAssistanceScreen", "Image saved successfully")
                        // Convert to bitmap and analyze
                        output.savedUri?.let { uri ->
                            try {
                                val bitmap = android.provider.MediaStore.Images.Media.getBitmap(
                                    context.contentResolver, uri
                                )
                                viewModel.analyzeImage(bitmap)
                            } catch (e: Exception) {
                                Log.e("VisualAssistanceScreen", "Error loading image", e)
                                viewModel.stopProcessing()
                            }
                        }
                    }
                    
                    override fun onError(exception: ImageCaptureException) {
                        Log.e("VisualAssistanceScreen", "Image capture failed", exception)
                        viewModel.stopProcessing()
                    }
                }
            )
        } ?: run {
            Log.w("VisualAssistanceScreen", "ImageCapture is null, cannot capture")
            viewModel.stopProcessing()
        }
    }

    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        if (!hasAudioPermission) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        
        // Set up the image capture callback for voice commands
        viewModel.setImageCaptureCallback {
            captureAndAnalyze()
        }
    }
    
    // Monitor status changes and announce them
    LaunchedEffect(isModelReady, isAnalyzing) {
        viewModel.checkAndAnnounceStatusChanges(isModelReady, isAnalyzing)
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopProcessing()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EchoSense Visual Assistance") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stopProcessing()
                        onNavigateUp()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (hasCameraPermission) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageCaptureUseCase = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()
                        
                        imageCapture = imageCaptureUseCase

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageCaptureUseCase
                            )
                        } catch (exc: Exception) {
                            Log.e("VisualAssistanceScreen", "Use case binding failed", exc)
                        }

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("Camera permission is required to use this feature.", modifier = Modifier.align(Alignment.Center))
            }

            if (objectDescription.isNotEmpty()) {
                Text(
                    text = objectDescription,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(12.dp),
                    color = Color.White
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            viewModel.startProcessing()
                            captureAndAnalyze()
                        },
                        enabled = !isProcessing && !isAnalyzing && isModelReady,
                        modifier = Modifier.height(60.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text(
                            when {
                                !isModelReady -> "Model Loading..."
                                isAnalyzing -> "Analyzing..."
                                else -> "Start"
                            },
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = { viewModel.stopProcessing() },
                        enabled = isProcessing || isAnalyzing,
                        modifier = Modifier.height(60.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                    ) {
                        Text("Stop", color = Color.White)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    if (hasAudioPermission) {
                        viewModel.startListening()
                    } else {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) {
                    Text("Use Voice Command")
                }
            }
        }
    }
}

@ComposePreview
@Composable
fun VisualAssistanceScreenPreview() {
    // Preview with mock ViewModel - this won't work in actual preview
    // but prevents compilation errors
}