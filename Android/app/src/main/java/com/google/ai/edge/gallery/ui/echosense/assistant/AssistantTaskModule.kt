package com.google.ai.edge.gallery.ui.echosense.assistant

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.echosense.createEchoSenseGemmaModels
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

class AssistantTask @Inject constructor() : CustomTask {
  override val task = Task(
    id = BuiltInTaskId.ASSISTANT,
    label = "Assistant",
    category = Category.ECHOSENSE,
    icon = Icons.Outlined.Forum,
    models = createEchoSenseGemmaModels().toMutableList(),
    description = "Multi-turn assistant for text, voice, images, and documents",
  )

  override fun initializeModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: (String) -> Unit) {
    LlmChatModelHelper.initialize(context, model, supportImage = true, supportAudio = false, onDone = onDone)
  }

  override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) {
    LlmChatModelHelper.cleanUp(model, onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val taskData = data as CustomTaskDataForBuiltinTask
    val modelManager = taskData.modelManagerViewModel
    val viewModel: AssistantViewModel = hiltViewModel()
    val managerState by modelManager.uiState.collectAsState()
    val selectedModel = managerState.selectedModel
    val context = androidx.compose.ui.platform.LocalContext.current
    val downloadStatus = managerState.modelDownloadStatus[selectedModel.name]
    val initializationStatus = managerState.modelInitializationStatus[selectedModel.name]?.status
    LaunchedEffect(downloadStatus, selectedModel.name) {
      if (
        downloadStatus?.status == ModelDownloadStatusType.SUCCEEDED &&
          (selectedModel.instance == null ||
            managerState.modelInitializationStatus[selectedModel.name]?.status !=
              ModelInitializationStatusType.INITIALIZED)
      ) {
        modelManager.initializeModel(context, task, selectedModel)
      }
    }
    LaunchedEffect(selectedModel.name) { viewModel.setModel(selectedModel) }
    LaunchedEffect(
      selectedModel.name,
      downloadStatus?.status,
      initializationStatus,
      selectedModel.instance,
    ) {
      val status = downloadStatus?.status ?: return@LaunchedEffect
      viewModel.announceStartupModelStatus(
        modelName = selectedModel.name,
        isModelInstalled = status == ModelDownloadStatusType.SUCCEEDED,
        isModelDownloadInProgress =
          status == ModelDownloadStatusType.IN_PROGRESS ||
            status == ModelDownloadStatusType.UNZIPPING,
        isModelReady =
          selectedModel.instance != null &&
            initializationStatus == ModelInitializationStatusType.INITIALIZED,
      )
    }
    AssistantScreen(
      viewModel = viewModel,
      bottomPadding = androidx.compose.ui.unit.Dp(0f),
      modelManagerViewModel = modelManager,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object AssistantTaskModule {
  @Provides @IntoSet fun provideTask(): CustomTask = AssistantTask()
}
