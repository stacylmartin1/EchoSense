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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.google.ai.edge.gallery.ui.echosense.EchoSenseActionButton
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentReaderScreen(
    viewModel: DocumentReaderViewModel,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setTargetResolution(android.util.Size(768, 768))
            .build()
    }

    var pendingCameraAction by remember { mutableStateOf(CameraReadAction.READ_TEXT) }
    var captureAfterCameraStarts by remember { mutableStateOf(false) }
    val captureAndAnalyze = {
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    when (pendingCameraAction) {
                        CameraReadAction.READ_TEXT -> viewModel.analyzeImageWithOcr(imageProxy)
                        CameraReadAction.COLOR -> viewModel.identifyCenterColor(imageProxy)
                        CameraReadAction.LIGHT -> viewModel.measureLightLevel(imageProxy)
                        CameraReadAction.CODE -> viewModel.scanBarcodeOrQrCode(imageProxy)
                    }
                }
                override fun onError(exception: ImageCaptureException) { Log.e(TAG, "Capture failed", exception) }
            }
        )
    }

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCameraPermission = it
    }

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
    var showCamera by remember { mutableStateOf(true) }
    var showMagnifier by remember { mutableStateOf(false) }

    if (showMagnifier) {
        MagnifierScreen(
            viewModel = viewModel,
            onClose = { showMagnifier = false },
        )
        return
    }

    LaunchedEffect(showCamera, captureAfterCameraStarts) {
        if (showCamera && captureAfterCameraStarts) {
            delay(350)
            captureAfterCameraStarts = false
            captureAndAnalyze()
        }
    }

    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        viewModel.setImageCaptureCallback { captureAndAnalyze() }
    }

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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EchoSenseActionButton(
                    icon = Icons.Default.CameraAlt,
                    label = "Read",
                    contentDescription = "Take photo of document",
                    onClick = {
                        if (!showCamera) {
                            showCamera = true
                            viewModel.announceAction("Camera ready. Tap Read to capture.")
                        } else {
                            pendingCameraAction = CameraReadAction.READ_TEXT
                            viewModel.startProcessing()
                            viewModel.announceAction("Reading document")
                            captureAndAnalyze()
                        }
                    },
                    enabled = !isAnalyzing && hasCameraPermission,
                    highlighted = true,
                    highlightColor = Color(0xFF4CAF50),
                )
                EchoSenseActionButton(
                    icon = Icons.Default.Palette,
                    label = "Color",
                    contentDescription = "Identify the color at the center of the camera view",
                    onClick = {
                        pendingCameraAction = CameraReadAction.COLOR
                        if (showCamera) {
                            captureAndAnalyze()
                        } else {
                            captureAfterCameraStarts = true
                            showCamera = true
                        }
                    },
                    enabled = !isAnalyzing && hasCameraPermission,
                )
                EchoSenseActionButton(
                    icon = Icons.Default.LightMode,
                    label = "Light",
                    contentDescription = "Measure the approximate light level seen by the camera",
                    onClick = {
                        pendingCameraAction = CameraReadAction.LIGHT
                        if (showCamera) {
                            captureAndAnalyze()
                        } else {
                            captureAfterCameraStarts = true
                            showCamera = true
                        }
                    },
                    enabled = !isAnalyzing && hasCameraPermission,
                )
                EchoSenseActionButton(
                    icon = Icons.Default.QrCodeScanner,
                    label = "Codes",
                    contentDescription = "Scan a barcode or QR code",
                    onClick = {
                        pendingCameraAction = CameraReadAction.CODE
                        if (showCamera) {
                            captureAndAnalyze()
                        } else {
                            captureAfterCameraStarts = true
                            showCamera = true
                        }
                    },
                    enabled = !isAnalyzing && hasCameraPermission,
                )
                EchoSenseActionButton(
                    icon = Icons.Default.ZoomIn,
                    label = "Magnify",
                    contentDescription = "Open camera magnifier",
                    onClick = { showMagnifier = true },
                    enabled = hasCameraPermission,
                )
                EchoSenseActionButton(
                    icon = Icons.Default.UploadFile,
                    label = "Upload",
                    contentDescription = "Upload file to read",
                    onClick = {
                        showCamera = false
                        filePickerLauncher.launch(arrayOf("text/*", "application/pdf", "image/*"))
                    },
                )
                EchoSenseActionButton(
                    icon = Icons.Default.Stop,
                    label = "Stop",
                    contentDescription = "Stop reading aloud",
                    onClick = { viewModel.stopReading() },
                    enabled = isProcessing || isAnalyzing,
                    highlighted = true,
                    highlightColor = Color(0xFFF44336),
                )
            }
        }
    }
}

private const val TAG = "DocumentReaderScreen"

private enum class CameraReadAction {
    READ_TEXT,
    COLOR,
    LIGHT,
    CODE,
}
