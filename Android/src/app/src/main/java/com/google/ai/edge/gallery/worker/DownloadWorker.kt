/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.ai.edge.gallery.data.KEY_MODEL_COMMIT_HASH
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_ERROR_MESSAGE
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_MODEL_DIR
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_RATE
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_RECEIVED_BYTES
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_REMAINING_MS
import com.google.ai.edge.gallery.data.KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES
import com.google.ai.edge.gallery.data.KEY_MODEL_EXTRA_DATA_URLS
import com.google.ai.edge.gallery.data.KEY_MODEL_IS_ZIP
import com.google.ai.edge.gallery.data.KEY_MODEL_NAME
import com.google.ai.edge.gallery.data.KEY_MODEL_SHA256
import com.google.ai.edge.gallery.data.KEY_MODEL_START_UNZIPPING
import com.google.ai.edge.gallery.data.KEY_MODEL_TOTAL_BYTES
import com.google.ai.edge.gallery.data.KEY_MODEL_UNZIPPED_DIR
import com.google.ai.edge.gallery.data.KEY_MODEL_URL
import com.google.ai.edge.gallery.data.TMP_FILE_EXT
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLongArray
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGDownloadWorker"

data class UrlAndFileName(val url: String, val fileName: String)

private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "model_download_channel_foreground"
private const val PARALLEL_DOWNLOAD_PARTS = 4
private var channelCreated = false

class DownloadWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {
  private val externalFilesDir = context.getExternalFilesDir(null)

  private val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  // Unique notification id.
  private val notificationId: Int = params.id.hashCode()

  init {
    if (!channelCreated) {
      // Create a notification channel for showing notifications for model downloading progress.
      val channel =
        NotificationChannel(
            FOREGROUND_NOTIFICATION_CHANNEL_ID,
            "Model Downloading",
            // Make it silent.
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply { description = "Notifications for model downloading" }
      notificationManager.createNotificationChannel(channel)
      channelCreated = true
    }
  }

  override suspend fun doWork(): Result {
    val fileUrl = inputData.getString(KEY_MODEL_URL)
    val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
    val version = inputData.getString(KEY_MODEL_COMMIT_HASH)!!
    val fileName = inputData.getString(KEY_MODEL_DOWNLOAD_FILE_NAME)
    val modelDir = inputData.getString(KEY_MODEL_DOWNLOAD_MODEL_DIR)!!
    val isZip = inputData.getBoolean(KEY_MODEL_IS_ZIP, false)
    val unzippedDir = inputData.getString(KEY_MODEL_UNZIPPED_DIR)
    val extraDataFileUrls = inputData.getString(KEY_MODEL_EXTRA_DATA_URLS)?.split(",") ?: listOf()
    val extraDataFileNames =
      inputData.getString(KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES)?.split(",") ?: listOf()
    val totalBytes = inputData.getLong(KEY_MODEL_TOTAL_BYTES, 0L)
    val expectedSha256 = inputData.getString(KEY_MODEL_SHA256)?.lowercase().orEmpty()

    return withContext(Dispatchers.IO) {
      if (fileUrl == null || fileName == null) {
        Result.failure()
      } else {
        return@withContext try {
          // Set the worker as a foreground service immediately.
          setForeground(createForegroundInfo(progress = 0, modelName = modelName))

          // Large catalog models use independent byte ranges so one slow CDN route does not
          // throttle the entire download. The existing sequential path remains available for zip
          // archives and multi-file models.
          if (
            totalBytes > 0L &&
              expectedSha256.isNotEmpty() &&
              !isZip &&
              extraDataFileUrls.isEmpty()
          ) {
            downloadModelInParallel(
              fileUrl = fileUrl,
              fileName = fileName,
              modelName = modelName,
              modelDir = modelDir,
              version = version,
              totalBytes = totalBytes,
              expectedSha256 = expectedSha256,
            )
            return@withContext Result.success()
          }

          // Collect data for all files.
          val allFiles: MutableList<UrlAndFileName> = mutableListOf()
          allFiles.add(UrlAndFileName(url = fileUrl, fileName = fileName))
          for (index in extraDataFileUrls.indices) {
            allFiles.add(
              UrlAndFileName(url = extraDataFileUrls[index], fileName = extraDataFileNames[index])
            )
          }
          Log.d(TAG, "About to download: $allFiles")

          // Download them in sequence.
          // TODO: maybe consider downloading them in parallel.
          var downloadedBytes = 0L
          val bytesReadSizeBuffer: MutableList<Long> = mutableListOf()
          val bytesReadLatencyBuffer: MutableList<Long> = mutableListOf()
          for (file in allFiles) {
            val url = URL(file.url)

            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true

            // Prepare output file's dir.
            val outputDir =
              File(
                applicationContext.getExternalFilesDir(null),
                listOf(modelDir, version).joinToString(separator = File.separator),
              )
            if (!outputDir.exists()) {
              outputDir.mkdirs()
            }

            val availableBytes = StatFs(outputDir.absolutePath).availableBytes
            val safetyMargin = 1L * 1024 * 1024 * 1024
            val remainingBytes = (totalBytes - downloadedBytes).coerceAtLeast(0)
            if (availableBytes < remainingBytes + safetyMargin) {
              throw IOException(
                "Not enough storage for model download. " +
                  "Need ${remainingBytes + safetyMargin} bytes, available $availableBytes"
              )
            }

            // Read the tmp file and see if it is partially downloaded.
            val outputTmpFile =
              File(
                applicationContext.getExternalFilesDir(null),
                listOf(modelDir, version, "${file.fileName}.$TMP_FILE_EXT")
                  .joinToString(separator = File.separator),
              )
            val outputFileBytes = outputTmpFile.length()
            if (outputFileBytes > 0) {
              Log.d(
                TAG,
                "File '${outputTmpFile.name}' partial size: ${outputFileBytes}. Trying to resume download",
              )
              connection.setRequestProperty("Range", "bytes=${outputFileBytes}-")
            }
            connection.connect()
            Log.d(TAG, "response code: ${connection.responseCode}")

            var appendToPartialFile = false
            if (
              connection.responseCode == HttpURLConnection.HTTP_OK ||
                connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            ) {
              val contentRange = connection.getHeaderField("Content-Range")

              if (connection.responseCode == HttpURLConnection.HTTP_PARTIAL && contentRange != null) {
                // Parse the Content-Range header
                val rangeParts = contentRange.substringAfter("bytes ").split("/")
                val byteRange = rangeParts[0].split("-")
                val startByte = byteRange[0].toLong()
                val endByte = byteRange[1].toLong()

                if (startByte != outputFileBytes) {
                  throw IOException(
                    "Server resumed at byte $startByte, expected $outputFileBytes"
                  )
                }

                Log.d(
                  TAG,
                  "Content-Range: $contentRange. Start bytes: ${startByte}, end bytes: $endByte",
                )

                downloadedBytes += startByte
                appendToPartialFile = startByte > 0
              } else {
                // Some servers ignore Range and return the whole object with 200. Never append that
                // response to a partial file or the resulting model will be silently corrupted.
                if (outputFileBytes > 0) {
                  Log.w(TAG, "Server ignored Range; restarting download from byte zero")
                  outputTmpFile.delete()
                }
                downloadedBytes = 0
              }
            } else {
              throw IOException("HTTP error code: ${connection.responseCode}")
            }

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(outputTmpFile, appendToPartialFile)

            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytesRead: Int
            var lastSetProgressTs: Long = 0
            var deltaBytes = 0L
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
              outputStream.write(buffer, 0, bytesRead)
              downloadedBytes += bytesRead
              deltaBytes += bytesRead

              // Report progress every 200 ms.
              val curTs = System.currentTimeMillis()
              if (curTs - lastSetProgressTs > 200) {
                // Calculate download rate.
                var bytesPerMs = 0f
                if (lastSetProgressTs != 0L) {
                  if (bytesReadSizeBuffer.size == 5) {
                    bytesReadSizeBuffer.removeAt(0)
                  }
                  bytesReadSizeBuffer.add(deltaBytes)
                  if (bytesReadLatencyBuffer.size == 5) {
                    bytesReadLatencyBuffer.removeAt(0)
                  }
                  bytesReadLatencyBuffer.add(curTs - lastSetProgressTs)
                  deltaBytes = 0L
                  bytesPerMs = bytesReadSizeBuffer.sum().toFloat() / bytesReadLatencyBuffer.sum()
                }

                // Calculate remaining seconds
                var remainingMs = 0f
                if (bytesPerMs > 0f && totalBytes > 0L) {
                  remainingMs = (totalBytes - downloadedBytes) / bytesPerMs
                }

                setProgress(
                  Data.Builder()
                    .putLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, downloadedBytes)
                    .putLong(KEY_MODEL_DOWNLOAD_RATE, (bytesPerMs * 1000).toLong())
                    .putLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, remainingMs.toLong())
                    .build()
                )
                setForeground(
                  createForegroundInfo(
                    progress = (downloadedBytes * 100 / totalBytes).toInt(),
                    modelName = modelName,
                  )
                )
                Log.d(TAG, "downloadedBytes: $downloadedBytes")
                lastSetProgressTs = curTs
              }
            }

            outputStream.close()
            inputStream.close()

            if (allFiles.size == 1 && totalBytes > 0 && outputTmpFile.length() != totalBytes) {
              throw IOException(
                "Downloaded size ${outputTmpFile.length()} does not match expected $totalBytes"
              )
            }
            if (file == allFiles.first() && expectedSha256.isNotEmpty()) {
              val actualSha256 = sha256(outputTmpFile)
              if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                outputTmpFile.delete()
                throw IOException("Downloaded model checksum does not match the catalog")
              }
            }

            // Rename the tmp file to the original file name by removing the tmp file ext.
            val originalFilePath = outputTmpFile.absolutePath.replace(".$TMP_FILE_EXT", "")
            val originalFile = File(originalFilePath)
            if (originalFile.exists()) {
              originalFile.delete()
            }
            if (!outputTmpFile.renameTo(originalFile)) {
              throw IOException("Could not install the completed model file")
            }
            Log.d(TAG, "Download done")

            // Unzip if the downloaded file is a zip.
            if (isZip && unzippedDir != null) {
              setProgress(Data.Builder().putBoolean(KEY_MODEL_START_UNZIPPING, true).build())

              // Prepare target dir.
              val destDir =
                File(
                  externalFilesDir,
                  listOf(modelDir, version, unzippedDir).joinToString(File.separator),
                )
              if (!destDir.exists()) {
                destDir.mkdirs()
              }

              // Unzip.
              val unzipBuffer = ByteArray(4096)
              val zipFilePath =
                "${externalFilesDir}${File.separator}$modelDir${File.separator}$version${File.separator}${fileName}"
              val zipIn = ZipInputStream(BufferedInputStream(FileInputStream(zipFilePath)))
              var zipEntry: ZipEntry? = zipIn.nextEntry

              while (zipEntry != null) {
                val filePath = destDir.absolutePath + File.separator + zipEntry.name

                // Extract files.
                if (!zipEntry.isDirectory) {
                  // extract file
                  val bos = FileOutputStream(filePath)
                  bos.use { curBos ->
                    var len: Int
                    while (zipIn.read(unzipBuffer).also { len = it } > 0) {
                      curBos.write(unzipBuffer, 0, len)
                    }
                  }
                }
                // Create dir.
                else {
                  val dir = File(filePath)
                  dir.mkdirs()
                }

                zipIn.closeEntry()
                zipEntry = zipIn.nextEntry
              }
              zipIn.close()

              // Delete the original file.
              val zipFile = File(zipFilePath)
              zipFile.delete()
            }
          }
          Result.success()
        } catch (e: IOException) {
          Result.failure(
            Data.Builder().putString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE, e.message).build()
          )
        }
      }
    }
  }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    // Initial progress is 0
    return createForegroundInfo(0)
  }

  private data class DownloadPart(val index: Int, val start: Long, val end: Long) {
    val length: Long
      get() = end - start + 1
  }

  private suspend fun downloadModelInParallel(
    fileUrl: String,
    fileName: String,
    modelName: String,
    modelDir: String,
    version: String,
    totalBytes: Long,
    expectedSha256: String,
  ) {
    val outputDir =
      File(
        applicationContext.getExternalFilesDir(null),
        listOf(modelDir, version).joinToString(separator = File.separator),
      )
    if (!outputDir.exists() && !outputDir.mkdirs()) {
      throw IOException("Could not create the model download directory")
    }

    val safetyMargin = 1L * 1024 * 1024 * 1024
    val availableBytes = StatFs(outputDir.absolutePath).availableBytes
    if (availableBytes < totalBytes + safetyMargin) {
      throw IOException(
        "Not enough storage for model download. " +
          "Need ${totalBytes + safetyMargin} bytes, available $availableBytes"
      )
    }

    val outputTmpFile = File(outputDir, "$fileName.$TMP_FILE_EXT")
    // A previous app version may have left a single-stream partial file. It cannot safely be
    // combined with independently addressed ranges.
    if (outputTmpFile.exists()) outputTmpFile.delete()

    val parts = createDownloadParts(totalBytes)
    val partFiles = parts.map { File(outputDir, "$fileName.$TMP_FILE_EXT.part-${it.index}") }
    val partProgress = AtomicLongArray(parts.size)
    parts.forEach { part ->
      val file = partFiles[part.index]
      if (file.length() > part.length) file.delete()
      partProgress.set(part.index, file.length())
    }

    coroutineScope {
      var lastReportAt = System.currentTimeMillis()
      var lastReportedBytes = (0 until parts.size).sumOf { partProgress.get(it) }
      var smoothedRate = 0L
      val reporter =
        launch {
          while (isActive) {
            val now = System.currentTimeMillis()
            val receivedBytes = (0 until parts.size).sumOf { partProgress.get(it) }
            val elapsed = (now - lastReportAt).coerceAtLeast(1L)
            val sampleRate = ((receivedBytes - lastReportedBytes).coerceAtLeast(0L) * 1000L) / elapsed
            smoothedRate =
              if (smoothedRate == 0L) sampleRate
              else (smoothedRate * 7L + sampleRate * 3L) / 10L
            val remainingMs =
              if (smoothedRate > 0L) ((totalBytes - receivedBytes) * 1000L) / smoothedRate else 0L
            reportParallelProgress(
              receivedBytes = receivedBytes,
              totalBytes = totalBytes,
              bytesPerSecond = smoothedRate,
              remainingMs = remainingMs,
              modelName = modelName,
            )
            lastReportAt = now
            lastReportedBytes = receivedBytes
            delay(500)
          }
        }

      try {
        parts
          .map { part ->
            async(Dispatchers.IO) {
              downloadPart(
                fileUrl = fileUrl,
                part = part,
                outputFile = partFiles[part.index],
              ) { bytes -> partProgress.set(part.index, bytes) }
            }
          }
          .awaitAll()
      } finally {
        reporter.cancelAndJoin()
      }
    }

    reportParallelProgress(
      receivedBytes = totalBytes,
      totalBytes = totalBytes,
      bytesPerSecond = 0L,
      remainingMs = 0L,
      modelName = modelName,
    )
    assembleParts(partFiles, outputTmpFile)
    val assembledSize = outputTmpFile.length()
    if (assembledSize != totalBytes) {
      outputTmpFile.delete()
      throw IOException(
        "Downloaded size $assembledSize does not match expected $totalBytes"
      )
    }
    val actualSha256 = sha256(outputTmpFile)
    if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
      outputTmpFile.delete()
      throw IOException("Downloaded model checksum does not match the catalog")
    }

    val outputFile = File(outputDir, fileName)
    if (outputFile.exists()) outputFile.delete()
    if (!outputTmpFile.renameTo(outputFile)) {
      throw IOException("Could not install the completed model file")
    }
    Log.d(TAG, "Parallel model download done")
  }

  private fun createDownloadParts(totalBytes: Long): List<DownloadPart> {
    val baseLength = totalBytes / PARALLEL_DOWNLOAD_PARTS
    val remainder = totalBytes % PARALLEL_DOWNLOAD_PARTS
    var start = 0L
    return (0 until PARALLEL_DOWNLOAD_PARTS).map { index ->
      val length = baseLength + if (index.toLong() < remainder) 1L else 0L
      DownloadPart(index = index, start = start, end = start + length - 1).also {
        start += length
      }
    }
  }

  private suspend fun downloadPart(
    fileUrl: String,
    part: DownloadPart,
    outputFile: File,
    onProgress: (Long) -> Unit,
  ) {
    var existingBytes = outputFile.length()
    if (existingBytes == part.length) return
    if (existingBytes < 0L || existingBytes > part.length) {
      outputFile.delete()
      existingBytes = 0L
    }

    val requestedStart = part.start + existingBytes
    val connection = URL(fileUrl).openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = 30_000
      connection.readTimeout = 60_000
      connection.instanceFollowRedirects = true
      connection.setRequestProperty("Range", "bytes=$requestedStart-${part.end}")
      connection.connect()
      if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
        throw IOException("Server did not honor range ${part.index}: HTTP ${connection.responseCode}")
      }
      val contentRange = connection.getHeaderField("Content-Range").orEmpty()
      if (!contentRange.startsWith("bytes $requestedStart-")) {
        throw IOException(
          "Server returned an unexpected range for part ${part.index}: $contentRange"
        )
      }

      BufferedInputStream(connection.inputStream).use { input ->
        FileOutputStream(outputFile, existingBytes > 0L).use { output ->
          val buffer = ByteArray(256 * 1024)
          var written = existingBytes
          while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (written + count > part.length) {
              throw IOException("Part ${part.index} exceeded its expected size")
            }
            output.write(buffer, 0, count)
            written += count
            onProgress(written)
          }
        }
      }
      if (outputFile.length() != part.length) {
        throw IOException(
          "Part ${part.index} size ${outputFile.length()} does not match ${part.length}"
        )
      }
    } finally {
      connection.disconnect()
    }
  }

  private suspend fun reportParallelProgress(
    receivedBytes: Long,
    totalBytes: Long,
    bytesPerSecond: Long,
    remainingMs: Long,
    modelName: String,
  ) {
    setProgress(
      Data.Builder()
        .putLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, receivedBytes)
        .putLong(KEY_MODEL_DOWNLOAD_RATE, bytesPerSecond)
        .putLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, remainingMs)
        .build()
    )
    setForeground(
      createForegroundInfo(
        progress = ((receivedBytes * 100L) / totalBytes).toInt(),
        modelName = modelName,
      )
    )
  }

  private fun assembleParts(partFiles: List<File>, outputFile: File) {
    if (outputFile.exists()) outputFile.delete()
    FileOutputStream(outputFile).use { output ->
      val buffer = ByteArray(4 * 1024 * 1024)
      for (partFile in partFiles) {
        FileInputStream(partFile).use { input ->
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) output.write(buffer, 0, count)
          }
        }
        if (!partFile.delete()) Log.w(TAG, "Could not remove completed part ${partFile.name}")
      }
      output.fd.sync()
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

  /**
   * Creates a [ForegroundInfo] object for the download worker's ongoing notification. This
   * notification is used to keep the worker running in the foreground, indicating to the user that
   * an active download is in progress.
   */
  private fun createForegroundInfo(progress: Int, modelName: String? = null): ForegroundInfo {
    // Create a notification for the foreground service
    var title = "Downloading model"
    if (modelName != null) {
      title = "Downloading \"$modelName\""
    }
    val content = "Downloading in progress: $progress%"

    val intent =
      Intent(applicationContext, Class.forName("com.google.ai.edge.gallery.MainActivity")).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
    val pendingIntent =
      PendingIntent.getActivity(
        applicationContext,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val notification =
      NotificationCompat.Builder(applicationContext, FOREGROUND_NOTIFICATION_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(content)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true) // Makes the notification non-dismissable
        .setProgress(100, progress, false) // Show progress
        .setContentIntent(pendingIntent)
        .build()

    return ForegroundInfo(
      notificationId,
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }
}
