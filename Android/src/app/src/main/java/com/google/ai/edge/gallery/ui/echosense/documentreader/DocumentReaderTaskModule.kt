package com.google.ai.edge.gallery.ui.echosense.documentreader

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
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

class DocumentReaderTask @Inject constructor() : CustomTask {
    override val task: Task = Task(
        id = BuiltInTaskId.DOCUMENT_READER,
        label = "Document Reader",
        category = Category.ECHOSENSE,
        icon = Icons.Outlined.Description,
        models = createEchoSenseGemmaModels().toMutableList(),
        description = "Read documents aloud from photos or files",
        textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
    )

    override fun initializeModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: (String) -> Unit) {
        LlmChatModelHelper.initialize(context = context, model = model, supportImage = true, supportAudio = false, onDone = onDone)
    }

    override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) {
        LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
    }

    @Composable
    override fun MainScreen(data: Any) {
        val myData = data as CustomTaskDataForBuiltinTask
        val viewModel: DocumentReaderViewModel = hiltViewModel()
        val modelManagerViewModel = myData.modelManagerViewModel
        val context = androidx.compose.ui.platform.LocalContext.current
        val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
        val selectedModel = modelManagerUiState.selectedModel
        val curDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]

        LaunchedEffect(curDownloadStatus, selectedModel.name) {
            if (curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
                val modelInitStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
                if (selectedModel.instance == null ||
                    modelInitStatus?.status != ModelInitializationStatusType.INITIALIZED) {
                    modelManagerViewModel.initializeModel(context = context, task = task, model = selectedModel)
                }
            }
        }
        LaunchedEffect(selectedModel.name) { viewModel.setModel(selectedModel) }

        DocumentReaderScreen(viewModel = viewModel, modelManagerViewModel = modelManagerViewModel)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object DocumentReaderTaskModule {
    @Provides @IntoSet
    fun provideTask(): CustomTask = DocumentReaderTask()
}
