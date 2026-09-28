/*
 * Copyright 2026 TerraNet Technologies LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.terranet.echosense.android.ui.echosense.currencymode

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.terranet.echosense.android.ui.echosense.EchoSenseActionButton
import com.terranet.echosense.android.data.ModelDownloadStatusType
import com.terranet.echosense.android.ui.home.AppSettings
import com.terranet.echosense.android.ui.modelmanager.ModelInitializationStatusType
import com.terranet.echosense.android.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencyModeScreen(
    viewModel: CurrencyModeViewModel,
    modelManagerViewModel: ModelManagerViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
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
    var permissionPromptResponded by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCameraPermission = it
        permissionPromptResponded = true
    }

    var hasAudioPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasAudioPermission = it
        permissionPromptResponded = true
    }

    val isProcessing by viewModel.isProcessing.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val objectDescription by viewModel.objectDescription.collectAsState()
    val videoPreviewOn by AppSettings.videoPreviewEnabled.collectAsState()

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

    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        if (!hasAudioPermission) audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        viewModel.setImageCaptureCallback { captureAndAnalyze() }
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
                        Text("Currency Identifier")
                        Text("USD · CAD · EUR · GBP · CHF", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                    }
                },
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

            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EchoSenseActionButton(
                    icon = Icons.Default.CameraAlt,
                    label = "Identify",
                    contentDescription = if (!isModelReady) "Model loading, please wait" else if (isAnalyzing) "Identifying currency" else "Capture and identify bank note",
                    onClick = {
                        viewModel.startProcessing()
                        viewModel.announceAction("Identifying currency")
                        captureAndAnalyze()
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
                )
                EchoSenseActionButton(
                    icon = Icons.Default.Stop,
                    label = "Stop",
                    contentDescription = "Stop current analysis and speech",
                    onClick = { viewModel.stopSpeaking(); viewModel.stopProcessing() },
                    enabled = isProcessing || isAnalyzing,
                    highlighted = true,
                    highlightColor = Color(0xFFF44336),
                )
            }
        }
    }
}

private const val TAG = "CurrencyModeScreen"
