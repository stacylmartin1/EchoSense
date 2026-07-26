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

package com.terranet.echosense.android.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/**
 * Creates EchoSense workers without relying on a persisted fully qualified class name.
 *
 * WorkManager stores worker class names in its database. Matching the stable class suffix lets an
 * app update resume a model download that was queued before the Kotlin namespace was renamed.
 */
class EchoSenseWorkerFactory : WorkerFactory() {
  override fun createWorker(
    appContext: Context,
    workerClassName: String,
    workerParameters: WorkerParameters,
  ): ListenableWorker? {
    return if (workerClassName.endsWith(".worker.DownloadWorker")) {
      DownloadWorker(appContext, workerParameters)
    } else {
      null
    }
  }
}
