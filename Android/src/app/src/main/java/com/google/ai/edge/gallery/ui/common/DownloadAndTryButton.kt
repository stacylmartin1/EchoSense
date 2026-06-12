/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.common

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGDownloadAndTryButton"
private const val SYSTEM_RESERVED_MEMORY_IN_BYTES = 3 * (1L shl 30)

/**
 * Handles the "Download & Try it" button click, managing the model download process.
 *
 * For models requiring download first, the download starts directly.
 * If the model doesn't need to be downloaded first, the provided `onClicked` callback is executed.
 */
@Composable
fun DownloadAndTryButton(
  task: Task,
  model: Model,
  enabled: Boolean,
  downloadStatus: ModelDownloadStatus?,
  modelManagerViewModel: ModelManagerViewModel,
  onClicked: () -> Unit,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
  canShowTryIt: Boolean = true,
) {
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var showErrorDialog by remember { mutableStateOf(false) }
  var showMemoryWarning by remember { mutableStateOf(false) }
  var downloadStarted by remember { mutableStateOf(false) }

  val needToDownloadFirst =
    (downloadStatus?.status == ModelDownloadStatusType.NOT_DOWNLOADED ||
      downloadStatus?.status == ModelDownloadStatusType.FAILED) &&
      model.localFileRelativeDirPathOverride.isEmpty()
  val inProgress = downloadStatus?.status == ModelDownloadStatusType.IN_PROGRESS
  val downloadSucceeded = downloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
  val isPartiallyDownloaded = downloadStatus?.status == ModelDownloadStatusType.PARTIALLY_DOWNLOADED
  val showDownloadProgress =
    !downloadSucceeded && (downloadStarted || inProgress || isPartiallyDownloaded)
  var curDownloadProgress: Float

  // A launcher for requesting notification permission.
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      modelManagerViewModel.downloadModel(task = task, model = model)
    }

  // Function to kick off download.
  val startDownload: () -> Unit = {
    checkNotificationPermissionAndStartDownload(
      context = context,
      launcher = permissionLauncher,
      modelManagerViewModel = modelManagerViewModel,
      task = task,
      model = model,
    )
  }

  // Handles the click on the download/try button.
  val handleClickButton = {
    scope.launch(Dispatchers.IO) {
      if (needToDownloadFirst) {
        downloadStarted = true
        Log.d(TAG, "Start downloading model '${model.name}'...")
        withContext(Dispatchers.Main) { startDownload() }
      }
      // No need to download. Directly open the model.
      else {
        withContext(Dispatchers.Main) { onClicked() }
      }
    }
  }

  if (!showDownloadProgress) {
    var buttonModifier: Modifier = modifier.height(42.dp)
    if (!compact) {
      buttonModifier = buttonModifier.fillMaxWidth()
    }
    Button(
      modifier = buttonModifier,
      colors =
        ButtonDefaults.buttonColors(
          containerColor =
            if (
              (!downloadSucceeded || !canShowTryIt) &&
              model.localFileRelativeDirPathOverride.isEmpty()
            )
              MaterialTheme.colorScheme.surfaceContainer
            else getTaskBgGradientColors(task = task)[1]
        ),
      contentPadding = PaddingValues(horizontal = 12.dp),
      onClick = {
        if (!enabled) {
          return@Button
        }

        if (isMemoryLow(context = context, model = model)) {
          showMemoryWarning = true
        } else {
          handleClickButton()
        }
      },
    ) {
      val textColor =
        if (!downloadSucceeded && model.localFileRelativeDirPathOverride.isEmpty())
          MaterialTheme.colorScheme.onSurface
        else Color.White
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          if (needToDownloadFirst) Icons.Outlined.FileDownload
          else Icons.AutoMirrored.Rounded.ArrowForward,
          contentDescription = null,
          tint = textColor,
        )

        if (!compact) {
          if (needToDownloadFirst) {
            Text(
              stringResource(R.string.download),
              color = textColor,
              style = MaterialTheme.typography.titleMedium,
            )
          } else if (canShowTryIt) {
            Text(
              stringResource(R.string.try_it),
              color = textColor,
              style = MaterialTheme.typography.titleMedium,
            )
          }
        }
      }
    }
  }
  // Download progress.
  else {
    curDownloadProgress =
      downloadStatus!!.receivedBytes.toFloat() / downloadStatus.totalBytes.toFloat()
    if (curDownloadProgress.isNaN()) {
      curDownloadProgress = 0f
    }
    val animatedProgress = remember { Animatable(0f) }

    var downloadProgressModifier: Modifier = modifier
    if (!compact) {
      downloadProgressModifier = downloadProgressModifier.fillMaxWidth()
    }
    downloadProgressModifier =
      downloadProgressModifier
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .padding(horizontal = 8.dp)
        .height(42.dp)
    Row(modifier = downloadProgressModifier, verticalAlignment = Alignment.CenterVertically) {
      Text(
        "${(curDownloadProgress * 100).toInt()}%",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 12.dp).width(if (compact) 32.dp else 44.dp),
      )
      if (!compact) {
        LinearProgressIndicator(
          modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
          progress = { animatedProgress.value },
          color = getTaskBgGradientColors(task = task)[1],
          trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
      }
      val cbStop = stringResource(R.string.cd_stop_icon)
      IconButton(
        onClick = {
          downloadStarted = false
          modelManagerViewModel.cancelDownloadModel(task = task, model = model)
        },
        colors =
          IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
          ),
        modifier = Modifier.semantics { contentDescription = cbStop },
      ) {
        Icon(
          Icons.Outlined.Close,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
    LaunchedEffect(curDownloadProgress) {
      animatedProgress.animateTo(curDownloadProgress, animationSpec = tween(150))
    }
  }

  if (showErrorDialog) {
    AlertDialog(
      icon = {
        Icon(
          Icons.Rounded.Error,
          contentDescription = stringResource(R.string.cd_error),
          tint = MaterialTheme.colorScheme.error,
        )
      },
      title = { Text("Unknown network error") },
      text = { Text("Please check your internet connection.") },
      onDismissRequest = { showErrorDialog = false },
      confirmButton = { TextButton(onClick = { showErrorDialog = false }) { Text("Close") } },
    )
  }

  if (showMemoryWarning) {
    MemoryWarningAlert(
      onProceeded = {
        handleClickButton()
        showMemoryWarning = false
      },
      onDismissed = { showMemoryWarning = false },
    )
  }
}
