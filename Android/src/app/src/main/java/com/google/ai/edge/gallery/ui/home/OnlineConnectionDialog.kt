package com.google.ai.edge.gallery.ui.home

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.ui.echosense.OnlineAnalysisHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.launch

@Composable
fun OnlineConnectionDialog(
  modelManagerViewModel: ModelManagerViewModel,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  val scope = rememberCoroutineScope()
  val connectedKey by AppSettings.geminiApiKey.collectAsState()
  val savedProvider by AppSettings.onlineProvider.collectAsState()
  val savedMode by AppSettings.onlineUsageMode.collectAsState()
  var provider by remember { mutableStateOf(savedProvider) }
  var usageMode by remember { mutableStateOf(savedMode) }
  var apiKey by remember { mutableStateOf("") }
  var isTesting by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  val isConnected = connectedKey.isNotBlank()
  val needsValidation = !isConnected || provider != savedProvider || apiKey.trim().isNotEmpty()

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Online Analysis") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text("Provider", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OnlineProvider.entries.forEach { option ->
            FilterChip(
              selected = provider == option,
              onClick = { provider = option; error = null },
              label = { Text(option.displayName) },
            )
          }
        }
        val keyUrl =
          if (provider == OnlineProvider.GEMINI) "https://aistudio.google.com/app/apikey"
          else "https://platform.openai.com/api-keys"
        TextButton(
          onClick = {
            CustomTabsIntent.Builder()
              .setShowTitle(true)
              .build()
              .launchUrl(context, Uri.parse(keyUrl))
          }
        ) {
          Text("Open ${provider.displayName} key page")
        }
        Text(
          "Create and copy the key, then use the Custom Tab close button to return here and tap Paste.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (isConnected && provider == savedProvider) {
          Text("Connected key: ${AppSettings.maskedOnlineKey()}")
        }
        OutlinedTextField(
          value = apiKey,
          onValueChange = { apiKey = it; error = null },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text(if (isConnected) "Paste replacement key" else "Paste API key") },
          visualTransformation = PasswordVisualTransformation(),
          trailingIcon = {
            TextButton(onClick = { apiKey = clipboard.getText()?.text.orEmpty() }) { Text("Paste") }
          },
        )

        Text("When to use it", style = MaterialTheme.typography.titleSmall)
        OnlineUsageMode.entries.forEach { mode ->
          Row(
            modifier = Modifier.fillMaxWidth().clickable { usageMode = mode }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(selected = usageMode == mode, onClick = { usageMode = mode })
            Column {
              Text(mode.displayName)
              Text(
                when (mode) {
                  OnlineUsageMode.ASK -> "On-device stays primary; use the online button when wanted."
                  OnlineUsageMode.FALLBACK -> "Use online analysis only if local analysis fails."
                  OnlineUsageMode.PREFER_ONLINE -> "Use online analysis first when connected."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        Text("Privacy and cost", style = MaterialTheme.typography.titleSmall)
        Text(
          "When online analysis is used, the current image and prompt are sent directly to ${provider.displayName}. " +
            "The key is encrypted with Android Keystore. Provider charges may apply.",
          style = MaterialTheme.typography.bodySmall,
        )
        Text(
          "Do not send sensitive documents or information unless you accept the provider's data practices.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      }
    },
    confirmButton = {
      Button(
        enabled = !isTesting && (!needsValidation || apiKey.trim().isNotEmpty()),
        onClick = {
          isTesting = true
          error = null
          scope.launch {
            try {
              val key = apiKey.trim().ifEmpty { connectedKey }
              if (needsValidation) OnlineAnalysisHelper.validateKey(provider, key)
              AppSettings.saveOnlineConnection(context, provider, key, usageMode)
              modelManagerViewModel.saveEchoSenseSettings()
              onDismiss()
            } catch (e: Exception) {
              error = e.message ?: "Unable to connect"
              isTesting = false
            }
          }
        },
      ) { Text(if (isTesting) "Testing…" else if (needsValidation) "Test and Save" else "Save") }
    },
    dismissButton = {
      Row {
        if (isConnected) {
          TextButton(onClick = {
            AppSettings.removeOnlineConnection(context)
            modelManagerViewModel.saveEchoSenseSettings()
            onDismiss()
          }) { Text("Remove") }
        }
        TextButton(onClick = onDismiss) { Text("Cancel") }
      }
    },
  )
}
