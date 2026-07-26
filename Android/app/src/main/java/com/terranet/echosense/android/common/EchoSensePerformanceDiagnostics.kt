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

package com.terranet.echosense.android.common

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Low-volume timing diagnostics for LiteRT-LM initialization and inference.
 *
 * All messages use the `EchoSensePerf` tag so a complete comparison can be captured with:
 *
 * `adb logcat -s EchoSensePerf`
 */
object EchoSensePerformanceDiagnostics {
  private const val TAG = "EchoSensePerf"
  private const val PROGRESS_INTERVAL_MS = 10_000L
  private val nextTraceId = AtomicLong(1L)

  @Volatile private var applicationContext: Context? = null

  fun beginInitialization(
    context: Context,
    modelName: String,
    modelBytes: Long,
    backend: String,
    visionBackend: String,
    maxTokens: Int,
    imported: Boolean,
  ): InitializationTrace {
    install(context)
    val traceId = nextTraceId.getAndIncrement()
    Log.i(
      TAG,
      "init[$traceId] begin model=$modelName file=${formatBytes(modelBytes)} " +
        "backend=$backend vision=$visionBackend maxTokens=$maxTokens imported=$imported " +
        systemSnapshot(context),
    )
    return InitializationTrace(traceId, modelName, SystemClock.elapsedRealtime())
  }

  fun beginInference(
    modelName: String,
    promptChars: Int,
    imageDimensions: List<Pair<Int, Int>>,
    audioBytes: Long,
  ): InferenceTrace {
    val traceId = nextTraceId.getAndIncrement()
    val dimensions =
      imageDimensions.joinToString(prefix = "[", postfix = "]") { (width, height) ->
        "${width}x$height"
      }
    Log.i(
      TAG,
      "inference[$traceId] begin model=$modelName promptChars=$promptChars " +
        "images=${imageDimensions.size}$dimensions audio=${formatBytes(audioBytes)} " +
        systemSnapshot(),
    )
    return InferenceTrace(traceId, modelName, SystemClock.elapsedRealtime())
  }

  private fun install(context: Context) {
    applicationContext = context.applicationContext
  }

  private fun systemSnapshot(context: Context? = applicationContext): String {
    if (context == null) return "system=unavailable"

    val powerManager = context.getSystemService(PowerManager::class.java)
    val thermal =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        thermalLabel(powerManager?.currentThermalStatus)
      } else {
        "unsupported"
      }
    val powerSave = powerManager?.isPowerSaveMode ?: false

    val memoryInfo = ActivityManager.MemoryInfo()
    context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memoryInfo)
    val runtime = Runtime.getRuntime()
    val javaHeapBytes = runtime.totalMemory() - runtime.freeMemory()
    val nativeHeapBytes = Debug.getNativeHeapAllocatedSize()
    val pssBytes = Debug.getPss().coerceAtLeast(0).toLong() * 1024L

    return "thermal=$thermal powerSave=$powerSave lowMemory=${memoryInfo.lowMemory} " +
      "availRam=${formatBytes(memoryInfo.availMem)} pss=${formatBytes(pssBytes)} " +
      "nativeHeap=${formatBytes(nativeHeapBytes)} javaHeap=${formatBytes(javaHeapBytes)} " +
      "device=${Build.MANUFACTURER}/${Build.MODEL}"
  }

  private fun thermalLabel(status: Int?): String =
    when (status) {
      PowerManager.THERMAL_STATUS_NONE -> "none"
      PowerManager.THERMAL_STATUS_LIGHT -> "light"
      PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
      PowerManager.THERMAL_STATUS_SEVERE -> "severe"
      PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
      PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
      PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
      null -> "unavailable"
      else -> "unknown($status)"
    }

  private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "${bytes}B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    do {
      value /= 1024.0
      unitIndex++
    } while (value >= 1024.0 && unitIndex < units.lastIndex)
    return String.format(Locale.US, "%.1f%s", value, units[unitIndex])
  }

  private fun seconds(milliseconds: Long): String =
    String.format(Locale.US, "%.2fs", milliseconds.coerceAtLeast(0L) / 1000.0)

  class InitializationTrace internal constructor(
    private val traceId: Long,
    private val modelName: String,
    private val startedAtMs: Long,
  ) {
    private val finished = AtomicBoolean(false)

    fun finish(success: Boolean, error: String? = null) {
      if (!finished.compareAndSet(false, true)) return
      val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
      val errorSuffix = error?.takeIf { it.isNotBlank() }?.let { " error=${it.take(240)}" }.orEmpty()
      Log.i(
        TAG,
        "init[$traceId] end model=$modelName success=$success elapsed=${seconds(elapsedMs)} " +
          systemSnapshot() + errorSuffix,
      )
    }
  }

  class InferenceTrace internal constructor(
    private val traceId: Long,
    private val modelName: String,
    private val startedAtMs: Long,
  ) {
    private val finished = AtomicBoolean(false)
    private val firstOutput = AtomicBoolean(false)
    private val callbackCount = AtomicInteger(0)
    private val outputChars = AtomicLong(0L)
    private val lastProgressAtMs = AtomicLong(startedAtMs)

    fun inputsPrepared(encodedImageBytes: Long, elapsedMs: Long) {
      Log.i(
        TAG,
        "inference[$traceId] inputsReady model=$modelName encodedImages=" +
          "${formatBytes(encodedImageBytes)} elapsed=${seconds(elapsedMs)}",
      )
    }

    fun onOutput(text: String) {
      if (text.isEmpty() || finished.get()) return
      val now = SystemClock.elapsedRealtime()
      val callbacks = callbackCount.incrementAndGet()
      val chars = outputChars.addAndGet(text.length.toLong())
      if (firstOutput.compareAndSet(false, true)) {
        Log.i(
          TAG,
          "inference[$traceId] firstOutput model=$modelName elapsed=" +
            "${seconds(now - startedAtMs)} chunkChars=${text.length} ${systemSnapshot()}",
        )
      }
      val previousProgress = lastProgressAtMs.get()
      if (
        now - previousProgress >= PROGRESS_INTERVAL_MS &&
          lastProgressAtMs.compareAndSet(previousProgress, now)
      ) {
        val elapsedSeconds = (now - startedAtMs).coerceAtLeast(1L) / 1000.0
        Log.i(
          TAG,
          "inference[$traceId] progress model=$modelName elapsed=${seconds(now - startedAtMs)} " +
            "callbacks=$callbacks chars=$chars charsPerSec=" +
            String.format(Locale.US, "%.1f", chars / elapsedSeconds) +
            " ${systemSnapshot()}",
        )
      }
    }

    fun finish(outcome: String, error: String? = null) {
      if (!finished.compareAndSet(false, true)) return
      val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
      val callbacks = callbackCount.get()
      val chars = outputChars.get()
      val generationSeconds = elapsedMs.coerceAtLeast(1L) / 1000.0
      val errorSuffix = error?.takeIf { it.isNotBlank() }?.let { " error=${it.take(240)}" }.orEmpty()
      Log.i(
        TAG,
        "inference[$traceId] end model=$modelName outcome=$outcome elapsed=${seconds(elapsedMs)} " +
          "callbacks=$callbacks chars=$chars avgCharsPerSec=" +
          String.format(Locale.US, "%.1f", chars / generationSeconds) +
          " ${systemSnapshot()}" + errorSuffix,
      )
    }
  }
}
