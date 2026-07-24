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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.ui.common.modelitem.ConfirmDeleteModelDialog
import com.google.ai.edge.gallery.ui.common.tos.TosDialog
import com.google.ai.edge.gallery.ui.echosense.ECHOSENSE_MODEL_IDS
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

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
  var showTos by remember { mutableStateOf(false) }
  var showLicenses by remember { mutableStateOf(false) }
  var showOnlineConnection by remember { mutableStateOf(false) }
  var showDeleteModelConfirmation by remember { mutableStateOf(false) }
  var modelMenuExpanded by remember { mutableStateOf(false) }
  
  val context = LocalContext.current
  val modelUiState by modelManagerViewModel.uiState.collectAsState()
  val modelTask = modelUiState.tasks.firstOrNull { task ->
    task.models.any { it.name in ECHOSENSE_MODEL_IDS }
  }
  val echoSenseModels = modelTask?.models?.filter { it.name in ECHOSENSE_MODEL_IDS }.orEmpty()
  val onDeviceModel = echoSenseModels.firstOrNull { it.name == modelUiState.selectedModel.name }
    ?: echoSenseModels.firstOrNull()
  val modelDownloadStatus = onDeviceModel?.let { modelUiState.modelDownloadStatus[it.name] }
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

  val videoPreviewEnabled by AppSettings.videoPreviewEnabled.collectAsState()
  val textOverlayEnabled by AppSettings.textOverlayEnabled.collectAsState()
  val responseStyle by AppSettings.llmResponseStyle.collectAsState()
  val ttsVoiceName by AppSettings.ttsVoiceName.collectAsState()
  val safetySpeechRate by AppSettings.safetySpeechRate.collectAsState()
  val onlineKey by AppSettings.geminiApiKey.collectAsState()
  val onlineProvider by AppSettings.onlineProvider.collectAsState()
  val onlineMode by AppSettings.onlineUsageMode.collectAsState()
  var voiceMenuExpanded by remember { mutableStateOf(false) }

  Dialog(
    onDismissRequest = onDismissed,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
          Spacer(modifier = Modifier.weight(1f))
          TextButton(onClick = onDismissed) { Text("Done") }
        }

        Column(
          modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
          SettingsSection("Output") {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
              Text("Response detail", style = MaterialTheme.typography.bodyLarge)
              val responseOptions = listOf(LlmResponseStyle.CONCISE, LlmResponseStyle.VERBOSE)
              MultiChoiceSegmentedButtonRow(modifier = Modifier.padding(top = 8.dp)) {
                responseOptions.forEachIndexed { index, style ->
                  SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, responseOptions.size),
                    onCheckedChange = {
                      AppSettings.setLlmResponseStyle(style)
                      modelManagerViewModel.saveEchoSenseSettings()
                    },
                    checked = responseStyle == style,
                    label = { Text(if (style == LlmResponseStyle.CONCISE) "Concise" else "Verbose") },
                  )
                }
              }
            }
            SettingsDivider()
            SettingsToggleRow("Video preview", videoPreviewEnabled) {
              AppSettings.setVideoPreviewEnabled(it)
              modelManagerViewModel.saveEchoSenseSettings()
            }
            SettingsDivider()
            SettingsToggleRow("Text overlay", textOverlayEnabled) {
              AppSettings.setTextOverlayEnabled(it)
              modelManagerViewModel.saveEchoSenseSettings()
            }
          }

          SettingsSection("Voice") {
            if (ttsVoices.isNotEmpty()) {
              ExposedDropdownMenuBox(
                expanded = voiceMenuExpanded,
                onExpandedChange = { voiceMenuExpanded = it },
              ) {
                TextButton(
                  onClick = { voiceMenuExpanded = true },
                  modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                  contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                  Text("TTS voice", color = MaterialTheme.colorScheme.onSurface)
                  Spacer(Modifier.weight(1f))
                  val voice = ttsVoices.find { it.name == ttsVoiceName }
                  Text(
                    voice?.let(::ttsVoiceLabel) ?: "Default Voice",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.4f),
                  )
                  ExposedDropdownMenuDefaults.TrailingIcon(voiceMenuExpanded)
                }
                ExposedDropdownMenu(voiceMenuExpanded, { voiceMenuExpanded = false }) {
                  DropdownMenuItem(
                    text = { Text("Default Voice") },
                    onClick = {
                      AppSettings.setTtsVoiceName("")
                      modelManagerViewModel.saveEchoSenseSettings()
                      voiceMenuExpanded = false
                    },
                  )
                  ttsVoices.forEach { voice ->
                    DropdownMenuItem(
                      text = { Text(ttsVoiceLabel(voice), maxLines = 2) },
                      onClick = {
                        AppSettings.setTtsVoiceName(voice.name)
                        modelManagerViewModel.saveEchoSenseSettings()
                        voiceMenuExpanded = false
                      },
                    )
                  }
                }
              }
              SettingsDivider()
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Safety speech speed", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Text("%.2f×".format(Locale.US, safetySpeechRate), color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Slider(
                value = safetySpeechRate,
                onValueChange = AppSettings::setSafetySpeechRate,
                onValueChangeFinished = modelManagerViewModel::saveEchoSenseSettings,
                valueRange = 0.8f..1.4f,
                steps = 11,
              )
              Text("Changes obstacle announcements only.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }

          SettingsSection("On-Device AI Model") {
            if (modelTask == null || onDeviceModel == null) {
              Text("Model information is temporarily unavailable.", modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
              val status = modelDownloadStatus?.status ?: ModelDownloadStatusType.NOT_DOWNLOADED
              val modelSizeGb = onDeviceModel.totalBytes.toDouble() / 1_000_000_000.0
              ExposedDropdownMenuBox(
                expanded = modelMenuExpanded,
                onExpandedChange = {
                  if (status != ModelDownloadStatusType.IN_PROGRESS && status != ModelDownloadStatusType.UNZIPPING) {
                    modelMenuExpanded = it
                  }
                },
              ) {
                TextButton(
                  onClick = { modelMenuExpanded = true },
                  modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                  enabled = status != ModelDownloadStatusType.IN_PROGRESS && status != ModelDownloadStatusType.UNZIPPING,
                  contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                  Text("Download model", color = MaterialTheme.colorScheme.onSurface)
                  Spacer(Modifier.weight(1f))
                  Text(onDeviceModel.displayName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  ExposedDropdownMenuDefaults.TrailingIcon(modelMenuExpanded)
                }
                ExposedDropdownMenu(modelMenuExpanded, { modelMenuExpanded = false }) {
                  echoSenseModels.forEach { model ->
                    DropdownMenuItem(
                      text = { Text("${model.displayName} · %.1f GB".format(model.totalBytes / 1_000_000_000.0)) },
                      onClick = {
                        modelManagerViewModel.selectModel(model)
                        modelMenuExpanded = false
                      },
                    )
                  }
                }
              }
              SettingsDivider()
              SettingsValueRow(
                "Status",
                when (status) {
                  ModelDownloadStatusType.SUCCEEDED -> "Downloaded and ready"
                  ModelDownloadStatusType.IN_PROGRESS -> "Downloading"
                  ModelDownloadStatusType.PARTIALLY_DOWNLOADED -> "Download paused"
                  ModelDownloadStatusType.UNZIPPING -> "Finishing installation"
                  ModelDownloadStatusType.FAILED -> "Download failed"
                  ModelDownloadStatusType.NOT_DOWNLOADED -> "Not downloaded · %.1f GB".format(modelSizeGb)
                },
              )
              if (status == ModelDownloadStatusType.IN_PROGRESS) {
                val progress = if ((modelDownloadStatus?.totalBytes ?: 0) > 0) {
                  modelDownloadStatus!!.receivedBytes.toFloat() / modelDownloadStatus.totalBytes.toFloat()
                } else 0f
                LinearProgressIndicator({ progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Text("${(progress * 100).toInt()}%", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                  color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
              }
              if (status == ModelDownloadStatusType.FAILED && !modelDownloadStatus?.errorMessage.isNullOrBlank()) {
                Text(modelDownloadStatus?.errorMessage.orEmpty(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                  color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
              }
              SettingsDivider()
              when (status) {
                ModelDownloadStatusType.SUCCEEDED -> SettingsActionRow("Delete downloaded model", destructive = true) {
                  showDeleteModelConfirmation = true
                }
                ModelDownloadStatusType.IN_PROGRESS, ModelDownloadStatusType.UNZIPPING -> SettingsActionRow("Cancel download") {
                  modelManagerViewModel.cancelDownloadModel(modelTask, onDeviceModel)
                }
                else -> SettingsActionRow(
                  if (status == ModelDownloadStatusType.PARTIALLY_DOWNLOADED) "Resume download" else "Download model"
                ) { modelManagerViewModel.downloadModel(modelTask, onDeviceModel) }
              }
              SettingsDivider()
              SettingsActionRow("View model license") {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(onDeviceModel.licenseUrl)))
              }
            }
          }

          SettingsSection("Online Analysis") {
            SettingsValueRow("Status", if (onlineKey.isEmpty()) "Not connected" else "Connected")
            if (onlineKey.isNotEmpty()) {
              SettingsDivider()
              SettingsValueRow("Provider", onlineProvider.displayName)
              SettingsDivider()
              SettingsValueRow("Key", AppSettings.maskedOnlineKey())
              SettingsDivider()
              SettingsValueRow("Usage", onlineMode.displayName)
            }
            SettingsDivider()
            SettingsActionRow(if (onlineKey.isEmpty()) "Connect AI provider" else "Manage online analysis") {
              showOnlineConnection = true
            }
            Text(
              "Online analysis is optional. Images and prompts are sent directly to your provider and may incur charges.",
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          SettingsSection("Appearance") {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
              Text("Theme", style = MaterialTheme.typography.bodyLarge)
              MultiChoiceSegmentedButtonRow(modifier = Modifier.padding(top = 8.dp)) {
                THEME_OPTIONS.forEachIndexed { index, theme ->
                  SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, THEME_OPTIONS.size),
                    onCheckedChange = {
                      selectedTheme = theme
                      ThemeSettings.themeOverride.value = theme
                      modelManagerViewModel.saveThemeOverride(theme)
                      val manager = context.applicationContext.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
                      manager.setApplicationNightMode(
                        when (theme) {
                          Theme.THEME_AUTO -> UiModeManager.MODE_NIGHT_AUTO
                          Theme.THEME_LIGHT -> UiModeManager.MODE_NIGHT_NO
                          else -> UiModeManager.MODE_NIGHT_YES
                        }
                      )
                    },
                    checked = theme == selectedTheme,
                    label = { Text(themeLabel(theme)) },
                  )
                }
              }
            }
          }

          SettingsSection("About") {
            SettingsValueRow("App version", BuildConfig.VERSION_NAME)
            SettingsDivider()
            SettingsActionRow("View terms and privacy") { showTos = true }
            SettingsDivider()
            SettingsActionRow("View licenses") { showLicenses = true }
          }
          Spacer(Modifier.height(8.dp))
        }
      }
    }
  }

  if (showDeleteModelConfirmation && modelTask != null && onDeviceModel != null) {
    ConfirmDeleteModelDialog(
      model = onDeviceModel,
      onConfirm = {
        modelManagerViewModel.deleteModel(modelTask, onDeviceModel)
        showDeleteModelConfirmation = false
      },
      onDismiss = { showDeleteModelConfirmation = false },
    )
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

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      title,
      modifier = Modifier.padding(horizontal = 4.dp),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
    )
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(12.dp),
    ) {
      Column(content = content)
    }
  }
}

@Composable
private fun SettingsDivider() {
  HorizontalDivider(
    modifier = Modifier.padding(start = 16.dp),
    color = MaterialTheme.colorScheme.outlineVariant,
  )
}

@Composable
private fun SettingsToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Composable
private fun SettingsValueRow(title: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    Text(
      value,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun SettingsActionRow(
  title: String,
  destructive: Boolean = false,
  onClick: () -> Unit,
) {
  TextButton(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
  ) {
    Text(
      title,
      modifier = Modifier.fillMaxWidth(),
      color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
      style = MaterialTheme.typography.bodyLarge,
    )
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
      name = "Gemma 4 LiteRT-LM model assets",
      license = "Apache License 2.0",
      notice = "The public LiteRT Community Gemma 4 E2B and E4B repositories identify these downloadable artifacts as Apache-2.0 licensed. Model cards and notices are linked from the download controls.",
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
      name = "AndroidX, Jetpack Compose, CameraX, Hilt, Protobuf, PDFBox Android, CommonMark, RichText, Firebase, and related libraries",
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
