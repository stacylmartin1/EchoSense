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

package com.terranet.echosense.android.ui.echosense.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.terranet.echosense.android.ui.common.MarkdownText
import com.terranet.echosense.android.ui.common.createTempPictureUri
import com.terranet.echosense.android.ui.home.AppSettings
import com.terranet.echosense.android.ui.home.OnlineConnectionDialog
import com.terranet.echosense.android.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun AssistantScreen(
  viewModel: AssistantViewModel,
  bottomPadding: Dp,
  modelManagerViewModel: ModelManagerViewModel,
) {
  val context = LocalContext.current
  val messages by viewModel.messages.collectAsState()
  val processing by viewModel.isProcessing.collectAsState()
  val resetting by viewModel.isResetting.collectAsState()
  val listening by viewModel.isListening.collectAsState()
  val attachmentName by viewModel.attachmentName.collectAsState()
  val attachmentBitmap by viewModel.attachmentBitmap.collectAsState()
  val error by viewModel.error.collectAsState()
  val activeModel by viewModel.activeModelName.collectAsState()
  val modelMode by viewModel.modelMode.collectAsState()
  val onlineKey by AppSettings.geminiApiKey.collectAsState()
  val onlineProvider by AppSettings.onlineProvider.collectAsState()
  val onlineConsent by AppSettings.onlineConsentGranted.collectAsState()
  var draft by remember { mutableStateOf("") }
  var cameraUri by remember { mutableStateOf<Uri?>(null) }
  var showOnlineConnection by remember { mutableStateOf(false) }
  var confirmOnline by remember { mutableStateOf(false) }
  var pendingOnlineSelection by remember { mutableStateOf(false) }
  var followLatest by remember { mutableStateOf(true) }
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val userScrollConnection = remember {
    object : NestedScrollConnection {
      override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y != 0f) followLatest = false
        return Offset.Zero
      }
    }
  }

  val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
    if (saved) cameraUri?.let(viewModel::attach)
  }
  val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) {
      cameraUri = context.createTempPictureUri(fileExtension = ".jpg")
      camera.launch(cameraUri!!)
    }
  }
  val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri?.let(viewModel::attach)
  }

  LaunchedEffect(messages.size) {
    if (messages.lastOrNull()?.text.isNullOrEmpty()) followLatest = true
  }
  LaunchedEffect(messages.lastOrNull()?.text?.length, followLatest) {
    if (followLatest && messages.isNotEmpty()) {
      listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE)
    }
  }
  LaunchedEffect(listState) {
    snapshotFlow { listState.canScrollForward }
      .distinctUntilChanged()
      .collect { canScrollForward ->
        if (!canScrollForward) followLatest = true
      }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .statusBarsPadding()
      .imePadding()
      .padding(start = 16.dp, end = 16.dp, bottom = bottomPadding + 8.dp)
  ) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Assistant", style = MaterialTheme.typography.titleLarge)
        Text(activeModel ?: "Text, voice, images, and documents", style = MaterialTheme.typography.bodySmall)
      }
      Button(
        onClick = {
          draft = ""
          viewModel.newChat()
        },
        enabled = !resetting,
      ) {
        Icon(Icons.Outlined.AddComment, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("New")
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilterChip(
        selected = modelMode == AssistantModelMode.ON_DEVICE,
        onClick = { viewModel.setModelMode(AssistantModelMode.ON_DEVICE) },
        enabled = !processing && !resetting,
        label = { Text("On-device") },
      )
      FilterChip(
        selected = modelMode == AssistantModelMode.ONLINE,
        onClick = {
          when {
            onlineKey.isBlank() -> {
              pendingOnlineSelection = true
              showOnlineConnection = true
            }
            !onlineConsent -> confirmOnline = true
            else -> viewModel.setModelMode(AssistantModelMode.ONLINE)
          }
        },
        enabled = !processing && !resetting,
        label = { Text(onlineProvider.displayName) },
      )
    }

    if (messages.isEmpty()) {
      Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text("Ask a question or attach something to analyze.", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    } else {
      Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize().nestedScroll(userScrollConnection),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(messages, key = { it.id }) { message -> MessageBubble(message) }
        }
        if (!followLatest) {
          TextButton(
            onClick = {
              followLatest = true
              scope.launch { listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE) }
            },
            modifier = Modifier.align(Alignment.BottomEnd),
          ) {
            Text("Latest")
          }
        }
      }
    }

    if (attachmentName != null) {
      Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
          attachmentBitmap?.let { Image(it.asImageBitmap(), null, Modifier.size(54.dp)) }
          Text(attachmentName.orEmpty(), Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 2)
          IconButton(onClick = viewModel::clearAttachment) { Icon(Icons.Outlined.Close, "Remove attachment") }
        }
      }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }

    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
      IconButton(onClick = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
          cameraUri = context.createTempPictureUri(fileExtension = ".jpg")
          camera.launch(cameraUri!!)
        } else cameraPermission.launch(Manifest.permission.CAMERA)
      }, enabled = !processing && !resetting) { Icon(Icons.Outlined.CameraAlt, "Take photo") }
      IconButton(onClick = { files.launch(arrayOf("image/*", "application/pdf", "text/*")) }, enabled = !processing && !resetting) {
        Icon(Icons.Outlined.AttachFile, "Attach file")
      }
      OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        modifier = Modifier.weight(1f),
        placeholder = { Text("Message") },
        minLines = 1,
        maxLines = 4,
      )
      IconButton(onClick = viewModel::toggleVoice, enabled = !processing && !resetting) {
        Icon(Icons.Outlined.Mic, if (listening) "Stop listening" else "Voice message", tint = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
      }
      IconButton(onClick = {
        if (processing) viewModel.stop() else {
          viewModel.send(draft)
          if (draft.isNotBlank()) draft = ""
        }
      }, enabled = processing || (!resetting && draft.isNotBlank())) {
        if (processing) Icon(Icons.Outlined.Stop, "Stop") else Icon(Icons.AutoMirrored.Outlined.Send, "Send")
      }
    }
    if (processing || resetting) {
      Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(if (resetting) "Starting new chat…" else "Thinking…", style = MaterialTheme.typography.bodySmall)
      }
    } else if (listening) {
      Text("Listening for a message…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
  }

  if (confirmOnline) {
    AlertDialog(
      onDismissRequest = { confirmOnline = false },
      title = { Text("Use ${onlineProvider.displayName} for chat?") },
      text = {
        Text(
          "Your messages and any attached image or document will be sent to " +
            "${onlineProvider.displayName}. Provider charges may apply."
        )
      },
      confirmButton = {
        TextButton(onClick = {
          confirmOnline = false
          AppSettings.onlineConsentGranted.value = true
          modelManagerViewModel.saveEchoSenseSettings()
          viewModel.setModelMode(AssistantModelMode.ONLINE)
        }) { Text("Use online") }
      },
      dismissButton = {
        TextButton(onClick = { confirmOnline = false }) { Text("Cancel") }
      },
    )
  }

  if (showOnlineConnection) {
    OnlineConnectionDialog(
      modelManagerViewModel = modelManagerViewModel,
      onDismiss = {
        showOnlineConnection = false
        if (pendingOnlineSelection && AppSettings.isOnlineConnected()) {
          pendingOnlineSelection = false
          if (AppSettings.onlineConsentGranted.value) {
            viewModel.setModelMode(AssistantModelMode.ONLINE)
          } else {
            confirmOnline = true
          }
        } else {
          pendingOnlineSelection = false
        }
      },
    )
  }
}

@Composable
private fun MessageBubble(message: AssistantMessage) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start) {
    val bubbleModifier = Modifier
        .fillMaxWidth(0.86f)
        .background(
          if (message.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
          RoundedCornerShape(16.dp),
        )
        .padding(12.dp)
    if (message.isUser || message.text.isBlank()) {
      Text(
        text = message.text.ifBlank { "…" },
        modifier = bubbleModifier,
        color = if (message.isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      MarkdownText(
        text = message.text,
        modifier = bubbleModifier,
        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
