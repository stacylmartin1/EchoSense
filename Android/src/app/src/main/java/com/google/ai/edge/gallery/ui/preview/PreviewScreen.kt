package com.google.ai.edge.gallery.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.ui.common.tos.TosDialog
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import com.google.ai.edge.gallery.ui.home.SettingsDialog
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

private val TASK_BANNER_ITEMS = listOf(
    TaskBannerItem(BuiltInTaskId.NAVIGATION_ASSISTANCE, "Navigate", Icons.Outlined.Navigation),
    TaskBannerItem(BuiltInTaskId.CURRENCY_MODE, "Currency", Icons.Outlined.AttachMoney),
    TaskBannerItem(BuiltInTaskId.DOCUMENT_READER, "Read", Icons.Outlined.Description),
    TaskBannerItem(BuiltInTaskId.DOCUMENT_TRANSLATOR, "Translate", Icons.Outlined.Translate),
)

@Composable
fun PreviewScreen(
    modelManagerViewModel: ModelManagerViewModel,
    tosViewModel: TosViewModel,
) {
    var selectedTaskId by rememberSaveable { mutableStateOf(BuiltInTaskId.NAVIGATION_ASSISTANCE) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showTosDialog by remember { mutableStateOf(!tosViewModel.getIsTosAccepted()) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle TOS first
    if (showTosDialog) {
        TosDialog(onTosAccepted = {
            showTosDialog = false
            tosViewModel.acceptTos()
        })
        return
    }

    // Use the default task's model as the shared model (all tasks use the same bundled model)
    val defaultCustomTask = modelManagerViewModel.getCustomTaskByTaskId(BuiltInTaskId.NAVIGATION_ASSISTANCE)
    val sharedModel = remember(defaultCustomTask) { defaultCustomTask?.task?.models?.firstOrNull() }
    LaunchedEffect(sharedModel) {
        sharedModel?.let { modelManagerViewModel.selectModel(it) }
    }

    // Get the currently active task
    val activeCustomTask = modelManagerViewModel.getCustomTaskByTaskId(selectedTaskId)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Active task screen fills remaining space above the banner
            Box(modifier = Modifier.weight(1f)) {
                if (activeCustomTask != null) {
                    key(selectedTaskId) {
                        activeCustomTask.MainScreen(
                            data = CustomTaskDataForBuiltinTask(
                                modelManagerViewModel = modelManagerViewModel,
                                onNavUp = { /* Main screen - nowhere to navigate */ },
                            )
                        )
                    }
                }
            }

            // Bottom task banner
            TaskBanner(
                selectedTaskId = selectedTaskId,
                onTaskSelected = { selectedTaskId = it },
                onSettingsClicked = { showSettingsDialog = true },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        )
    }

    // Handle allowlist loading errors
    val uiState by modelManagerViewModel.uiState.collectAsState()
    LaunchedEffect(uiState.loadingModelAllowlistError) {
        if (uiState.loadingModelAllowlistError.isNotEmpty()) {
            snackbarHostState.showSnackbar("Using bundled models only (offline mode)")
            modelManagerViewModel.clearLoadModelAllowlistError()
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            curThemeOverride = modelManagerViewModel.readThemeOverride(),
            modelManagerViewModel = modelManagerViewModel,
            onDismissed = { showSettingsDialog = false },
        )
    }
}

@Composable
private fun TaskBanner(
    selectedTaskId: String,
    onTaskSelected: (String) -> Unit,
    onSettingsClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            TASK_BANNER_ITEMS.forEach { item ->
                TaskBannerIcon(
                    icon = item.icon,
                    label = item.label,
                    isSelected = item.taskId == selectedTaskId,
                    onClick = { onTaskSelected(item.taskId) },
                )
            }
            TaskBannerIcon(
                icon = Icons.Outlined.Settings,
                label = "Settings",
                isSelected = false,
                onClick = onSettingsClicked,
            )
        }
    }
}

@Composable
private fun TaskBannerIcon(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val color = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .semantics {
                contentDescription = if (isSelected) "$label, selected" else label
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(26.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
        // Selected indicator line
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .width(if (isSelected) 24.dp else 0.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
        )
    }
}

private data class TaskBannerItem(
    val taskId: String,
    val label: String,
    val icon: ImageVector,
)
