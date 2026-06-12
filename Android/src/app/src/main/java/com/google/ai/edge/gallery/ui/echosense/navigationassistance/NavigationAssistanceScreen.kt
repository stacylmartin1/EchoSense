package com.google.ai.edge.gallery.ui.echosense.navigationassistance

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.ui.home.AppSettings
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationAssistanceScreen(
    onNavigateUp: () -> Unit = {},
    viewModel: NavigationAssistanceViewModel,
    modelManagerViewModel: ModelManagerViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setTargetResolution(android.util.Size(768, 768))
            .build()
    }

    val captureAndAnalyze = {
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    viewModel.analyzeImage(imageProxy)
                }
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed", exception)
                }
            }
        )
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasAudioPermission = granted }
    )

    // Original state flows
    val isProcessing by viewModel.isProcessing.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val objectDescription by viewModel.objectDescription.collectAsState()
    val videoPreviewOn by AppSettings.videoPreviewEnabled.collectAsState()

    val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
    val selectedModel = modelManagerUiState.selectedModel
    val modelInitStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
    val isModelReady = modelInitStatus?.status == ModelInitializationStatusType.INITIALIZED

    // Collision avoidance state flows
    val boxes by viewModel.proximityBoxes.collectAsState()
    val bestBox by viewModel.proximityBest.collectAsState()
    val isCollisionAvoidanceEnabled by viewModel.isCollisionAvoidanceEnabled.collectAsState()

    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        if (!hasAudioPermission) audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        viewModel.setImageCaptureCallback { captureAndAnalyze() }
    }

    LaunchedEffect(isModelReady) { viewModel.checkAndAnnounceStatusChanges(isModelReady, isAnalyzing) }
    LaunchedEffect(isAnalyzing) { if (isAnalyzing) viewModel.checkAndAnnounceStatusChanges(isModelReady, isAnalyzing) }
    LaunchedEffect(modelInitStatus?.status) { viewModel.checkAndAnnounceStatusChanges(isModelReady, isAnalyzing) }

    DisposableEffect(Unit) { onDispose { viewModel.stopProcessing() } }

    // Show model name Toast when model changes
    val activeModelName by viewModel.activeModelName.collectAsState()
    LaunchedEffect(activeModelName) {
        activeModelName?.let { model ->
            android.widget.Toast.makeText(context, "Analyzing with $model", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Navigation Assistance") },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.stopProcessing(); onNavigateUp() },
                        modifier = Modifier.semantics { contentDescription = "Navigate back" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Toggle Collision Avoidance
                    IconButton(
                        onClick = { viewModel.toggleCollisionAvoidance() },
                        modifier = Modifier.semantics {
                            contentDescription = if (isCollisionAvoidanceEnabled) "Disable collision avoidance" else "Enable collision avoidance"
                        }
                    ) {
                        Icon(
                            imageVector = if (isCollisionAvoidanceEnabled) Icons.Filled.Shield else Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = if (isCollisionAvoidanceEnabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (hasCameraPermission) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        // Proximity detector — paused while LLM is running to avoid
                        // GPU/NPU resource contention that causes native crashes.
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build().also { analysis ->
                                analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { proxy ->
                                    // Skip proximity frames while the LLM is actively analyzing
                                    // to avoid resource contention with the inference engine.
                                    if (viewModel.isAnalyzing.value) {
                                        proxy.close()
                                        return@setAnalyzer
                                    }
                                    try { viewModel.analyzeProximity(proxy) }
                                    catch (t: Throwable) { Log.e(TAG, "Analyzer error", t) }
                                    // analyzeProximity closes proxy
                                }
                            }

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, imageAnalysis)
                        } catch (exc: Exception) {
                            Log.e(TAG, "Use case binding failed", exc)
                        }
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (!videoPreviewOn) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                }

                // Detection overlay
                if (videoPreviewOn && isCollisionAvoidanceEnabled) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val imgW = boxes.firstOrNull()?.imageWidth ?: 0
                        val imgH = boxes.firstOrNull()?.imageHeight ?: 0
                        if (imgW > 0 && imgH > 0) {
                            val sx = size.width / imgW
                            val sy = size.height / imgH
                            boxes.forEach { b ->
                                val isBest = bestBox != null && b == bestBox
                                drawRect(
                                    color = if (isBest) Color(0xFFFF5252) else Color(0xFF00E676),
                                    topLeft = Offset(b.x * sx, b.y * sy),
                                    size = Size(b.width * sx, b.height * sy),
                                    style = Stroke(width = if (isBest) 10f else 6f)
                                )
                            }
                        }
                    }
                }

            } else {
                Text("Camera permission is required.", modifier = Modifier.align(Alignment.Center))
            }

            if (objectDescription.isNotEmpty()) {
                val scrollState = rememberScrollState()
                Text(
                    text = objectDescription,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(12.dp)
                        .verticalScroll(scrollState)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = Color.White
                )
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()
                    .semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Collision Avoidance toggle button
                Button(
                    onClick = { viewModel.toggleCollisionAvoidance() },
                    modifier = Modifier.fillMaxWidth().height(52.dp).semantics {
                        contentDescription = if (isCollisionAvoidanceEnabled) "Disable collision avoidance" else "Enable collision avoidance"
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCollisionAvoidanceEnabled) Color(0xFFFF9800) else Color(0xFF757575)
                    )
                ) {
                    Icon(
                        imageVector = if (isCollisionAvoidanceEnabled) Icons.Filled.Shield else Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = if (isCollisionAvoidanceEnabled) "Collision Avoidance: ON" else "Collision Avoidance: OFF",
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.startProcessing(); captureAndAnalyze() },
                    enabled = !isAnalyzing && isModelReady,
                    modifier = Modifier.fillMaxWidth().height(60.dp).semantics { contentDescription = if (!isModelReady) "Model loading, please wait" else if (isAnalyzing) "Analyzing scene" else "Analyze scene" },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text(
                        when {
                            !isModelReady -> "Model Loading..."
                            isAnalyzing -> "Analyzing..."
                            else -> "Analyze Scene"
                        },
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.semantics(mergeDescendants = true) {}
                ) {
                    Button(
                        onClick = { if (hasAudioPermission) viewModel.startListening() else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Activate voice command" }
                    ) { Text("Voice Command") }
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = { viewModel.stopSpeaking() },
                        enabled = isProcessing,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Stop current analysis and speech" }
                    ) { Text("Stop", color = Color.White) }
                }
            }
        }
    }
}

private const val TAG = "NavAssistScreen"
