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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.google.ai.edge.gallery.ui.home.OnlineConnectionDialog
import com.google.ai.edge.gallery.ui.echosense.EchoSenseActionButton
import com.google.ai.edge.gallery.ui.echosense.ARCoreDepthCameraView
import com.google.ai.edge.gallery.ui.echosense.OnlineAnalysisHelper
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationAssistanceScreen(
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
    var depthCameraView by remember { mutableStateOf<ARCoreDepthCameraView?>(null) }
    var depthUnavailable by remember { mutableStateOf(false) }
    var depthCameraActivated by remember { mutableStateOf(false) }

    val isCollisionAvoidanceEnabled by viewModel.isCollisionAvoidanceEnabled.collectAsState()
    val useDepthCamera = depthCameraActivated && !depthUnavailable

    LaunchedEffect(isCollisionAvoidanceEnabled) {
        if (isCollisionAvoidanceEnabled) depthCameraActivated = true
    }

    val captureAndAnalyze = {
        val depthFrame = if (useDepthCamera) depthCameraView?.captureCurrentFrame() else null
        if (depthFrame != null) {
            viewModel.analyzeCapturedBitmap(depthFrame)
        } else if (useDepthCamera) {
            viewModel.reportDepthCameraNotReady()
        } else {
            imageCapture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                        viewModel.analyzeImage(imageProxy)
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Image capture failed", exception)
                        viewModel.stopProcessing()
                    }
                }
            )
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionPromptResponded by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
            permissionPromptResponded = true
        }
    )

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasAudioPermission = granted
            permissionPromptResponded = true
        }
    )

    // Original state flows
    val isProcessing by viewModel.isProcessing.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val areSafetyAlertsSuppressed by viewModel.areSafetyAlertsSuppressed.collectAsState()
    val objectDescription by viewModel.objectDescription.collectAsState()
    val videoPreviewOn by AppSettings.videoPreviewEnabled.collectAsState()
    val onlineProvider by AppSettings.onlineProvider.collectAsState()
    val pendingVoiceCommand by viewModel.pendingVoiceCommand.collectAsState()
    val cloudPromptRequested by AppSettings.cloudPromptRequested.collectAsState()
    var showOnlineConnection by remember { mutableStateOf(false) }
    var confirmOnlineAnalysis by remember { mutableStateOf(false) }
    var pendingOnlineRequestIsVoice by remember { mutableStateOf(false) }

    val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
    val selectedModel = modelManagerUiState.selectedModel
    val modelDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]?.status
    val modelInitStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
    val isModelInstalled = modelDownloadStatus == ModelDownloadStatusType.SUCCEEDED
    val isModelDownloadInProgress =
        modelDownloadStatus == ModelDownloadStatusType.IN_PROGRESS ||
            modelDownloadStatus == ModelDownloadStatusType.UNZIPPING
    val isModelReady =
        selectedModel.instance != null &&
            modelInitStatus?.status == ModelInitializationStatusType.INITIALIZED

    // Collision avoidance state flows
    val boxes by viewModel.proximityBoxes.collectAsState()
    val bestBox by viewModel.proximityBest.collectAsState()
    val proximityAlert by viewModel.proximityAlert.collectAsState()
    val analysisSensorContext by viewModel.analysisSensorContext.collectAsState()

    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        if (!hasAudioPermission) audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(useDepthCamera, depthCameraView) {
        viewModel.setImageCaptureCallback { captureAndAnalyze() }
    }
    LaunchedEffect(pendingVoiceCommand) {
        if (pendingVoiceCommand != null) {
            if (OnlineAnalysisHelper.isAvailable() && OnlineAnalysisHelper.hasValidatedInternet(context)) {
                pendingOnlineRequestIsVoice = true
                confirmOnlineAnalysis = true
            } else {
                viewModel.analyzePendingVoiceCommand(useOnline = false)
            }
        }
    }

    LaunchedEffect(selectedModel.name, modelDownloadStatus, isModelReady, isAnalyzing) {
        if (modelDownloadStatus != null) {
            viewModel.checkAndAnnounceStatusChanges(
                modelName = selectedModel.name,
                isModelInstalled = isModelInstalled,
                isModelDownloadInProgress = isModelDownloadInProgress,
                isModelReady = isModelReady,
                isAnalyzing = isAnalyzing,
            )
        }
    }
    LaunchedEffect(permissionPromptResponded, hasCameraPermission, hasAudioPermission) {
        if (permissionPromptResponded && hasCameraPermission && hasAudioPermission) {
            delay(750)
            if (modelDownloadStatus != null) {
                viewModel.retryStartupStatusAnnouncementAfterPermission(
                    modelName = selectedModel.name,
                    isModelInstalled = isModelInstalled,
                    isModelDownloadInProgress = isModelDownloadInProgress,
                    isModelReady = isModelReady,
                    isAnalyzing = isAnalyzing,
                )
            }
        }
    }

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
                title = {
                    Column {
                        Text("Navigation Assistance")
                        if (analysisSensorContext.isNotEmpty()) {
                            Text(
                                analysisSensorContext,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (hasCameraPermission) {
                if (useDepthCamera) {
                    AndroidView(
                        factory = { ctx ->
                            ARCoreDepthCameraView(ctx).also { view ->
                                depthCameraView = view
                                view.setSafetyProcessingEnabled(isCollisionAvoidanceEnabled)
                                view.onDepthAvailabilityChanged = { available, reason ->
                                    viewModel.updateDepthAvailability(available, reason)
                                    depthUnavailable = !available
                                }
                                view.onDepthObservations = viewModel::updateDepthObservations
                                view.onCameraFrame = viewModel::analyzeProximityBitmap
                                view.attach(lifecycleOwner)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { view ->
                            view.setSafetyProcessingEnabled(isCollisionAvoidanceEnabled)
                        },
                        onRelease = { view ->
                            if (depthCameraView === view) depthCameraView = null
                            view.detach()
                        },
                    )
                } else {
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
                                        if (viewModel.isAnalyzing.value) {
                                            proxy.close()
                                            return@setAnalyzer
                                        }
                                        try { viewModel.analyzeProximity(proxy) }
                                        catch (t: Throwable) { Log.e(TAG, "Analyzer error", t) }
                                    }
                                }

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageCapture,
                                    imageAnalysis,
                                )
                            } catch (exc: Exception) {
                                Log.e(TAG, "Use case binding failed", exc)
                            }
                            previewView
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { cameraProviderFuture.get().unbindAll() },
                    )
                }
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

            proximityAlert?.takeUnless { areSafetyAlertsSuppressed }?.let { alert ->
                val distance = alert.distanceMeters?.let { " · %.1f m".format(it) }.orEmpty()
                val rangeSource = if (alert.distanceMeters != null) "ARCore range" else "Camera estimate"
                Text(
                    text = "${alert.label}$distance · ${alert.bearing.name.lowercase()} · $rangeSource",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (objectDescription.isEmpty()) 12.dp else 92.dp, start = 12.dp, end = 12.dp)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = if (alert.severity == com.google.ai.edge.gallery.ui.echosense.ProximitySeverity.URGENT) {
                        Color(0xFFFF5252)
                    } else {
                        Color.White
                    },
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EchoSenseActionButton(
                    icon = Icons.Default.CameraAlt,
                    label = "Analyze",
                    contentDescription = if (!isModelReady) "Model loading, please wait" else if (isAnalyzing) "Analyzing scene" else "Analyze scene",
                    onClick = {
                        if (OnlineAnalysisHelper.isAvailable() && OnlineAnalysisHelper.hasValidatedInternet(context)) {
                            pendingOnlineRequestIsVoice = false
                            confirmOnlineAnalysis = true
                        } else {
                            viewModel.startOnDeviceProcessing()
                            captureAndAnalyze()
                        }
                    },
                    enabled = !isAnalyzing && isModelReady,
                    highlighted = true,
                    highlightColor = Color(0xFF4CAF50),
                )
                EchoSenseActionButton(
                    icon = Icons.Default.Mic,
                    label = "Voice",
                    contentDescription = "Activate voice command",
                    onClick = { if (hasAudioPermission) viewModel.startListening() else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    enabled = !isAnalyzing,
                )
                EchoSenseActionButton(
                    icon = Icons.Default.Stop,
                    label = "Stop",
                    contentDescription = "Stop current analysis and speech",
                    onClick = { viewModel.stopSpeaking() },
                    enabled = isProcessing,
                    highlighted = true,
                    highlightColor = Color(0xFFF44336),
                )
                EchoSenseActionButton(
                    icon = if (isCollisionAvoidanceEnabled) Icons.Filled.Shield else Icons.Outlined.Shield,
                    label = "Safety",
                    contentDescription = if (isCollisionAvoidanceEnabled) "Disable collision avoidance" else "Enable collision avoidance",
                    onClick = { viewModel.toggleCollisionAvoidance() },
                    highlighted = isCollisionAvoidanceEnabled,
                    highlightColor = Color(0xFFFF9800),
                )
            }
        }
    }

    if (cloudPromptRequested) {
        AlertDialog(
            onDismissRequest = { AppSettings.dismissOnlinePrompt(context) },
            title = { Text("Optional Online Analysis") },
            text = { Text("Connect your own AI provider for optional online scene analysis. Your key stays on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    AppSettings.beginOnlineSetup()
                    showOnlineConnection = true
                }) { Text("Connect") }
            },
            dismissButton = {
                TextButton(onClick = { AppSettings.dismissOnlinePrompt(context) }) { Text("Not now") }
            },
        )
    }

    if (confirmOnlineAnalysis) {
        val useOnDevice = {
            confirmOnlineAnalysis = false
            if (pendingOnlineRequestIsVoice) {
                viewModel.analyzePendingVoiceCommand(useOnline = false)
            } else {
                viewModel.startOnDeviceProcessing()
                captureAndAnalyze()
            }
            pendingOnlineRequestIsVoice = false
        }
        AlertDialog(
            onDismissRequest = useOnDevice,
            title = { Text("Use ${onlineProvider.displayName} analysis?") },
            text = {
                Text(
                    if (pendingOnlineRequestIsVoice) {
                        "Your voice request and current image will be sent to ${onlineProvider.displayName}. Provider charges may apply. Use on-device to keep them on this device."
                    } else {
                        "The current image and prompt will be sent to ${onlineProvider.displayName}. Provider charges may apply. Use on-device to keep them on this device."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOnlineAnalysis = false
                    AppSettings.onlineConsentGranted.value = true
                    modelManagerViewModel.saveEchoSenseSettings()
                    if (pendingOnlineRequestIsVoice) {
                        viewModel.analyzePendingVoiceCommand(useOnline = true)
                    } else {
                        viewModel.startOnlineProcessing()
                        captureAndAnalyze()
                    }
                    pendingOnlineRequestIsVoice = false
                }) { Text("Use Online") }
            },
            dismissButton = {
                TextButton(onClick = useOnDevice) { Text("Use On-device") }
            },
        )
    }

    if (showOnlineConnection) {
        OnlineConnectionDialog(
            modelManagerViewModel = modelManagerViewModel,
            onDismiss = { showOnlineConnection = false },
        )
    }
}

private const val TAG = "NavAssistScreen"
