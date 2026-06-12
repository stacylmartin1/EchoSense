/*
 * Copyright 2025 TerraNet Technologies LLC
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

package com.google.ai.edge.gallery.common

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

private const val TAG = "BundledModelHelper"
private const val BUNDLED_DIR = "bundled"

/**
 * Helper to copy bundled assets to external files directory.
 */
object BundledModelHelper {
  /**
   * Ensures a bundled asset is copied to the external files directory.
   * Returns true if the file exists (was already copied or just copied), false otherwise.
   * This is a blocking operation that ensures the file is fully written before returning.
   */
  fun ensureBundledAssetCopied(
    context: Context,
    assetFileName: String,
    targetFileName: String = assetFileName
  ): Boolean {
    try {
      val externalFilesDir = context.getExternalFilesDir(null) ?: run {
        Log.e(TAG, "External files directory is null")
        return false
      }

      val bundledDir = File(externalFilesDir, BUNDLED_DIR)
      if (!bundledDir.exists()) {
        val created = bundledDir.mkdirs()
        if (!created && !bundledDir.exists()) {
          Log.e(TAG, "Failed to create bundled directory: ${bundledDir.absolutePath}")
          return false
        }
        Log.d(TAG, "Created bundled directory: ${bundledDir.absolutePath}")
      }

      val targetFile = File(bundledDir, targetFileName)
      
      // Check if file already exists and verify it's the correct size
      if (targetFile.exists()) {
        val fileSize = targetFile.length()
        Log.d(TAG, "Bundled asset '$assetFileName' already exists at: ${targetFile.absolutePath}, size: $fileSize bytes")
        
        // Verify the file has reasonable size (not zero or corrupted)
        if (fileSize > 1000000) { // At least 1MB for a valid model file
          Log.d(TAG, "File appears valid, skipping copy")
          return true
        } else {
          Log.w(TAG, "File exists but size is suspicious ($fileSize bytes), re-copying...")
          targetFile.delete()
        }
      }

      Log.d(TAG, "Copying bundled asset '$assetFileName' to: ${targetFile.absolutePath}")
      
      // Copy from assets with proper buffering and flushing
      context.assets.open(assetFileName).use { input ->
        FileOutputStream(targetFile).use { output ->
          val buffer = ByteArray(1024 * 1024) // 1MB buffer for large files
          var bytesRead: Int
          var totalCopied = 0L
          val startTime = System.currentTimeMillis()
          
          while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
            totalCopied += bytesRead
            
            // Log progress every 100MB
            if (totalCopied % (100 * 1024 * 1024) == 0L) {
              Log.d(TAG, "Copied ${totalCopied / (1024 * 1024)}MB so far...")
            }
          }
          
          // Force flush and sync to disk
          output.flush()
          output.fd.sync()
          
          val duration = (System.currentTimeMillis() - startTime) / 1000.0
          Log.d(TAG, "Successfully copied $totalCopied bytes (${totalCopied / (1024 * 1024)}MB) in ${"%.2f".format(duration)}s to ${targetFile.absolutePath}")
        }
      }

      // Verify the file was created and has content
      val success = targetFile.exists() && targetFile.length() > 0
      if (success) {
        Log.d(TAG, "Verification passed: file exists with size ${targetFile.length()} bytes")
      } else {
        Log.e(TAG, "Verification failed: file exists=${targetFile.exists()}, size=${targetFile.length()}")
      }
      
      return success
    } catch (e: Exception) {
      Log.e(TAG, "Error copying bundled asset '$assetFileName'", e)
      return false
    }
  }

  /**
   * Gets the path where a bundled asset should be copied to.
   */
  fun getBundledAssetPath(context: Context, fileName: String): String {
    val externalFilesDir = context.getExternalFilesDir(null)?.absolutePath ?: ""
    return listOf(externalFilesDir, BUNDLED_DIR, fileName).joinToString(File.separator)
  }
  
  /**
   * Check if a bundled asset has been copied and exists.
   */
  fun isBundledAssetAvailable(context: Context, fileName: String): Boolean {
    val targetPath = getBundledAssetPath(context, fileName)
    val file = File(targetPath)
    val exists = file.exists() && file.length() > 1000000 // At least 1MB
    Log.d(TAG, "Checking bundled asset '$fileName': exists=$exists, path=$targetPath")
    return exists
  }
}
