package com.google.ai.edge.gallery.ui.echosense.documenttranslator

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BundledModelHelper
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.echosense.BUNDLED_LLM_ASSET_NAME
import com.google.ai.edge.gallery.ui.echosense.createBundledGemma3nForChat
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

class DocumentTranslatorTask @Inject constructor() : CustomTask {
    override val task: Task = Task(
        id = BuiltInTaskId.DOCUMENT_TRANSLATOR,
        label = "Document Translator",
        category = Category.ECHOSENSE,
        icon = Icons.Outlined.Translate,
        models = mutableListOf(createBundledGemma3nForChat()),
        description = "Translate documents to English from photos or files",
        textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
    )

    override fun initializeModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: (String) -> Unit) {
        if (model.downloadFileName == BUNDLED_LLM_ASSET_NAME) {
            val copied = BundledModelHelper.ensureBundledAssetCopied(context, BUNDLED_LLM_ASSET_NAME)
            if (!copied) { onDone("Failed to copy bundled asset"); return }
        }
        LlmChatModelHelper.initialize(context = context, model = model, supportImage = true, supportAudio = false, onDone = onDone)
    }

    override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) {
        LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
    }

    @Composable
    override fun MainScreen(data: Any) {
        val myData = data as CustomTaskDataForBuiltinTask
        val viewModel: DocumentTranslatorViewModel = hiltViewModel()
        val modelManagerViewModel = myData.modelManagerViewModel
        val context = androidx.compose.ui.platform.LocalContext.current
        val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
        val selectedModel = modelManagerUiState.selectedModel
        val curDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]

        LaunchedEffect(curDownloadStatus, selectedModel.name) {
            if (curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED || selectedModel.downloadFileName == BUNDLED_LLM_ASSET_NAME) {
                val modelInitStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
                if (modelInitStatus?.status != ModelInitializationStatusType.INITIALIZED) {
                    modelManagerViewModel.initializeModel(context = context, task = task, model = selectedModel)
                }
            }
        }
        LaunchedEffect(selectedModel.name) { viewModel.setModel(selectedModel) }

        DocumentTranslatorScreen(onNavigateUp = myData.onNavUp, viewModel = viewModel, modelManagerViewModel = modelManagerViewModel)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object DocumentTranslatorTaskModule {
    @Provides @IntoSet
    fun provideTask(): CustomTask = DocumentTranslatorTask()
}
