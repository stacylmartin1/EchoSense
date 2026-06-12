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

import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.ui.common.tos.TosDialog
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.ai.edge.gallery.ui.theme.labelSmallNarrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

private val THEME_OPTIONS = listOf(Theme.THEME_AUTO, Theme.THEME_LIGHT, Theme.THEME_DARK)

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
  
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var ttsVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
  var ttsTemp by remember { mutableStateOf<TextToSpeech?>(null) }

  // Load voices securely
  androidx.compose.runtime.LaunchedEffect(Unit) {
      ttsTemp = TextToSpeech(context) { status ->
          if (status == TextToSpeech.SUCCESS) {
              val allVoices = ttsTemp?.voices?.toList() ?: emptyList()
              ttsVoices = allVoices.filter {
                  it.locale.language == "en" && !it.isNetworkConnectionRequired
              }.sortedBy { it.name }
          }
      }
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
          
          // Debug Overlay toggle.
          Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(
              "Debug Overlay",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            val showDebugOverlay by AppSettings.showDebugOverlay.collectAsState()
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                "Show raw OCR/translation & model status",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Switch(
                checked = showDebugOverlay,
                onCheckedChange = {
                  AppSettings.setShowDebugOverlay(it)
                  modelManagerViewModel.saveEchoSenseSettings()
                },
              )
            }
          }

          // TTS Voice Settings
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
                           "${voice.locale.displayCountry} (${if (voice.name.contains("female", true)) "Female" else if (voice.name.contains("male", true)) "Male" else "Standard"})"
                      } ?: currentName
                      Text(displayName, modifier = Modifier.weight(1f))
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
                          val label = "${voice.locale.displayCountry} (${if (voice.name.contains("female", true)) "Female" else if (voice.name.contains("male", true)) "Male" else "Voice"})"
                          DropdownMenuItem(
                              text = { Text(label) },
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



          // Gemini API Key management.
          Column(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(
              "Gemini API Key",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            val geminiKey by AppSettings.geminiApiKey.collectAsState()
            var geminiKeyInput by remember { mutableStateOf("") }
            var geminiKeyFocused by remember { mutableStateOf(false) }
            val geminiFocusRequester = remember { FocusRequester() }

            if (geminiKey.isNotEmpty()) {
              Text(
                geminiKey.take(8) + "..." + geminiKey.takeLast(4),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                "Online OCR and vision enabled",
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
                "Get a free key at aistudio.google.com",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              OutlinedButton(
                onClick = {
                  AppSettings.setGeminiApiKey("")
                  modelManagerViewModel.saveEchoSenseSettings()
                  geminiKeyInput = ""
                },
                enabled = geminiKey.isNotEmpty(),
              ) {
                Text("Clear")
              }
              val handleSaveGeminiKey = {
                AppSettings.setGeminiApiKey(geminiKeyInput.trim())
                modelManagerViewModel.saveEchoSenseSettings()
                geminiKeyInput = ""
                focusManager.clearFocus()
              }
              BasicTextField(
                value = geminiKeyInput,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { handleSaveGeminiKey() }),
                modifier =
                  Modifier.fillMaxWidth()
                    .padding(top = 4.dp)
                    .focusRequester(geminiFocusRequester)
                    .onFocusChanged { geminiKeyFocused = it.isFocused },
                onValueChange = { geminiKeyInput = it },
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
              ) { innerTextField ->
                Box(
                  modifier =
                    Modifier.border(
                        width = if (geminiKeyFocused) 2.dp else 1.dp,
                        color =
                          if (geminiKeyFocused) MaterialTheme.colorScheme.primary
                          else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                      )
                      .height(40.dp),
                  contentAlignment = Alignment.CenterStart,
                ) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                      if (geminiKeyInput.isEmpty()) {
                        Text(
                          "Enter Gemini API key",
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          style = MaterialTheme.typography.bodySmall,
                        )
                      }
                      innerTextField()
                    }
                    if (geminiKeyInput.isNotEmpty()) {
                      IconButton(modifier = Modifier.offset(x = 1.dp), onClick = handleSaveGeminiKey) {
                        Icon(
                          Icons.Rounded.CheckCircle,
                          contentDescription = "Save Gemini API key",
                        )
                      }
                    }
                  }
                }
              }
            }
          }

          // Third party licenses.
          Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(
              "Third-party libraries",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            OutlinedButton(
              onClick = {
                // Create an Intent to launch a license viewer that displays a list of
                // third-party library names. Clicking a name will show its license content.
                val intent = Intent(context, OssLicensesMenuActivity::class.java)
                context.startActivity(intent)
              }
            ) {
              Text("View licenses")
            }
          }

          // Tos
          Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(
              stringResource(R.string.settings_dialog_tos_title),
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            OutlinedButton(onClick = { showTos = true }) { Text("View Terms of Services") }
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

  if (showTos) {
    TosDialog(onTosAccepted = { showTos = false }, viewingMode = true)
  }
}

private fun themeLabel(theme: Theme): String {
  return when (theme) {
    Theme.THEME_AUTO -> "Auto"
    Theme.THEME_LIGHT -> "Light"
    Theme.THEME_DARK -> "Dark"
    else -> "Unknown"
  }
}
