package com.google.ai.edge.gallery.ui.echosense.documentreader

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.ui.home.AppSettings
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentReaderScreen(
    onNavigateUp: () -> Unit = {},
    viewModel: DocumentReaderViewModel,
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
                override fun onCaptureSuccess(imageProxy: ImageProxy) { viewModel.analyzeImageWithOcr(imageProxy) }
                override fun onError(exception: ImageCaptureException) { Log.e(TAG, "Capture failed", exception) }
            }
        )
    }

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCameraPermission = it }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedUri ->
            val mimeType = context.contentResolver.getType(selectedUri)
            when {
                mimeType?.startsWith("text/") == true -> viewModel.loadTextFile(selectedUri)
                mimeType == "application/pdf" -> viewModel.loadPdfFile(selectedUri)
                mimeType?.startsWith("image/") == true -> viewModel.loadImageFile(selectedUri)
                else -> viewModel.loadTextFile(selectedUri) // Try as text
            }
        }
    }

    val isProcessing by viewModel.isProcessing.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val objectDescription by viewModel.objectDescription.collectAsState()
    val documentText by viewModel.documentText.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val documentMode by viewModel.documentMode.collectAsState()
    val videoPreviewOn by AppSettings.videoPreviewEnabled.collectAsState()
    var showCamera by remember { mutableStateOf(false) }

    val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
    val selectedModel = modelManagerUiState.selectedModel
    val modelInitStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
    val isModelReady = modelInitStatus?.status == ModelInitializationStatusType.INITIALIZED

    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        viewModel.setImageCaptureCallback { captureAndAnalyze() }
    }

    LaunchedEffect(isModelReady) { viewModel.checkAndAnnounceStatusChanges(isModelReady, isAnalyzing) }
    LaunchedEffect(modelInitStatus?.status) { viewModel.checkAndAnnounceStatusChanges(isModelReady, isAnalyzing) }

    DisposableEffect(Unit) { onDispose { viewModel.stopProcessing() } }

    // Show model name Toast when model changes
    val activeModelName by viewModel.activeModelName.collectAsState()
    LaunchedEffect(activeModelName) {
        activeModelName?.let { model ->
            android.widget.Toast.makeText(context, "Analyzing with $model", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Text-based docs (TXT, MD, text-PDFs) show the extracted text directly.
    // Image-based docs (scanned PDFs, photos) show the LLM output.
    val displayText = when (documentMode) {
        DocumentMode.TEXT, DocumentMode.PDF_TEXT -> documentText
        else -> objectDescription
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Document Reader") },
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
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (showCamera && hasCameraPermission) {
                Box(modifier = Modifier.weight(1f)) {
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
                }
            } else {
                // Text display area
                Box(modifier = Modifier.weight(1f).padding(16.dp)) {
                    if (displayText.isNotEmpty()) {
                        val scrollState = rememberScrollState()
                        Text(
                            text = displayText,
                            modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            fontSize = 16.sp
                        )
                    } else {
                        Text(
                            "Take a photo of a document or upload a file to read it aloud.",
                            modifier = Modifier.align(Alignment.Center),
                            color = Color.Gray
                        )
                    }
                }
            }

            // Page controls
            if (totalPages > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { viewModel.previousPage() },
                        enabled = currentPage > 0,
                        modifier = Modifier.weight(1f).height(56.dp)
                            .semantics { contentDescription = "Previous page" },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                    ) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = Color.White)
                        Text("Previous", color = Color.White)
                    }
                    Text(
                        "${currentPage + 1} / $totalPages",
                        fontSize = 18.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    Button(
                        onClick = { viewModel.nextPage() },
                        enabled = currentPage < totalPages - 1,
                        modifier = Modifier.weight(1f).height(56.dp)
                            .semantics { contentDescription = "Next page" },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                    ) {
                        Text("Next", color = Color.White)
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.White)
                    }
                }
            }

            // Action buttons
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()
                .semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(
                        onClick = {
                            if (!showCamera) {
                                // First click: show camera preview, wait for binding
                                showCamera = true
                                viewModel.announceAction("Camera ready. Tap Take Photo to capture.")
                            } else {
                                // Camera already showing: capture + analyze
                                viewModel.startProcessing()
                                viewModel.announceAction("Reading document")
                                captureAndAnalyze()
                            }
                        },
                        enabled = !isAnalyzing && hasCameraPermission,
                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                            .sizeIn(minHeight = 48.dp)
                            .semantics { contentDescription = "Take photo of document" }
                    ) { Text(if (isAnalyzing && showCamera) "Reading..." else "Take Photo") }
                    Button(
                        onClick = {
                            showCamera = false
                            filePickerLauncher.launch(arrayOf("text/*", "application/pdf", "image/*"))
                        },
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                            .sizeIn(minHeight = 48.dp)
                            .semantics { contentDescription = "Upload file to read" }
                    ) { Text("Upload File") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(
                        onClick = { viewModel.stopReading() },
                        enabled = isProcessing || isAnalyzing,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                            .semantics { contentDescription = "Stop reading aloud" }
                    ) { Text("Stop Reading", color = Color.White) }
                }
            }
        }
    }
}

private const val TAG = "DocumentReaderScreen"
