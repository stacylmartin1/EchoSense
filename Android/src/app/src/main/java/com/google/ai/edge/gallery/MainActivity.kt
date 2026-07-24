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

package com.google.ai.edge.gallery

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.os.bundleOf
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  private val modelManagerViewModel: ModelManagerViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    modelManagerViewModel.loadModelAllowlist()
    setContent {
      GalleryTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          EchoSenseApp(modelManagerViewModel = modelManagerViewModel)
        }
      }
    }

    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isNavigationBarContrastEnforced = false
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  override fun onResume() {
    super.onResume()
    firebaseAnalytics?.logEvent(
      FirebaseAnalytics.Event.APP_OPEN,
      bundleOf(
        "app_version" to BuildConfig.VERSION_NAME,
        "os_version" to Build.VERSION.SDK_INT.toString(),
        "device_model" to Build.MODEL,
      ),
    )
  }
}
