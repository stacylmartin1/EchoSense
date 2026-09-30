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

plugins {
  alias(libs.plugins.android.application)
  // Note: set apply to true to enable google-services (requires google-services.json).
  alias(libs.plugins.google.services) apply false
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  kotlin("kapt")
}

android {
  namespace = "com.terranet.echosense.android"
  compileSdk = 36
  compileSdkMinor = 1

  defaultConfig {
    applicationId = "com.terranettechnologies.echosense"
    minSdk = 31
    targetSdk = 36
    versionCode = 20
    versionName = "1.1.1"
    ndk {
      abiFilters.add("arm64-v8a")
    }
    manifestPlaceholders["applicationName"] = "com.terranet.echosense.android.EchoSenseApplication"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += listOf("-Xcontext-receivers", "-Xskip-metadata-version-check")
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }

  androidResources {
    // Store .task assets uncompressed to enable fast FileDescriptor access and faster copy
    noCompress += setOf("task", "tflite", "traineddata")
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.browser)
  implementation(libs.litertlm)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.mediapipe.tasks.vision)
  implementation(libs.arcore)
  implementation(libs.tflite)
  implementation(libs.tflite.gpu)
  implementation(libs.tflite.support)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.protobuf.javalite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.analytics)
  implementation(libs.androidx.exifinterface)
  implementation("com.tom-roush:pdfbox-android:2.0.27.0")
  implementation("com.google.mlkit:translate:17.0.3")
  implementation("com.google.mlkit:language-id:17.0.6")
  implementation("com.google.mlkit:text-recognition:16.0.1")
  implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
  implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
  implementation("com.google.mlkit:text-recognition-korean:16.0.1")
  implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
  implementation("com.google.mlkit:barcode-scanning:17.3.0")
  implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
  kapt(libs.hilt.android.compiler)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
}

tasks.register("verifyNoBundledLlm") {
  group = "verification"
  description = "Fails if a LiteRT-LM model is accidentally packaged as an Android asset."
  doLast {
    val bundledModels = fileTree("src/main/assets") { include("**/*.litertlm") }.files
    check(bundledModels.isEmpty()) {
      "LLM files must be downloaded after installation, not bundled: $bundledModels"
    }
  }
}

tasks.named("preBuild").configure { dependsOn("verifyNoBundledLlm") }

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks { all().forEach { it.plugins { create("java") { option("lite") } } } }
}
