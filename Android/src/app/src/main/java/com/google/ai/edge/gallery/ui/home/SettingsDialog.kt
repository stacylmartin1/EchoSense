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

package com.google.ai.edge.gallery.ui.home

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.ui.common.tos.TosDialog
import com.google.ai.edge.gallery.ui.echosense.ECHOSENSE_MODEL_ID
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.ai.edge.gallery.ui.theme.labelSmallNarrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

private val THEME_OPTIONS = listOf(Theme.THEME_AUTO, Theme.THEME_LIGHT, Theme.THEME_DARK)
private const val SETTINGS_DIALOG_TAG = "SettingsDialog"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
  curThemeOverride: Theme,
  modelManagerViewModel: ModelManagerViewModel,
  onDismissed: () -> Unit,
) {
  var selectedTheme by remember { mutableStateOf(curThemeOverride) }
  var isFocused by remember { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }
  val interactionSource = remember { MutableInteractionSource() }
  var showTos by remember { mutableStateOf(false) }
  var showLicenses by remember { mutableStateOf(false) }
  var showOnlineConnection by remember { mutableStateOf(false) }
  
  val context = LocalContext.current
  val modelUiState by modelManagerViewModel.uiState.collectAsState()
  val modelTask = modelUiState.tasks.firstOrNull { task ->
    task.models.any { it.name == ECHOSENSE_MODEL_ID }
  }
  val onDeviceModel = modelTask?.models?.firstOrNull { it.name == ECHOSENSE_MODEL_ID }
  val modelDownloadStatus = onDeviceModel?.let { modelUiState.modelDownloadStatus[it.name] }
  val coroutineScope = rememberCoroutineScope()
  var ttsVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
  var ttsTemp by remember { mutableStateOf<TextToSpeech?>(null) }

  // Load voices exposed by the active Android TTS engine.
  androidx.compose.runtime.LaunchedEffect(Unit) {
      val appContext = context.applicationContext
      var createdTts: TextToSpeech? = null
      createdTts = TextToSpeech(appContext) { status ->
          if (status != TextToSpeech.SUCCESS) {
              Log.w(SETTINGS_DIALOG_TAG, "TTS init failed while loading voices: $status")
              return@TextToSpeech
          }

          android.os.Handler(android.os.Looper.getMainLooper()).post {
              val engine = ttsTemp ?: createdTts ?: return@post
              val allVoices = engine.voices?.toList().orEmpty()
              ttsVoices = allVoices
                  .filter(::isSelectableTtsVoice)
                  .distinctBy { it.name }
                  .sortedWith(ttsVoiceComparator(Locale.getDefault()))
              Log.d(
                  SETTINGS_DIALOG_TAG,
                  "Loaded ${ttsVoices.size} selectable TTS voices from ${allVoices.size} engine voices"
              )
          }
      }
      ttsTemp = createdTts
  }

  // Cleanup TTS when dialog closes
  androidx.compose.runtime.DisposableEffect(Unit) {
      onDispose { ttsTemp?.shutdown() }
  }

  Dialog(onDismissRequest = onDismissed) {
    val focusManager = LocalFocusManager.current
    Card(
      modifier =
        Modifier.fillMaxWidth().clickable(
          interactionSource = interactionSource,
          indication = null, // Disable the ripple effect
        ) {
          focusManager.clearFocus()
        },
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Dialog title and subtitle.
        Column {
          Text(
            "Settings",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp),
          )
          // Subtitle.
          Text(
            "App version: ${BuildConfig.VERSION_NAME}",
            style = labelSmallNarrow,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.offset(y = (-6).dp),
          )
        }

        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          // Theme switcher.
          Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(
              "Theme",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            MultiChoiceSegmentedButtonRow {
              THEME_OPTIONS.forEachIndexed { index, theme ->
                SegmentedButton(
                  shape =
                    SegmentedButtonDefaults.itemShape(index = index, count = THEME_OPTIONS.size),
                  onCheckedChange = {
                    selectedTheme = theme

                    // Update theme settings.
                    // This will update app's theme.
                    ThemeSettings.themeOverride.value = theme

                    // Save to data store.
                    modelManagerViewModel.saveThemeOverride(theme)

                    // Update ui mode.
                    //
                    // This is necessary to make other Activities launched from MainActivity to have
                    // the correct theme.
                    val uiModeManager =
                      context.applicationContext.getSystemService(Context.UI_MODE_SERVICE)
                        as UiModeManager
                    if (theme == Theme.THEME_AUTO) {
                      uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
                    } else if (theme == Theme.THEME_LIGHT) {
                      uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
                    } else {
                      uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
                    }
                  },
                  checked = theme == selectedTheme,
                  label = { Text(themeLabel(theme)) },
                )
              }
            }
          }

          // EchoSense settings.
          val videoPreviewEnabled by AppSettings.videoPreviewEnabled.collectAsState()
          val textOverlayEnabled by AppSettings.textOverlayEnabled.collectAsState()
          val responseStyle by AppSettings.llmResponseStyle.collectAsState()

          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(
              "On-Device AI Model",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            if (modelTask == null || onDeviceModel == null) {
              Text(
                "Model information is temporarily unavailable.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              val status = modelDownloadStatus?.status ?: ModelDownloadStatusType.NOT_DOWNLOADED
              val modelSizeGb = onDeviceModel.totalBytes.toDouble() / 1_000_000_000.0
              Text(
                onDeviceModel.displayName,
                style = MaterialTheme.typography.bodyMedium,
              )
              Text(
                when (status) {
                  ModelDownloadStatusType.SUCCEEDED -> "Downloaded and ready"
                  ModelDownloadStatusType.IN_PROGRESS -> "Downloading"
                  ModelDownloadStatusType.PARTIALLY_DOWNLOADED -> "Download paused"
                  ModelDownloadStatusType.UNZIPPING -> "Finishing installation"
                  ModelDownloadStatusType.FAILED -> "Download failed"
                  ModelDownloadStatusType.NOT_DOWNLOADED -> "Not downloaded · %.1f GB".format(modelSizeGb)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )

              if (status == ModelDownloadStatusType.IN_PROGRESS) {
                val progress =
                  if ((modelDownloadStatus?.totalBytes ?: 0L) > 0L) {
                    modelDownloadStatus!!.receivedBytes.toFloat() /
                      modelDownloadStatus.totalBytes.toFloat()
                  } else 0f
                LinearProgressIndicator(
                  progress = { progress.coerceIn(0f, 1f) },
                  modifier = Modifier.fillMaxWidth(),
                )
                Text(
                  "${(progress * 100).toInt()}%",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.primary,
                )
              }

              if (status == ModelDownloadStatusType.FAILED &&
                !modelDownloadStatus?.errorMessage.isNullOrBlank()
              ) {
                Text(
                  modelDownloadStatus?.errorMessage.orEmpty(),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.error,
                )
              }

              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status) {
                  ModelDownloadStatusType.SUCCEEDED -> {
                    OutlinedButton(
                      onClick = { modelManagerViewModel.deleteModel(modelTask, onDeviceModel) }
                    ) { Text("Delete Model") }
                  }
                  ModelDownloadStatusType.IN_PROGRESS,
                  ModelDownloadStatusType.UNZIPPING -> {
                    OutlinedButton(
                      onClick = {
                        modelManagerViewModel.cancelDownloadModel(modelTask, onDeviceModel)
                      }
                    ) { Text("Cancel Download") }
                  }
                  else -> {
                    Button(
                      onClick = { modelManagerViewModel.downloadModel(modelTask, onDeviceModel) }
                    ) {
                      Text(
                        if (status == ModelDownloadStatusType.PARTIALLY_DOWNLOADED) {
                          "Resume Download"
                        } else {
                          "Download Model"
                        }
                      )
                    }
                  }
                }
                TextButton(
                  onClick = {
                    context.startActivity(
                      Intent(Intent.ACTION_VIEW, Uri.parse(onDeviceModel.licenseUrl))
                    )
                  }
                ) { Text("License") }
              }
            }
          }

          // Camera Preview toggle.
          Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(
              "Camera Preview",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                "Show camera feed on screen",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Switch(
                checked = videoPreviewEnabled,
                onCheckedChange = {
                  AppSettings.setVideoPreviewEnabled(it)
                  modelManagerViewModel.saveEchoSenseSettings()
                },
              )
            }
          }

          // Text Overlay toggle.
          Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(
              "Text Overlay",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                "Display analysis text on screen",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Switch(
                checked = textOverlayEnabled,
                onCheckedChange = {
                  AppSettings.setTextOverlayEnabled(it)
                  modelManagerViewModel.saveEchoSenseSettings()
                },
              )
            }
          }

          // TTS Voice Settings
          if (ttsVoices.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
              Text(
                  "TTS Voice",
                  style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
              )
              val ttsVoiceName by AppSettings.ttsVoiceName.collectAsState()
              var expanded by remember { mutableStateOf(false) }

              ExposedDropdownMenuBox(
                  expanded = expanded,
                  onExpandedChange = { expanded = it },
                  modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
              ) {
                  OutlinedButton(
                      onClick = { expanded = !expanded },
                      modifier = Modifier.menuAnchor().fillMaxWidth(),
                      shape = RoundedCornerShape(8.dp)
                  ) {
                      val currentName = ttsVoiceName.ifEmpty { "Default Voice" }
                      val displayName = ttsVoices.find { it.name == currentName }?.let { voice ->
                          ttsVoiceLabel(voice)
                      } ?: currentName
                      Text(
                          displayName,
                          modifier = Modifier.weight(1f),
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                      )
                      ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                  }

                  ExposedDropdownMenu(
                      expanded = expanded,
                      onDismissRequest = { expanded = false }
                  ) {
                      DropdownMenuItem(
                          text = { Text("System Default") },
                          onClick = {
                              AppSettings.setTtsVoiceName("")
                              modelManagerViewModel.saveEchoSenseSettings()
                              expanded = false
                          }
                      )
                      ttsVoices.forEach { voice ->
                          DropdownMenuItem(
                              text = { Text(ttsVoiceLabel(voice), maxLines = 2) },
                              onClick = {
                                  AppSettings.setTtsVoiceName(voice.name)
                                  modelManagerViewModel.saveEchoSenseSettings()
                                  expanded = false
                              }
                          )
                      }
                  }
              }
            }
          }

          // Response Detail segmented button.
          Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(
              "Response Detail",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            val responseOptions = listOf(LlmResponseStyle.CONCISE, LlmResponseStyle.VERBOSE)
            MultiChoiceSegmentedButtonRow {
              responseOptions.forEachIndexed { index, style ->
                SegmentedButton(
                  shape =
                    SegmentedButtonDefaults.itemShape(index = index, count = responseOptions.size),
                  onCheckedChange = {
                    AppSettings.setLlmResponseStyle(style)
                    modelManagerViewModel.saveEchoSenseSettings()
                  },
                  checked = responseStyle == style,
                  label = {
                    Text(
                      when (style) {
                        LlmResponseStyle.CONCISE -> "Concise"
                        LlmResponseStyle.VERBOSE -> "Verbose"
                      }
                    )
                  },
                )
              }
            }
          }



          // Optional online analysis connection.
          Column(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(
              "Online Analysis",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            val onlineKey by AppSettings.geminiApiKey.collectAsState()
            val onlineProvider by AppSettings.onlineProvider.collectAsState()
            val onlineMode by AppSettings.onlineUsageMode.collectAsState()
            if (onlineKey.isNotEmpty()) {
              Text(
                "${onlineProvider.displayName} ${AppSettings.maskedOnlineKey()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                onlineMode.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
              )
            } else {
              Text(
                "Not set",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                "Connect a provider for optional online scene analysis.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            OutlinedButton(onClick = { showOnlineConnection = true }) {
              Text(if (onlineKey.isEmpty()) "Connect AI Provider" else "Manage Online Analysis")
            }
            Text(
              "Images are sent only when online analysis is used. Provider charges may apply.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          // Third party licenses.
          Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(
              "Third-party licenses",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            OutlinedButton(onClick = { showLicenses = true }) {
              Text("View licenses")
            }
          }

          // Tos
          Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(
              stringResource(R.string.settings_dialog_tos_title),
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            OutlinedButton(onClick = { showTos = true }) { Text("View terms and privacy") }
          }
        }

        // Button row.
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          // Close button
          Button(onClick = { onDismissed() }) { Text("Close") }
        }
      }
    }
  }

  if (showOnlineConnection) {
    OnlineConnectionDialog(
      modelManagerViewModel = modelManagerViewModel,
      onDismiss = { showOnlineConnection = false },
    )
  }

  if (showTos) {
    TosDialog(onTosAccepted = { showTos = false }, viewingMode = true)
  }
  if (showLicenses) {
    ThirdPartyLicensesDialog(onDismiss = { showLicenses = false })
  }
}

private data class LicenseNotice(
  val name: String,
  val license: String,
  val notice: String,
)

private val THIRD_PARTY_LICENSE_NOTICES =
  listOf(
    LicenseNotice(
      name = "Google AI Edge Gallery and EchoSense app code",
      license = "Apache License 2.0",
      notice = "The application code is based on Google AI Edge Gallery and EchoSense modifications. Copyright notices are retained in source files.",
    ),
    LicenseNotice(
      name = "Gemma / Gemma 3n LiteRT-LM model asset",
      license = "Gemma Terms of Use",
      notice = "Downloaded .litertlm model files are subject to the Gemma Terms of Use at ai.google.dev/gemma/terms, including redistribution notice and prohibited-use requirements.",
    ),
    LicenseNotice(
      name = "LiteRT-LM, TensorFlow Lite, and Google AI Edge runtimes",
      license = "Apache License 2.0",
      notice = "On-device model runtime components from Google AI Edge, LiteRT, and TensorFlow Lite are used for local inference.",
    ),
    LicenseNotice(
      name = "MediaPipe Tasks and EfficientDet Lite object detector",
      license = "Apache License 2.0",
      notice = "The collision-avoidance detector uses MediaPipe Tasks with an EfficientDet Lite model asset.",
    ),
    LicenseNotice(
      name = "Ultralytics YOLO11s model asset",
      license = "AGPL-3.0 or commercial Ultralytics license",
      notice = "The bundled yolo11s_float16.tflite asset originates from the Ultralytics YOLO model family. Verify commercial licensing before distributing builds that include or use this asset.",
    ),
    LicenseNotice(
      name = "ML Kit OCR, translation, and language identification",
      license = "Google ML Kit / Google APIs terms",
      notice = "Google ML Kit components provide text recognition, translation, and language identification. Some models may be provided by Google Play services or bundled SDK artifacts.",
    ),
    LicenseNotice(
      name = "Tesseract OCR, Tesseract4Android, and tessdata language files",
      license = "Apache License 2.0",
      notice = "Tesseract is used as an offline OCR fallback. Bundled tessdata files include Thai, Khmer, Lao, Burmese, and Arabic recognition data.",
    ),
    LicenseNotice(
      name = "AndroidX, Jetpack Compose, CameraX, Hilt, Protobuf, PDFBox Android, CommonMark, RichText, AppAuth, Firebase, and related libraries",
      license = "Apache License 2.0, BSD, MIT, or similar permissive licenses",
      notice = "The app uses standard Android and JVM open-source libraries. Dependency-level license generation is summarized here because the Google OSS Licenses debug artifact currently generates an empty placeholder list.",
    ),
    LicenseNotice(
      name = "Android platform, camera, audio, and device drivers",
      license = "Device and OS vendor licenses",
      notice = "Android framework services, system TTS, camera stacks, audio stacks, GPU delegates, and hardware drivers are provided by the OS or device vendor and are not redistributed by this app.",
    ),
  )

@Composable
private fun ThirdPartyLicensesDialog(onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Close")
      }
    },
    title = {
      Text("Third-party licenses")
    },
    text = {
      Column(
        modifier = Modifier.height(420.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Text(
          "License notices for downloaded models, neural network assets, app code, and major runtime components.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        THIRD_PARTY_LICENSE_NOTICES.forEach { notice ->
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              notice.name,
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            Text(
              notice.license,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            Text(
              notice.notice,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    },
  )
}

private fun themeLabel(theme: Theme): String {
  return when (theme) {
    Theme.THEME_AUTO -> "Auto"
    Theme.THEME_LIGHT -> "Light"
    Theme.THEME_DARK -> "Dark"
    else -> "Unknown"
  }
}

private fun isSelectableTtsVoice(voice: Voice): Boolean {
  val features = voice.features.orEmpty()
  return TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features
}

private fun ttsVoiceComparator(currentLocale: Locale): Comparator<Voice> {
  return compareBy<Voice>(
      { it.isNetworkConnectionRequired },
      { if (it.locale.language == currentLocale.language) 0 else 1 },
      { if (it.locale.language == Locale.ENGLISH.language) 0 else 1 },
      { -it.quality },
      { it.locale.displayName },
      { it.name },
  )
}

private fun ttsVoiceLabel(voice: Voice): String {
  val localeName =
    voice.locale.getDisplayName(Locale.getDefault()).ifBlank { voice.locale.toLanguageTag() }
  val connection = if (voice.isNetworkConnectionRequired) "Network" else "Offline"
  return "$localeName - $connection - ${ttsVoiceQualityLabel(voice.quality)}"
}

private fun ttsVoiceQualityLabel(quality: Int): String {
  return when {
    quality >= Voice.QUALITY_VERY_HIGH -> "Very high quality"
    quality >= Voice.QUALITY_HIGH -> "High quality"
    quality >= Voice.QUALITY_NORMAL -> "Standard quality"
    quality >= Voice.QUALITY_LOW -> "Low quality"
    else -> "Very low quality"
  }
}
