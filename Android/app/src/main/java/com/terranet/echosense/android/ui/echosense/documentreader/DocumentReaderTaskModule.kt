/*
 * Copyright 2026 TerraNet Technologies LLC
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

package com.terranet.echosense.android.ui.echosense.documentreader

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.terranet.echosense.android.R
import com.terranet.echosense.android.customtasks.common.CustomTask
import com.terranet.echosense.android.customtasks.common.CustomTaskDataForBuiltinTask
import com.terranet.echosense.android.data.BuiltInTaskId
import com.terranet.echosense.android.data.Category
import com.terranet.echosense.android.data.Model
import com.terranet.echosense.android.data.ModelDownloadStatusType
import com.terranet.echosense.android.data.Task
import com.terranet.echosense.android.ui.echosense.createEchoSenseGemmaModels
import com.terranet.echosense.android.ui.llmchat.LlmChatModelHelper
import com.terranet.echosense.android.ui.modelmanager.ModelInitializationStatusType
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

        DocumentReaderScreen(viewModel = viewModel)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object DocumentReaderTaskModule {
    @Provides @IntoSet
    fun provideTask(): CustomTask = DocumentReaderTask()
}
