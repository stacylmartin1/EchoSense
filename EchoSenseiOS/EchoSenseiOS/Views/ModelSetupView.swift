import SwiftUI

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
          Text("EchoSense uses a one-time 3.4 GB download for private, on-device visual assistance. No account is required.")
            .multilineTextAlignment(.center)
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
      Button("Download Model — 3.4 GB") { Task { await modelDownloads.startDownload() } }
        .buttonStyle(.borderedProminent)
        .disabled(modelDownloads.availableModel == nil)
      if allowsDeferral {
        Button("Not Now") { dismiss() }
          .buttonStyle(.bordered)
      }
    }
  }
}
