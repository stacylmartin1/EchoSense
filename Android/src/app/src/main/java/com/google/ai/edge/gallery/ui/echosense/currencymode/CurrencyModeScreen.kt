package com.google.ai.edge.gallery.ui.echosense.currencymode

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencyModeScreen(
    onNavigateUp: () -> Unit = {},
    viewModel: CurrencyModeViewModel,
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
                override fun onCaptureSuccess(imageProxy: ImageProxy) { viewModel.analyzeImage(imageProxy) }
                override fun onError(exception: ImageCaptureException) { Log.e(TAG, "Capture failed", exception) }
            }
        )
    }

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCameraPermission = it }

    var hasAudioPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasAudioPermission = it }

    val isProcessing by viewModel.isProcessing.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val objectDescription by viewModel.objectDescription.collectAsState()
    val videoPreviewOn by AppSettings.videoPreviewEnabled.collectAsState()

    val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
    val selectedModel = modelManagerUiState.selectedModel
    val modelInitStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
    val isModelReady = modelInitStatus?.status == ModelInitializationStatusType.INITIALIZED

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
                title = { Text("Currency Identifier") },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.stopProcessing(); onNavigateUp() },
                        modifier = Modifier.semantics { contentDescription = "Navigate back" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                        } catch (exc: Exception) { Log.e(TAG, "Binding failed", exc) }
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (!videoPreviewOn) { Box(modifier = Modifier.fillMaxSize().background(Color.Black)) }
            } else {
                Text("Camera permission is required.", modifier = Modifier.align(Alignment.Center))
            }

            if (objectDescription.isNotEmpty()) {
                val scrollState = rememberScrollState()
                Text(
                    text = objectDescription,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).background(Color.Black.copy(alpha = 0.7f)).padding(12.dp).verticalScroll(scrollState)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = Color.White
                )
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()
                    .semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        viewModel.startProcessing()
                        viewModel.announceAction("Identifying currency")
                        captureAndAnalyze()
                    },
                    enabled = !isAnalyzing && isModelReady,
                    modifier = Modifier.fillMaxWidth().height(60.dp).semantics {
                        contentDescription = if (!isModelReady) "Model loading, please wait" else if (isAnalyzing) "Identifying currency" else "Capture and identify bank note"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text(when { !isModelReady -> "Model Loading..."; isAnalyzing -> "Identifying..."; else -> "Capture Note" }, color = Color.White)
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
                        onClick = { viewModel.stopSpeaking(); viewModel.stopProcessing() },
                        enabled = isProcessing || isAnalyzing,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Stop current analysis and speech" }
                    ) { Text("Stop", color = Color.White) }
                }
            }
        }
    }
}

private const val TAG = "CurrencyModeScreen"
