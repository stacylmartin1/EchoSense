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

package com.terranet.echosense.android.ui.modelmanager

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.terranet.echosense.android.AppLifecycleProvider
import com.terranet.echosense.android.customtasks.common.CustomTask
import com.terranet.echosense.android.data.Accelerator
import com.terranet.echosense.android.data.Config
import com.terranet.echosense.android.data.DataStoreRepository
import com.terranet.echosense.android.data.DownloadRepository
import com.terranet.echosense.android.data.EMPTY_MODEL
import com.terranet.echosense.android.data.IMPORTS_DIR
import com.terranet.echosense.android.data.Model
import com.terranet.echosense.android.data.ModelAllowlist
import com.terranet.echosense.android.data.ModelDownloadStatus
import com.terranet.echosense.android.data.ModelDownloadStatusType
import com.terranet.echosense.android.data.TMP_FILE_EXT
import com.terranet.echosense.android.data.Task
import com.terranet.echosense.android.data.createLlmChatConfigs
import com.terranet.echosense.android.proto.ImportedModel
import com.terranet.echosense.android.proto.Theme
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


private const val TAG = "AGModelManagerViewModel"
private const val TEXT_INPUT_HISTORY_MAX_SIZE = 50
private const val MODEL_ALLOWLIST_TEST_FILENAME = "model_allowlist_test.json"
private const val ECHOSENSE_MODEL_PREFS = "echosense_model_selection"
private const val SELECTED_MODEL_KEY = "selected_model_id"

data class ModelInitializationStatus(
  val status: ModelInitializationStatusType,
  var error: String = "",
)

enum class ModelInitializationStatusType {
  NOT_INITIALIZED,
  INITIALIZING,
  INITIALIZED,
  ERROR,
}



data class ModelManagerUiState(
  /** A list of tasks available in the application. */
  val tasks: List<Task>,

  /** A map that tracks the download status of each model, indexed by model name. */
  val modelDownloadStatus: Map<String, ModelDownloadStatus>,

  /** A map that tracks the initialization status of each model, indexed by model name. */
  val modelInitializationStatus: Map<String, ModelInitializationStatus>,

  /** Whether the app is loading and processing the model allowlist. */
  val loadingModelAllowlist: Boolean = true,

  /** The error message when loading the model allowlist. */
  val loadingModelAllowlistError: String = "",

  /** The currently selected model. */
  val selectedModel: Model = EMPTY_MODEL,

  /** The history of text inputs entered by the user. */
  val textInputHistory: List<String> = listOf(),
  val configValuesUpdateTrigger: Long = 0L,
) {
  fun isModelInitialized(model: Model): Boolean {
    return model.instance != null &&
      modelInitializationStatus[model.name]?.status == ModelInitializationStatusType.INITIALIZED
  }

  fun isModelInitializing(model: Model): Boolean {
    return modelInitializationStatus[model.name]?.status ==
      ModelInitializationStatusType.INITIALIZING
  }
}

/**
 * ViewModel responsible for managing models, their download status, and initialization.
 *
 * This ViewModel handles model-related operations such as downloading, deleting, initializing, and
 * cleaning up models. It also manages the UI state for model management, including the list of
 * tasks, models, download statuses, and initialization statuses.
 */
@HiltViewModel
open class ModelManagerViewModel
@Inject
constructor(
  private val downloadRepository: DownloadRepository,
  private val dataStoreRepository: DataStoreRepository,
  private val lifecycleProvider: AppLifecycleProvider,
  private val customTasks: Set<@JvmSuppressWildcards CustomTask>,
  @ApplicationContext private val context: Context,
) : ViewModel() {
  private val externalFilesDir = context.getExternalFilesDir(null)
  protected val _uiState = MutableStateFlow(createEmptyUiState())
  val uiState = _uiState.asStateFlow()

  override fun onCleared() {
    super.onCleared()
  }

  fun getTaskById(id: String): Task? {
    return uiState.value.tasks.find { it.id == id }
  }

  fun getTasksByIds(ids: Set<String>): List<Task> {
    return uiState.value.tasks.filter { ids.contains(it.id) }
  }

  fun getCustomTaskByTaskId(id: String): CustomTask? {
    return customTasks.find { it.task.id == id }
  }

  fun getModelByName(name: String): Model? {
    for (task in uiState.value.tasks) {
      for (model in task.models) {
        if (model.name == name) {
          return model
        }
      }
    }
    return null
  }

  fun processTasks() {
    val curTasks = customTasks.map { it.task }
    for (task in curTasks) {
      for (model in task.models) {
        model.preProcess()
      }
      // Move the model that is best for this task to the front.
      val bestModel = task.models.find { it.bestForTaskIds.contains(task.id) }
      if (bestModel != null) {
        task.models.remove(bestModel)
        task.models.add(0, bestModel)
      }
    }
  }

  fun updateConfigValuesUpdateTrigger() {
    _uiState.update { _uiState.value.copy(configValuesUpdateTrigger = System.currentTimeMillis()) }
  }

  fun selectModel(model: Model) {
    context.getSharedPreferences(ECHOSENSE_MODEL_PREFS, Context.MODE_PRIVATE)
      .edit()
      .putString(SELECTED_MODEL_KEY, model.name)
      .apply()
    _uiState.update { _uiState.value.copy(selectedModel = model) }
  }

  fun downloadModel(task: Task, model: Model) {
    // Function screens observe selectedModel for download and initialization state. Downloads
    // started from onboarding or Settings must select the same model before status updates begin.
    selectModel(model)
    val shouldResume =
      uiState.value.modelDownloadStatus[model.name]?.status ==
        ModelDownloadStatusType.PARTIALLY_DOWNLOADED
    // Preserve a partial file so the worker can resume with an HTTP Range request.
    if (!shouldResume) {
      deleteModel(task = task, model = model)
    }

    setDownloadStatus(
      curModel = model,
      status = ModelDownloadStatus(
        status = ModelDownloadStatusType.IN_PROGRESS,
        totalBytes = model.totalBytes,
      ),
    )

    // Start to send download request.
    downloadRepository.downloadModel(
      task = task,
      model = model,
      onStatusUpdated = this::setDownloadStatus,
    )
  }

  fun cancelDownloadModel(task: Task, model: Model) {
    downloadRepository.cancelDownloadModel(model)
    deleteModel(task = task, model = model)
  }

  fun deleteModel(task: Task, model: Model) {
    if (model.imported) {
      deleteFilesFromImportDir(model.downloadFileName)
    } else {
      deleteDirFromExternalFilesDir(model.normalizedName)
    }

    // Update model download status to NotDownloaded.
    val curModelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    curModelDownloadStatus[model.name] =
      ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)

    // Delete model from the list if model is imported as a local model.
    if (model.imported) {
      for (curTask in uiState.value.tasks) {
        val index = curTask.models.indexOf(model)
        if (index >= 0) {
          curTask.models.removeAt(index)
        }
        curTask.updateTrigger.value = System.currentTimeMillis()
      }
      curModelDownloadStatus.remove(model.name)

      // Update data store.
      val importedModels = dataStoreRepository.readImportedModels().toMutableList()
      val importedModelIndex = importedModels.indexOfFirst { it.fileName == model.name }
      if (importedModelIndex >= 0) {
        importedModels.removeAt(importedModelIndex)
      }
      dataStoreRepository.saveImportedModels(importedModels = importedModels)
    }
    val newUiState =
      uiState.value.copy(
        modelDownloadStatus = curModelDownloadStatus,
        tasks = uiState.value.tasks.toList(),
      )
    _uiState.update { newUiState }
  }

  fun initializeModel(context: Context, task: Task, model: Model, force: Boolean = false) {
    viewModelScope.launch(Dispatchers.Default) {
      // The status map is keyed by name, so never trust it without checking the exact
      // Model object's native instance as well.
      if (
        !force &&
          model.instance != null &&
          uiState.value.modelInitializationStatus[model.name]?.status ==
            ModelInitializationStatusType.INITIALIZED
      ) {
        Log.d(TAG, "Model '${model.name}' has been initialized. Skipping.")
        return@launch
      }
      if (
        model.instance == null &&
          uiState.value.modelInitializationStatus[model.name]?.status ==
            ModelInitializationStatusType.INITIALIZED
      ) {
        Log.w(TAG, "Repairing stale initialized state for '${model.name}' with no native instance")
        updateModelInitializationStatus(model, ModelInitializationStatusType.NOT_INITIALIZED)
      }

      // Skip if initialization is in progress.
      if (model.initializing) {
        model.cleanUpAfterInit = false
        Log.d(TAG, "Model '${model.name}' is being initialized. Skipping.")
        return@launch
      }

      // A model choice is shared by every EchoSense function. Release any other
      // initialized model before loading this one so E2B and E4B do not compete
      // for GPU memory after the user switches models.
      customTasks.forEach { customTask ->
        customTask.task.models
          .filter { otherModel -> otherModel.name != model.name && otherModel.instance != null }
          .forEach { otherModel ->
            cleanupModel(context = context, task = customTask.task, model = otherModel)
          }
      }

      // Clean up.
      cleanupModel(context = context, task = task, model = model)

      // Start initialization.
      Log.d(
        TAG,
        "Initializing model '${model.name}'; object=${System.identityHashCode(model)} " +
          "existingInstance=${model.instance != null}",
      )
      model.initializing = true
      updateModelInitializationStatus(
        model = model,
        status = ModelInitializationStatusType.INITIALIZING,
      )

      val onDone: (error: String) -> Unit = { error ->
        model.initializing = false
        if (model.instance != null) {
          Log.d(TAG, "Model '${model.name}' initialized successfully")
          updateModelInitializationStatus(
            model = model,
            status = ModelInitializationStatusType.INITIALIZED,
          )
          if (model.cleanUpAfterInit) {
            Log.d(TAG, "Model '${model.name}' needs cleaning up after init.")
            cleanupModel(context = context, task = task, model = model)
          }
        } else if (error.isNotEmpty()) {
          Log.d(TAG, "Model '${model.name}' failed to initialize")
          updateModelInitializationStatus(
            model = model,
            status = ModelInitializationStatusType.ERROR,
            error = error,
          )
        }
      }

      // Call the model initialization function.
      getCustomTaskByTaskId(id = task.id)
        ?.initializeModelFn(
          context = context,
          coroutineScope = viewModelScope,
          model = model,
          onDone = onDone,
        )
    }
  }

  fun cleanupModel(context: Context, task: Task, model: Model, onDone: () -> Unit = {}) {
    if (model.instance != null) {
      model.cleanUpAfterInit = false
      Log.d(TAG, "Cleaning up model '${model.name}'...")
      val onDone: () -> Unit = {
        model.instance = null
        model.initializing = false
        updateModelInitializationStatus(
          model = model,
          status = ModelInitializationStatusType.NOT_INITIALIZED,
        )
        Log.d(TAG, "Clean up model '${model.name}' done")
        onDone()
      }
      getCustomTaskByTaskId(id = task.id)
        ?.cleanUpModelFn(
          context = context,
          coroutineScope = viewModelScope,
          model = model,
          onDone = onDone,
        )
    } else {
      // When model is being initialized and we are trying to clean it up at same time, we mark it
      // to clean up and it will be cleaned up after initialization is done.
      if (model.initializing) {
        Log.d(
          TAG,
          "Model '${model.name}' is still initializing.. Will clean up after it is done initializing",
        )
        model.cleanUpAfterInit = true
      }
    }
  }

  fun setDownloadStatus(curModel: Model, status: ModelDownloadStatus) {
    // Update model download progress.
    val curModelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    curModelDownloadStatus[curModel.name] = status
    val newUiState = uiState.value.copy(modelDownloadStatus = curModelDownloadStatus)

    // Delete downloaded file if status is failed or not_downloaded.
    if (
      status.status == ModelDownloadStatusType.FAILED ||
        status.status == ModelDownloadStatusType.NOT_DOWNLOADED
    ) {
      deleteFileFromExternalFilesDir(curModel.downloadFileName)
    }

    _uiState.update { newUiState }
  }

  fun setInitializationStatus(model: Model, status: ModelInitializationStatus) {
    val curStatus = uiState.value.modelInitializationStatus.toMutableMap()
    if (curStatus.containsKey(model.name)) {
      curStatus[model.name] = status
      _uiState.update { _uiState.value.copy(modelInitializationStatus = curStatus) }
    }
  }

  fun addTextInputHistory(text: String) {
    if (uiState.value.textInputHistory.indexOf(text) < 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.add(0, text)
      if (newHistory.size > TEXT_INPUT_HISTORY_MAX_SIZE) {
        newHistory.removeAt(newHistory.size - 1)
      }
      _uiState.update { _uiState.value.copy(textInputHistory = newHistory) }
      dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
    } else {
      promoteTextInputHistoryItem(text)
    }
  }

  fun promoteTextInputHistoryItem(text: String) {
    val index = uiState.value.textInputHistory.indexOf(text)
    if (index >= 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.removeAt(index)
      newHistory.add(0, text)
      _uiState.update { _uiState.value.copy(textInputHistory = newHistory) }
      dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
    }
  }

  fun deleteTextInputHistory(text: String) {
    val index = uiState.value.textInputHistory.indexOf(text)
    if (index >= 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.removeAt(index)
      _uiState.update { _uiState.value.copy(textInputHistory = newHistory) }
      dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
    }
  }

  fun clearTextInputHistory() {
    _uiState.update { _uiState.value.copy(textInputHistory = mutableListOf()) }
    dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
  }

  fun readThemeOverride(): Theme {
    return dataStoreRepository.readTheme()
  }

  fun saveThemeOverride(theme: Theme) {
    dataStoreRepository.saveTheme(theme = theme)
  }

  fun loadEchoSenseSettings() {
    com.terranet.echosense.android.ui.home.AppSettings.loadFrom(dataStoreRepository, context)
  }

  fun saveEchoSenseSettings() {
    com.terranet.echosense.android.ui.home.AppSettings.persistTo(dataStoreRepository)
  }

  fun getModelUrlResponse(model: Model): Int {
    try {
      val url = URL(model.url)
      val connection = url.openConnection() as HttpURLConnection
      connection.connect()

      // Report the result.
      return connection.responseCode
    } catch (e: Exception) {
      Log.e(TAG, "$e")
      return -1
    }
  }

  fun addImportedLlmModel(info: ImportedModel) {
    Log.d(TAG, "adding imported llm model: $info")

    // Create model.
    val model = createModelFromImportedModelInfo(info = info)

    for (task in uiState.value.tasks) {
      // Remove duplicated imported model if existed.
      val modelIndex = task.models.indexOfFirst { info.fileName == it.name && it.imported }
      if (modelIndex >= 0) {
        Log.d(TAG, "duplicated imported model found in task. Removing it first")
        task.models.removeAt(modelIndex)
      }
      task.models.add(model)
      task.updateTrigger.value = System.currentTimeMillis()
    }

    // Add initial status and states.
    val modelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    val modelInstances = uiState.value.modelInitializationStatus.toMutableMap()
    modelDownloadStatus[model.name] =
      ModelDownloadStatus(
        status = ModelDownloadStatusType.SUCCEEDED,
        receivedBytes = info.fileSize,
        totalBytes = info.fileSize,
      )
    modelInstances[model.name] =
      ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED)

    // Update ui state.
    _uiState.update {
      uiState.value.copy(
        tasks = uiState.value.tasks.toList(),
        modelDownloadStatus = modelDownloadStatus,
        modelInitializationStatus = modelInstances,
      )
    }

    // Add to data store.
    val importedModels = dataStoreRepository.readImportedModels().toMutableList()
    val importedModelIndex = importedModels.indexOfFirst { info.fileName == it.fileName }
    if (importedModelIndex >= 0) {
      Log.d(TAG, "duplicated imported model found in data store. Removing it first")
      importedModels.removeAt(importedModelIndex)
    }
    importedModels.add(info)
    dataStoreRepository.saveImportedModels(importedModels = importedModels)
  }



  private fun processPendingDownloads() {
    // Cancel all pending downloads for the retrieved models.
    downloadRepository.cancelAll {
      Log.d(TAG, "All workers are cancelled.")

      viewModelScope.launch(Dispatchers.Main) {
        val checkedModelNames = mutableSetOf<String>()
        for (task in uiState.value.tasks) {
          for (model in task.models) {
            if (checkedModelNames.contains(model.name)) {
              continue
            }

            // Start download for partially downloaded models.
            val downloadStatus = uiState.value.modelDownloadStatus[model.name]?.status
            if (downloadStatus == ModelDownloadStatusType.PARTIALLY_DOWNLOADED) {
              Log.d(TAG, "Sending a new download request for '${model.name}'")
              downloadRepository.downloadModel(
                task = task,
                model = model,
                onStatusUpdated = this@ModelManagerViewModel::setDownloadStatus,
              )
            }

            checkedModelNames.add(model.name)
          }
        }
      }
    }
  }

  fun loadModelAllowlist() {
    // Phase 1: Register tasks with their small offline catalog descriptor. The LLM itself is not
    // packaged with the app.
    val curTasks = customTasks.map { it.task }
    processTasks()
    _uiState.update {
      createUiState().copy(loadingModelAllowlist = false, tasks = curTasks)
    }
    Log.d(TAG, "Registered ${curTasks.size} tasks with offline model metadata")

    // Load EchoSense settings from DataStore into AppSettings
    loadEchoSenseSettings()

    // Phase 2: retain the upstream test-allowlist hook. Production EchoSense models are pinned
    // locally to the public URLs referenced by Google AI Edge Gallery, so no EchoSense backend
    // catalog is required.
    viewModelScope.launch(Dispatchers.IO) {
      try {
        if (migrateLegacyBundledModel(curTasks)) {
          processTasks()
          _uiState.update { createUiState().copy(loadingModelAllowlist = false, tasks = curTasks) }
        }
        var modelAllowlist: ModelAllowlist? = null

        // Try to read the test allowlist first.
        Log.d(TAG, "Loading test model allowlist.")
        modelAllowlist = readModelAllowlistFromDisk(fileName = MODEL_ALLOWLIST_TEST_FILENAME)

        if (modelAllowlist == null) {
          Log.d(TAG, "Using embedded Google AI Edge model metadata")
          processPendingDownloads()
          return@launch
        }

        Log.d(TAG, "Allowlist: $modelAllowlist")
        require(modelAllowlist.schemaVersion == 1) { "Unsupported model catalog schema" }

        // Add downloadable models from the allowlist to tasks.
        val nameToModel = mutableMapOf<String, Model>()
        for (allowedModel in modelAllowlist.models) {
          if (!allowedModel.supportedPlatforms.contains("android")) {
            continue
          }

          val model = try {
            allowedModel.toModel()
          } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Ignoring invalid catalog model '${allowedModel.id}': ${e.message}")
            continue
          }
          nameToModel.put(model.name, model)
          for (taskType in model.bestForTaskIds) {
            val task = curTasks.find { it.id == taskType }
            task?.models?.removeAll { it.name == model.name }
            task?.models?.add(0, model)
          }
        }

        // Find models from allowlist if a task's `modelNames` field is not empty.
        for (task in curTasks) {
          if (task.modelNames.isNotEmpty()) {
            for (modelName in task.modelNames) {
              val model = nameToModel[modelName]
              if (model == null) {
                Log.w(TAG, "Model '${modelName}' in task '${task.label}' not found in allowlist.")
                continue
              }
              task.models.add(model)
            }
          }
        }

        // Re-process tasks with new models.
        processTasks()

        // Update UI state with additional models.
        _uiState.update { createUiState().copy(loadingModelAllowlist = false, tasks = curTasks) }

        // Process pending downloads.
        processPendingDownloads()
      } catch (e: Exception) {
        Log.w(TAG, "Error loading model allowlist in background", e)
      }
    }
  }

  fun clearLoadModelAllowlistError() {
    val curTasks = customTasks.map { it.task }
    processTasks()
    _uiState.update {
      createUiState()
        .copy(loadingModelAllowlist = false, tasks = curTasks, loadingModelAllowlistError = "")
    }
  }

  fun setAppInForeground(foreground: Boolean) {
    lifecycleProvider.isAppInForeground = foreground
  }

  private fun readModelAllowlistFromDisk(fileName: String): ModelAllowlist? {
    try {
      Log.d(TAG, "Reading model allowlist from disk: $fileName")
      val baseDir =
        if (fileName == MODEL_ALLOWLIST_TEST_FILENAME) File("/data/local/tmp") else externalFilesDir
      val file = File(baseDir, fileName)
      if (file.exists()) {
        val content = file.readText()
        Log.d(TAG, "Model allowlist content from local file: $content")

        val gson = Gson()
        return gson.fromJson(content, ModelAllowlist::class.java)
      }
    } catch (e: Exception) {
      Log.e(TAG, "failed to read model allowlist from disk", e)
      return null
    }

    return null
  }

  /** Reuses the model copied by older builds instead of forcing a multi-gigabyte redownload. */
  private fun migrateLegacyBundledModel(tasks: List<Task>): Boolean {
    val model = tasks.asSequence().flatMap { it.models.asSequence() }
      .firstOrNull { it.sha256.isNotEmpty() } ?: return false
    val legacy = File(externalFilesDir, "bundled/${model.downloadFileName}")
    if (!legacy.exists()) return false

    return try {
      val valid = legacy.length() == model.sizeInBytes && sha256(legacy) == model.sha256.lowercase()
      if (!valid) {
        Log.w(TAG, "Removing invalid legacy bundled model copy")
        legacy.delete()
        legacy.parentFile?.delete()
        false
      } else {
        val destination = File(model.getPath(context))
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()
        check(legacy.renameTo(destination)) { "Could not move legacy model" }
        legacy.parentFile?.delete()
        Log.i(TAG, "Migrated legacy model to ${destination.absolutePath}")
        true
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to migrate legacy model", e)
      false
    }
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
      val buffer = ByteArray(4 * 1024 * 1024)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun isModelPartiallyDownloaded(model: Model): Boolean {
    if (model.localModelFilePathOverride.isNotEmpty()) {
      return false
    }

    // A model is partially downloaded when the tmp file exists.
    val tmpFilePath =
      model.getPath(context = context, fileName = "${model.downloadFileName}.$TMP_FILE_EXT")
    return File(tmpFilePath).exists()
  }

  private fun createEmptyUiState(): ModelManagerUiState {
    return ModelManagerUiState(
      tasks = listOf(),
      modelDownloadStatus = mapOf(),
      modelInitializationStatus = mapOf(),
    )
  }

  private fun createUiState(): ModelManagerUiState {
    val modelDownloadStatus: MutableMap<String, ModelDownloadStatus> = mutableMapOf()
    val modelInstances: MutableMap<String, ModelInitializationStatus> = mutableMapOf()
    val tasks: MutableMap<String, Task> = mutableMapOf()
    val checkedModelNames = mutableSetOf<String>()
    for (customTask in customTasks) {
      val task = customTask.task
      tasks.put(key = task.id, value = task)
      for (model in task.models) {
        if (checkedModelNames.contains(model.name)) {
          continue
        }
        modelDownloadStatus[model.name] = getModelDownloadStatus(model = model)
        modelInstances[model.name] =
          ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED)
        checkedModelNames.add(model.name)
      }
    }

    // Load imported models.
    for (importedModel in dataStoreRepository.readImportedModels()) {
      Log.d(TAG, "stored imported model: $importedModel")

      // Create model.
      val model = createModelFromImportedModelInfo(info = importedModel)

      // Add to all tasks.
      for ((_, task) in tasks) {
        task.models.add(model)
      }

      // Update status.
      modelDownloadStatus[model.name] =
        ModelDownloadStatus(
          status = ModelDownloadStatusType.SUCCEEDED,
          receivedBytes = importedModel.fileSize,
          totalBytes = importedModel.fileSize,
        )
    }

    val textInputHistory = dataStoreRepository.readTextInputHistory()
    Log.d(TAG, "text input history: $textInputHistory")

    val availableModels = customTasks
      .asSequence()
      .flatMap { it.task.models.asSequence() }
      .filter { it.name != EMPTY_MODEL.name }
      .toList()
    val previousSelection = _uiState.value.selectedModel
    val persistedSelection = context.getSharedPreferences(ECHOSENSE_MODEL_PREFS, Context.MODE_PRIVATE)
      .getString(SELECTED_MODEL_KEY, null)
    val selectedModel =
      availableModels.firstOrNull { it.name == previousSelection.name }
        ?: availableModels.firstOrNull { it.name == persistedSelection }
        ?: availableModels.firstOrNull {
          modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED
        }
        ?: availableModels.firstOrNull()

    Log.d(TAG, "model download status: $modelDownloadStatus")
    return ModelManagerUiState(
      tasks = customTasks.map { it.task }.toList(),
      modelDownloadStatus = modelDownloadStatus,
      modelInitializationStatus = modelInstances,
      selectedModel = selectedModel ?: EMPTY_MODEL,
      textInputHistory = textInputHistory,
    )
  }

  private fun createModelFromImportedModelInfo(info: ImportedModel): Model {
    val accelerators: List<Accelerator> =
      info.llmConfig.compatibleAcceleratorsList.mapNotNull { acceleratorLabel ->
        when (acceleratorLabel.trim()) {
          Accelerator.GPU.label -> Accelerator.GPU
          Accelerator.CPU.label -> Accelerator.CPU
          else -> null // Ignore unknown accelerator labels
        }
      }
    val configs: MutableList<Config> =
      createLlmChatConfigs(
          defaultMaxToken = info.llmConfig.defaultMaxTokens,
          defaultTopK = info.llmConfig.defaultTopk,
          defaultTopP = info.llmConfig.defaultTopp,
          defaultTemperature = info.llmConfig.defaultTemperature,
          accelerators = accelerators,
        )
        .toMutableList()
    val llmSupportImage = info.llmConfig.supportImage
    val llmSupportAudio = info.llmConfig.supportAudio
    val model =
      Model(
        name = info.fileName,
        url = "",
        configs = configs,
        sizeInBytes = info.fileSize,
        downloadFileName = "$IMPORTS_DIR/${info.fileName}",
        showBenchmarkButton = false,
        showRunAgainButton = false,
        imported = true,
        llmSupportImage = llmSupportImage,
        llmSupportAudio = llmSupportAudio,
      )
    model.preProcess()

    return model
  }

  /**
   * Retrieves the download status of a model.
   *
   * This function determines the download status of a given model by checking if it's fully
   * downloaded, partially downloaded, or not downloaded at all. It also retrieves the received and
   * total bytes for partially downloaded models.
   */
  private fun getModelDownloadStatus(model: Model): ModelDownloadStatus {
    Log.d(TAG, "Checking model ${model.name} download status...")

    if (model.localFileRelativeDirPathOverride.isNotEmpty()) {
      Log.d(TAG, "Model has localFileRelativeDirPathOverride set. Set status to SUCCEEDED")
      return ModelDownloadStatus(
        status = ModelDownloadStatusType.SUCCEEDED,
        receivedBytes = 0,
        totalBytes = 0,
      )
    }

    var status = ModelDownloadStatusType.NOT_DOWNLOADED
    var receivedBytes = 0L
    var totalBytes = 0L

    // Partially downloaded.
    if (isModelPartiallyDownloaded(model = model)) {
      status = ModelDownloadStatusType.PARTIALLY_DOWNLOADED
      val tmpFilePath =
        model.getPath(context = context, fileName = "${model.downloadFileName}.$TMP_FILE_EXT")
      val tmpFile = File(tmpFilePath)
      receivedBytes = tmpFile.length()
      totalBytes = model.totalBytes
      Log.d(TAG, "${model.name} is partially downloaded. $receivedBytes/$totalBytes")
    }
    // Fully downloaded.
    else if (isModelDownloaded(model = model)) {
      status = ModelDownloadStatusType.SUCCEEDED
      Log.d(TAG, "${model.name} has been downloaded.")
    }
    // Not downloaded.
    else {
      Log.d(TAG, "${model.name} has not been downloaded.")
    }

    return ModelDownloadStatus(
      status = status,
      receivedBytes = receivedBytes,
      totalBytes = totalBytes,
    )
  }

  private fun isFileInExternalFilesDir(fileName: String): Boolean {
    if (externalFilesDir != null) {
      val file = File(externalFilesDir, fileName)
      return file.exists()
    } else {
      return false
    }
  }

  private fun isFileInDataLocalTmpDir(fileName: String): Boolean {
    val file = File("/data/local/tmp", fileName)
    return file.exists()
  }

  private fun deleteFileFromExternalFilesDir(fileName: String) {
    if (isFileInExternalFilesDir(fileName)) {
      val file = File(externalFilesDir, fileName)
      file.delete()
    }
  }

  /**
   * Deletes files from the the model imports directory whose absolute paths start with a given
   * prefix.
   */
  private fun deleteFilesFromImportDir(fileName: String) {
    val dir = context.getExternalFilesDir(null) ?: return

    val prefixAbsolutePath = "${context.getExternalFilesDir(null)}${File.separator}$fileName"
    val filesToDelete =
      File(dir, IMPORTS_DIR).listFiles { dirFile, name ->
        File(dirFile, name).absolutePath.startsWith(prefixAbsolutePath)
      } ?: arrayOf()
    for (file in filesToDelete) {
      Log.d(TAG, "Deleting file: ${file.name}")
      file.delete()
    }
  }

  private fun deleteDirFromExternalFilesDir(dir: String) {
    if (isFileInExternalFilesDir(dir)) {
      val file = File(externalFilesDir, dir)
      file.deleteRecursively()
    }
  }

  private fun updateModelInitializationStatus(
    model: Model,
    status: ModelInitializationStatusType,
    error: String = "",
  ) {
    val curModelInstance = uiState.value.modelInitializationStatus.toMutableMap()
    curModelInstance[model.name] = ModelInitializationStatus(status = status, error = error)
    val newUiState = uiState.value.copy(modelInitializationStatus = curModelInstance)
    _uiState.update { newUiState }
  }

  private fun isModelDownloaded(model: Model): Boolean {
    val modelRelativePath =
      listOf(model.normalizedName, model.version, model.downloadFileName)
        .joinToString(File.separator)
    val downloadedFileExists =
      model.downloadFileName.isNotEmpty() &&
        ((model.localModelFilePathOverride.isEmpty() &&
          isFileInExternalFilesDir(modelRelativePath)) ||
          (model.localModelFilePathOverride.isNotEmpty() &&
            File(model.localModelFilePathOverride).exists()))

    val unzippedDirectoryExists =
      model.isZip &&
        model.unzipDir.isNotEmpty() &&
        isFileInExternalFilesDir(
          listOf(model.normalizedName, model.version, model.unzipDir).joinToString(File.separator)
        )

    return downloadedFileExists || unzippedDirectoryExists
  }
}
