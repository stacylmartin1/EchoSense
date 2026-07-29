/*
 * Copyright 2025 Google LLC
 * Modifications Copyright 2026 TerraNet Technologies LLC
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

package com.terranet.echosense.android.ui.common.tos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.terranet.echosense.android.R
import com.terranet.echosense.android.ui.common.MarkdownText

/** A composable for Terms of Service dialog, shown once when app is launched. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TosDialog(onTosAccepted: () -> Unit, viewingMode: Boolean = false) {
  Dialog(
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    onDismissRequest = { if (viewingMode) onTosAccepted() },
  ) {
    Card(shape = RoundedCornerShape(28.dp)) {
      Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        // Title.
        val titleColor = MaterialTheme.colorScheme.onSurface
        BasicText(
          stringResource(R.string.tos_dialog_title),
          modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
          style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
          color = { titleColor },
          maxLines = 1,
          autoSize =
            TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 24.sp, stepSize = 1.sp),
        )

        Column(modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
          MarkdownText(
            ECHOSENSE_TERMS_AND_PRIVACY,
            smallFontSize = true,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
          )
        }

        // Accept button.
        Button(
          onClick = onTosAccepted,
          modifier = Modifier.padding(top = 28.dp, bottom = 24.dp).align(Alignment.End),
        ) {
          Text(
            stringResource(
              if (viewingMode) R.string.close else R.string.tos_dialog_view_accept_button_label
            )
          )
        }
      }
    }
  }
}

private val ECHOSENSE_TERMS_AND_PRIVACY =
  """
  ## Terms and safety notice

  EchoSense-AI provides experimental visual, document, translation, currency, and navigation assistance. The app can make mistakes, miss hazards, hallucinate descriptions, mistranslate text, misread documents, or identify currency incorrectly.

  **Do not rely on EchoSense-AI as your only source of safety-critical information.** Vision assistance and collision avoidance are not a substitute for a cane, guide dog, sighted assistance, mobility training, traffic signals, medical devices, emergency services, or your own judgment.

  Do not rely on EchoSense-AI for:

  - personal safety, navigation safety, traffic decisions, obstacle avoidance, or emergency response;
  - legal, medical, financial, immigration, insurance, tax, or other professional advice;
  - legal document analysis, contract interpretation, official forms, or compliance decisions;
  - monetary transactions, currency authenticity, prices, account numbers, checks, bills, or payment instructions;
  - identity verification, security screening, access control, or law-enforcement decisions;
  - medication labels, dosage instructions, allergens, hazardous materials, or health and safety warnings;
  - any decision where an incorrect result could cause injury, loss, legal exposure, or property damage.

  Always verify important information with a trusted person, official source, professional advisor, or dedicated safety tool. You are responsible for how you use the app and for any decisions you make based on its output.

  ## Privacy notice

  EchoSense-AI is designed primarily for on-device processing. Camera frames, selected photos, selected documents, microphone input for voice commands, OCR text, translations, and model outputs are processed on your device when local models and local Android services are used.

  The app may access the camera, microphone, files you choose, and downloaded or bundled model files only to provide the features you request. Camera preview and analysis frames are not intentionally saved by the app unless you explicitly choose files or platform components cache data as part of normal operation.

  Some features may use Google or Android services:

  - Safety depth sensing uses Google Play Services for AR (ARCore), which is provided by Google LLC and governed by the [Google Privacy Policy](https://policies.google.com/privacy). ARCore is optional; EchoSense-AI falls back to camera-only estimates when it is unavailable.
  - ML Kit OCR, language identification, and translation may use Google Play services or download language/model packs.
  - If you connect and use an online AI provider, the images, document text, prompts, and related content needed for that request are sent directly to the selected provider and handled under that provider's terms. Provider charges may apply.
  - Android system text-to-speech, speech recognition, camera, audio, and accessibility services are provided by the device or OS vendor and may have their own settings and privacy behavior.
  - App builds that include analytics or crash reporting may collect basic diagnostics, crash, performance, or usage information.

  Do not scan highly sensitive documents, credentials, financial information, medical information, private keys, or other confidential content unless you understand which local and online services are active and accept the risk.

  ## No warranty

  EchoSense-AI is provided as-is, without warranties of accuracy, availability, fitness for a particular purpose, or non-infringement. To the maximum extent permitted by law, the app developers and contributors are not liable for losses or damages arising from your use of the app or reliance on its outputs.
  """.trimIndent()
