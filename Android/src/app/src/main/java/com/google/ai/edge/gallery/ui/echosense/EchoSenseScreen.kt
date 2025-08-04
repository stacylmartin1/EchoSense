package com.google.ai.edge.gallery.ui.echosense

import androidx.compose.runtime.collectAsState // For collecting StateFlow
import androidx.compose.runtime.getValue      // For delegate access (by viewModel.voiceCommand)
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoSenseScreen(
    onVisualAssistanceClick: () -> Unit = {},
    onAuditoryAssistanceClick: () -> Unit = {},
    viewModel: EchoSenseViewModel,
    modelManagerViewModel: com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
) {
    val voiceCommand by viewModel.voiceCommand.collectAsState<String?>() // This line
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("EchoSense Visual Assistance") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Button(onClick = onVisualAssistanceClick) {
                Text("Visual Assistance")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onAuditoryAssistanceClick) {
                Text("Auditory Assistance")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { viewModel.startListening() }) {
                Text("Start Listening")
            }
            voiceCommand?.let {
                Text(text = "Last command: $it")
            }
        }
    }
}

@Preview
@Composable
fun EchoSenseScreenPreview() {
    // Preview disabled due to required parameters
}