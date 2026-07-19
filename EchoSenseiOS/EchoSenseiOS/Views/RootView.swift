import SwiftUI

struct RootView: View {
  @AppStorage("echosense.acceptedTerms") private var acceptedTerms = false
  @AppStorage("echosense.modelSetupDeferred") private var modelSetupDeferred = false
  @EnvironmentObject private var modelDownloads: ModelDownloadManager
  @State private var showingSettings = false
  @State private var showingModelSetup = false
  @StateObject private var sessionViewModel = EchoSenseSessionViewModel(feature: .navigation)

  var body: some View {
    FeatureSessionView(
      viewModel: sessionViewModel,
      onSettings: { showingSettings = true }
    )
    .sheet(isPresented: $showingSettings) {
      SettingsView(sessionViewModel: sessionViewModel)
    }
    .fullScreenCover(isPresented: $showingModelSetup, onDismiss: {
      if modelDownloads.phase != .installed { modelSetupDeferred = true }
      sessionViewModel.resumeCameraAfterModal()
    }) {
      ModelSetupView(allowsDeferral: true)
        .environmentObject(modelDownloads)
    }
    .fullScreenCover(isPresented: Binding(get: { !acceptedTerms }, set: { _ in })) {
      TermsPrivacyView(acceptedTerms: $acceptedTerms)
    }
    .task {
      await modelDownloads.refreshCatalog()
      connectInstalledModel()
      presentModelSetupIfNeeded()
    }
    .onChange(of: acceptedTerms) { _, _ in presentModelSetupIfNeeded() }
    .onChange(of: modelDownloads.installedModelURL) { _, _ in connectInstalledModel() }
  }

  private func presentModelSetupIfNeeded() {
    guard acceptedTerms, modelDownloads.installedModelURL == nil, !modelSetupDeferred else { return }
    sessionViewModel.pauseCameraForModal()
    showingModelSetup = true
  }

  private func connectInstalledModel() {
    guard let url = modelDownloads.installedModelURL else { return }
    sessionViewModel.useInstalledModel(at: url)
  }
}
