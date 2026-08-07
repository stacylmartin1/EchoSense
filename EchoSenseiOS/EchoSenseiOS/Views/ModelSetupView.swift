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

import SwiftUI
import UIKit

struct ModelSetupView: View {
  @EnvironmentObject private var modelDownloads: ModelDownloadManager
  @Environment(\.dismiss) private var dismiss
  let allowsDeferral: Bool

  var body: some View {
    NavigationStack {
      VStack(spacing: 24) {
        Image(systemName: "brain.head.profile")
          .font(.system(size: 64))
          .foregroundStyle(.tint)

        VStack(spacing: 8) {
          Text("Download On-Device AI")
            .font(.title.bold())
          Text("Choose a Gemma 4 model for private, on-device visual assistance. No account is required.")
            .multilineTextAlignment(.center)
            .foregroundStyle(.secondary)
        }

        Text("The model file is downloaded from Hugging Face. Hugging Face and its content-delivery providers receive standard network information such as your IP address and download request. After download, images, documents, and prompts analyzed by this model stay on this device and are not sent to Hugging Face or Google.")
          .font(.footnote)
          .foregroundStyle(.secondary)
          .multilineTextAlignment(.center)

        if modelDownloads.availableModels.count > 1 {
          Picker("AI model", selection: Binding(
            get: { modelDownloads.selectedModelID },
            set: { modelDownloads.selectModel(id: $0) }
          )) {
            ForEach(modelDownloads.availableModels) { model in
              Text(model.displayName).tag(model.id)
            }
          }
          .pickerStyle(.segmented)
          .disabled(isDownloadActive)
        }

        if let model = modelDownloads.availableModel {
          Text(model.id.contains("e2b")
            ? "Smaller and faster · \(formattedSize(model.sizeBytes))"
            : "More capable, with higher memory use · \(formattedSize(model.sizeBytes))")
            .font(.callout)
            .foregroundStyle(.secondary)
        }

        if modelDownloads.phase == .downloading || modelDownloads.phase == .paused || modelDownloads.phase == .verifying {
          VStack(spacing: 8) {
            ProgressView(value: modelDownloads.progress)
            Text(modelDownloads.statusText)
              .font(.callout)
              .foregroundStyle(.secondary)
          }
        }

        if let error = modelDownloads.errorMessage {
          Text(error)
            .font(.callout)
            .foregroundStyle(.red)
            .multilineTextAlignment(.center)
        }

        Toggle("Allow cellular data", isOn: $modelDownloads.allowsCellularDownload)

        controls

        if let licenseURL = modelDownloads.availableModel?.licenseURL {
          Link("View model license", destination: licenseURL)
            .font(.footnote)
        }

#if DEBUG
        Button("Copy Download Diagnostics") {
          UIPasteboard.general.string = modelDownloads.downloadDiagnosticReport
        }
        .font(.footnote)
#endif
      }
      .padding(24)
      .navigationTitle("AI Model")
      .task { await modelDownloads.refreshCatalog() }
    }
    .interactiveDismissDisabled(!allowsDeferral && modelDownloads.phase != .installed)
  }

  @ViewBuilder private var controls: some View {
    switch modelDownloads.phase {
    case .downloading:
      Button("Pause Download") { modelDownloads.pauseDownload() }
        .buttonStyle(.borderedProminent)
    case .paused:
      Button("Resume Download") { Task { await modelDownloads.startDownload() } }
        .buttonStyle(.borderedProminent)
    case .verifying:
      Button("Verifying…") {}
        .buttonStyle(.borderedProminent)
        .disabled(true)
    case .installed:
      Button("Continue") { dismiss() }
        .buttonStyle(.borderedProminent)
    default:
      Button(downloadButtonTitle) { Task { await modelDownloads.startDownload() } }
        .buttonStyle(.borderedProminent)
        .disabled(modelDownloads.availableModel == nil)
      if allowsDeferral {
        Button("Not Now") { dismiss() }
          .buttonStyle(.bordered)
      }
    }
  }

  private var isDownloadActive: Bool {
    modelDownloads.phase == .downloading || modelDownloads.phase == .paused || modelDownloads.phase == .verifying
  }

  private var downloadButtonTitle: String {
    guard let model = modelDownloads.availableModel else { return "Download Model" }
    return "Download \(model.displayName) — \(formattedSize(model.sizeBytes))"
  }

  private func formattedSize(_ bytes: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
  }
}
