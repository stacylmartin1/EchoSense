package com.google.ai.edge.gallery.ui.echosense.documenttranslator

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
fun DocumentTranslatorScreen(
    onNavigateUp: () -> Unit = {},
    viewModel: DocumentTranslatorViewModel,
    modelManagerViewModel: ModelManagerViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }

    val captureAndAnalyzeText = {
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
                else -> viewModel.loadTextFile(selectedUri)
            }
        }
    }

    val isProcessing by viewModel.isProcessing.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val objectDescription by viewModel.objectDescription.collectAsState()
    val translatedText by viewModel.translatedText.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val documentMode by viewModel.documentMode.collectAsState()
    val forceLlmOcr by viewModel.forceLlmOcr.collectAsState()
    val videoPreviewOn by AppSettings.videoPreviewEnabled.collectAsState()
    var showCamera by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
    val selectedModel = modelManagerUiState.selectedModel
    val modelInitStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
    val isModelReady = modelInitStatus?.status == ModelInitializationStatusType.INITIALIZED

    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        viewModel.setImageCaptureCallback { captureAndAnalyzeText() }
    }

    LaunchedEffect(isModelReady) { viewModel.checkAndAnnounceStatusChanges(isModelReady, isAnalyzing) }
    LaunchedEffect(modelInitStatus?.status) { viewModel.checkAndAnnounceStatusChanges(isModelReady, isAnalyzing) }

    DisposableEffect(Unit) { onDispose { viewModel.stopProcessing() } }

    // Show translated text for text-based modes, raw description for image modes
    val displayText = when (documentMode) {
        TranslatorMode.TEXT, TranslatorMode.PDF_TEXT -> translatedText
        TranslatorMode.IMAGE, TranslatorMode.PDF_IMAGE -> {
            if (translatedText.isNotEmpty()) translatedText else objectDescription
        }
        TranslatorMode.NONE -> ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Document Translator") },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.stopProcessing(); onNavigateUp() },
                        modifier = Modifier.semantics { contentDescription = "Navigate back" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showLanguageDialog = true },
                        modifier = Modifier.semantics { contentDescription = "Manage translation languages" }
                    ) {
                        Icon(Icons.Default.Language, contentDescription = "Languages")
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
                            "Take a photo or upload a file to translate it to English.",
                            modifier = Modifier.align(Alignment.Center),
                            color = Color.Gray
                        )
                    }
                }
            }

            if (totalPages > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.previousPage() }, enabled = currentPage > 0,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "Previous page" }
                    ) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous") }
                    Text("Page ${currentPage + 1} of $totalPages",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    IconButton(onClick = { viewModel.nextPage() }, enabled = currentPage < totalPages - 1,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "Next page" }
                    ) { Icon(Icons.Default.ChevronRight, contentDescription = "Next") }
                }
            }

            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()
                .semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Force LLM OCR Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Force LLM OCR for images and PDFs, currently ${if (forceLlmOcr) "enabled" else "disabled"}"
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Force LLM OCR",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = forceLlmOcr,
                        onCheckedChange = { viewModel.setForceLlmOcr(it) }
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(
                        onClick = {
                            showCamera = true
                            viewModel.startProcessing()
                            viewModel.announceAction("Translating")
                            captureAndAnalyzeText()
                        },
                        enabled = !isAnalyzing && hasCameraPermission,
                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                            .sizeIn(minHeight = 48.dp)
                            .semantics { contentDescription = "Take photo to translate" }
                    ) { Text(if (isAnalyzing && showCamera) "Translating..." else "Take Photo") }
                    Button(
                        onClick = {
                            showCamera = false
                            viewModel.announceAction("Translating")
                            filePickerLauncher.launch(arrayOf("text/*", "application/pdf", "image/*"))
                        },
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                            .sizeIn(minHeight = 48.dp)
                            .semantics { contentDescription = "Upload file to translate" }
                    ) { Text("Upload File") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.stopTranslating() },
                    enabled = isProcessing || isAnalyzing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                        .semantics { contentDescription = "Stop translation" }
                ) { Text("Stop", color = Color.White) }
            }
        }
    }

    // Language pack management dialog
    if (showLanguageDialog) {
        LanguagePackDialog(onDismiss = { showLanguageDialog = false })
    }
    // Debug Overlay & Model Status Toast
    val activeModelName by viewModel.activeModelName.collectAsState()
    val showDebugOverlay by AppSettings.showDebugOverlay.collectAsState()


    LaunchedEffect(activeModelName) {
        activeModelName?.let { model ->
            android.widget.Toast.makeText(context, "Analyzing with $model", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    if (showDebugOverlay) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .padding(top = 56.dp, start = 8.dp, end = 8.dp)
                .background(Color.Black.copy(alpha = 0.8f))
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Column {
                Text("DEBUG OVERLAY", color = Color.Green, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text("Mode: $documentMode", color = Color.Green)
                Text("Model: ${activeModelName ?: "None"}", color = Color.Green)
                Text("Page: ${currentPage + 1} / $totalPages", color = Color.Green)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Raw Text / Description:", color = Color.Yellow)
                Text(objectDescription, color = Color.White, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Translated Text:", color = Color.Cyan)
                Text(translatedText, color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun LanguagePackDialog(onDismiss: () -> Unit) {
    val downloadedLanguages by TranslationHelper.downloadedLanguages.collectAsState()
    val downloading by TranslationHelper.isDownloading.collectAsState()
    val allLanguages = remember { TranslationHelper.getAllSupportedLanguages() }

    LaunchedEffect(Unit) { TranslationHelper.refreshDownloadedLanguages() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Translation Languages") },
        text = {
            Column {
                Text(
                    "Download language packs for offline translation. English is always available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(modifier = Modifier.height(400.dp)) {
                    items(allLanguages) { lang ->
                        val isDownloaded = downloadedLanguages.contains(lang.code)
                        val isDownloadingThis = downloading.contains(lang.code)
                        val isEnglish = lang.code == "en"

                        ListItem(
                            headlineContent = {
                                Text(
                                    lang.displayName,
                                    color = if (isDownloaded || isEnglish)
                                        MaterialTheme.colorScheme.onSurface
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingContent = {
                                when {
                                    isEnglish -> {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Always available",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    isDownloadingThis -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                    isDownloaded -> {
                                        IconButton(
                                            onClick = { TranslationHelper.deleteLanguagePack(lang.code) },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete ${lang.displayName}",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                    else -> {
                                        IconButton(
                                            onClick = { TranslationHelper.downloadLanguagePack(lang.code) },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Download,
                                                contentDescription = "Download ${lang.displayName}",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable(enabled = !isEnglish && !isDownloadingThis) {
                                if (isDownloaded) {
                                    TranslationHelper.deleteLanguagePack(lang.code)
                                } else {
                                    TranslationHelper.downloadLanguagePack(lang.code)
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

private const val TAG = "DocTranslatorScreen"
