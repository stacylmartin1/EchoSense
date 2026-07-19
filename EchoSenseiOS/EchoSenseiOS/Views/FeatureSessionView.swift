import SwiftUI
import UniformTypeIdentifiers

struct FeatureSessionView: View {
  @EnvironmentObject private var settings: AppSettings
  @ObservedObject var viewModel: EchoSenseSessionViewModel
  var onSettings: () -> Void = {}
  @State private var showingDocumentImporter = false
  @State private var showingCloudSetup = false
  @State private var confirmingOnlineAnalysis = false

  var body: some View {
    ZStack {
      previewLayer
        .ignoresSafeArea()
        .accessibilityHidden(true)

      if viewModel.feature.supportsCollisionAvoidance && viewModel.collisionAvoidanceAvailable {
        detectionOverlay
          .ignoresSafeArea()
      }

      VStack(spacing: 0) {
        if settings.textOverlayEnabled {
          HStack(alignment: .top) {
            statusOverlay
            Spacer()
          }
          .padding(.horizontal, 12)
          .padding(.top, 8)
        }

        Spacer()

        if settings.textOverlayEnabled {
          resultOverlay
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
        }

        featureControlsOverlay
          .padding(.horizontal, 12)
          .padding(.bottom, 8)

        bottomModeBar
      }
    }
    .onAppear { viewModel.onAppear(settings: settings) }
    .onDisappear { viewModel.onDisappear() }
    .onChange(of: settings.selectedDetector) { oldValue, newValue in
      viewModel.reloadDetector(model: newValue)
    }
    .alert("Optional Online Analysis", isPresented: $settings.cloudPromptRequested) {
      Button("Connect") {
        settings.beginCloudSetup()
        showingCloudSetup = true
      }
      Button("Not Now", role: .cancel) { settings.dismissCloudPrompt() }
    } message: {
      Text("Connect your own AI provider for optional online scene analysis. Your key stays on this device.")
    }
    .confirmationDialog(
      "Send this image to \(settings.cloudProvider.displayName)?",
      isPresented: $confirmingOnlineAnalysis,
      titleVisibility: .visible
    ) {
      Button("Send for Online Analysis") {
        settings.cloudConsentGranted = true
        viewModel.analyze(settings: settings, forceOnline: true)
      }
      Button("Cancel", role: .cancel) {}
    } message: {
      Text("The current image and prompt will be sent to the provider and may incur charges on your provider account.")
    }
    .sheet(isPresented: $showingCloudSetup) {
      CloudConnectionView()
        .environmentObject(settings)
    }
    .accessibilityElement(children: .contain)
  }

  private var previewLayer: some View {
    Group {
      if settings.videoPreviewEnabled {
        CameraPreviewView(session: viewModel.camera.session)
      } else {
        Color.black
          .overlay {
            Image(systemName: "video.slash")
              .font(.largeTitle)
              .foregroundStyle(.secondary)
          }
      }
    }
  }

  private var statusOverlay: some View {
    VStack(alignment: .leading, spacing: 6) {
      Text(viewModel.feature.title)
        .font(.caption.bold())
      Text(viewModel.isAnalyzing ? "Analyzing" : viewModel.isModelReady ? "Ready" : "Model Loading")
      if !viewModel.analysisStage.isEmpty {
        Text(viewModel.analysisStage)
          .foregroundStyle(.secondary)
      }
      if !viewModel.voiceCommandText.isEmpty {
        Text("Command: \(viewModel.voiceCommandText)")
      }
      if let activeModelName = viewModel.activeModelName {
        Text(activeModelName)
      }
      if let error = viewModel.errorMessage {
        Text(error)
          .foregroundStyle(.red)
      }
    }
    .font(.caption)
    .padding(8)
    .background(.ultraThinMaterial)
    .clipShape(RoundedRectangle(cornerRadius: 8))
    .accessibilityElement(children: .combine)
    .accessibilityLabel(statusAccessibilityLabel)
  }

  private var resultOverlay: some View {
    VStack(alignment: .leading, spacing: 6) {
      if let alert = viewModel.proximityAlert {
        Label("\(alert.label) \(alert.bearing.rawValue)", systemImage: "exclamationmark.triangle.fill")
          .font(.callout.bold())
          .foregroundStyle(alert.severity == .urgent ? .red : .orange)
      }

      if !viewModel.transcript.isEmpty {
        ScrollView {
          Text(viewModel.transcript)
            .frame(maxWidth: .infinity, alignment: .leading)
            .font(.callout)
            .textSelection(.enabled)
        }
        .frame(maxHeight: 150)
      }
    }
    .padding(10)
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(.ultraThinMaterial)
    .clipShape(RoundedRectangle(cornerRadius: 8))
    .opacity(viewModel.transcript.isEmpty && viewModel.proximityAlert == nil ? 0 : 1)
    .accessibilityElement(children: .combine)
    .accessibilityLabel(resultAccessibilityLabel)
  }

  private var bottomModeBar: some View {
    ScrollView(.horizontal, showsIndicators: false) {
      HStack(spacing: 8) {
        ForEach(EchoSenseFeature.allCases) { feature in
          modeButton(for: feature)
        }

        Divider()
          .frame(height: 56)

        Button(action: onSettings) {
          iconLabel(systemName: "gearshape.fill", title: "Settings")
        }
        .buttonStyle(IconCaptionButtonStyle())
        .accessibilityLabel("Settings")
        .accessibilityHint("Opens EchoSense settings.")
      }
      .padding(.horizontal, 10)
      .padding(.top, 8)
      .padding(.bottom, 10)
    }
    .frame(maxWidth: .infinity)
    .background(.regularMaterial)
    .scrollBounceBehavior(.basedOnSize)
  }

  private func modeButton(for feature: EchoSenseFeature) -> some View {
    Button {
      viewModel.selectFeature(feature)
    } label: {
      iconLabel(
        systemName: feature.systemImageName,
        title: feature.menuTitle,
        accent: viewModel.feature == feature ? .accentColor : nil
      )
    }
    .buttonStyle(IconCaptionButtonStyle())
    .accessibilityLabel(feature.title)
    .accessibilityValue(viewModel.feature == feature ? "Selected" : "Not selected")
    .accessibilityHint("Switches to \(feature.title).")
    .accessibilityAddTraits(viewModel.feature == feature ? [.isSelected] : [])
  }

  private var featureControlsOverlay: some View {
    ScrollView(.horizontal, showsIndicators: false) {
      HStack(spacing: 8) {
        primaryActionButton
        if viewModel.feature == .navigation {
          voiceCommandButton
        }
        if settings.isCloudConnected && viewModel.feature.usesLocalLLM {
          onlineAnalysisButton
        }
        stopButton

        if viewModel.feature.supportsCollisionAvoidance && viewModel.collisionAvoidanceAvailable {
          collisionToggleButton
        }

        if viewModel.feature.supportsDocumentImport {
          uploadButton
        }
      }
      .padding(8)
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(.ultraThinMaterial)
    .clipShape(RoundedRectangle(cornerRadius: 8))
    .scrollBounceBehavior(.basedOnSize)
    .accessibilityElement(children: .contain)
  }

  private var voiceCommandButton: some View {
    Button {
      viewModel.toggleVoiceCommand(settings: settings)
    } label: {
      iconLabel(
        systemName: viewModel.isListeningForVoiceCommand ? "mic.fill" : "mic",
        title: "Voice",
        accent: viewModel.isListeningForVoiceCommand ? .red : nil
      )
    }
    .buttonStyle(IconCaptionButtonStyle())
    .disabled(viewModel.isAnalyzing)
    .accessibilityLabel("Voice command")
    .accessibilityValue(viewModel.isListeningForVoiceCommand ? "Listening" : "Not listening")
    .accessibilityHint(viewModel.isListeningForVoiceCommand ? "Stops listening." : "Listens for a custom navigation request and analyzes the current scene.")
  }

  private var primaryActionButton: some View {
    Button {
      viewModel.analyze(settings: settings)
    } label: {
      iconLabel(
        systemName: "camera.viewfinder",
        title: viewModel.feature.actionTitle,
        accent: .accentColor
      )
    }
    .buttonStyle(IconCaptionButtonStyle())
    .disabled(viewModel.isAnalyzing || viewModel.isListeningForVoiceCommand)
    .accessibilityLabel(viewModel.feature.defaultActionTitle)
    .accessibilityHint("Captures the current camera frame for \(viewModel.feature.title).")
  }

  private var onlineAnalysisButton: some View {
    Button {
      if settings.cloudConsentGranted {
        viewModel.analyze(settings: settings, forceOnline: true)
      } else {
        confirmingOnlineAnalysis = true
      }
    } label: {
      iconLabel(systemName: "cloud.fill", title: "Online")
    }
    .buttonStyle(IconCaptionButtonStyle())
    .disabled(viewModel.isAnalyzing || viewModel.isListeningForVoiceCommand)
    .accessibilityLabel("Online analysis")
    .accessibilityHint("Captures the current image and sends it to \(settings.cloudProvider.displayName) for analysis.")
  }

  private var stopButton: some View {
    Button {
      viewModel.stopCurrentAction()
    } label: {
      iconLabel(systemName: "stop.fill", title: "Stop")
    }
    .buttonStyle(IconCaptionButtonStyle())
    .accessibilityLabel("Stop")
    .accessibilityHint("Stops speech and cancels the current action.")
  }

  private var collisionToggleButton: some View {
    Button {
      viewModel.toggleCollisionAvoidance()
    } label: {
      iconLabel(
        systemName: viewModel.collisionAvoidanceEnabled ? "shield.lefthalf.filled" : "shield.slash",
        title: "Safety",
        accent: viewModel.collisionAvoidanceEnabled ? .green : nil
      )
    }
    .buttonStyle(IconCaptionButtonStyle())
    .accessibilityLabel("Collision avoidance")
    .accessibilityValue(viewModel.collisionAvoidanceEnabled ? "On" : "Off")
    .accessibilityHint(viewModel.collisionAvoidanceEnabled ? "Double tap to turn collision avoidance off." : "Double tap to turn collision avoidance on.")
  }

  private var uploadButton: some View {
    Button {
      showingDocumentImporter = true
      viewModel.pauseCameraForModal()
    } label: {
      iconLabel(systemName: "doc.badge.plus", title: "Upload")
    }
    .buttonStyle(IconCaptionButtonStyle())
    .disabled(viewModel.isAnalyzing)
    .accessibilityLabel("Upload document")
    .accessibilityHint("Opens the file picker for PDF, image, or text documents.")
    .fileImporter(
      isPresented: $showingDocumentImporter,
      allowedContentTypes: [.pdf, .image, .plainText],
      allowsMultipleSelection: false
    ) { result in
      viewModel.resumeCameraAfterModal()
      if case let .success(urls) = result, let url = urls.first {
        viewModel.analyzeDocument(url: url, settings: settings)
      }
    }
  }

  private func iconLabel(systemName: String, title: String, accent: Color? = nil) -> some View {
    VStack(spacing: 2) {
      Image(systemName: systemName)
        .font(.title2)
      Text(title)
        .font(.caption2)
        .lineLimit(1)
        .minimumScaleFactor(0.75)
    }
    .frame(width: 72, height: 64)
    .foregroundStyle(accent == nil ? Color.primary : Color.white)
    .background(accent ?? Color.primary.opacity(0.08), in: Capsule())
    .overlay {
      Capsule()
        .strokeBorder(accent ?? Color.secondary.opacity(0.35), lineWidth: 1)
    }
    .contentShape(Capsule())
  }

  private var detectionOverlay: some View {
    GeometryReader { geometry in
      ForEach(viewModel.proximityBoxes) { box in
        let frameWidth = max(1, CGFloat(box.imageWidth))
        let frameHeight = max(1, CGFloat(box.imageHeight))
        let rect = CGRect(
          x: CGFloat(box.x) / frameWidth * geometry.size.width,
          y: CGFloat(box.y) / frameHeight * geometry.size.height,
          width: CGFloat(box.width) / frameWidth * geometry.size.width,
          height: CGFloat(box.height) / frameHeight * geometry.size.height
        )

        Rectangle()
          .stroke(.yellow, lineWidth: 4)
          .frame(width: max(2, rect.width), height: max(2, rect.height))
          .position(x: rect.midX, y: rect.midY)
          .accessibilityHidden(true)
      }
    }
    .allowsHitTesting(false)
  }

  private var statusAccessibilityLabel: String {
    var parts = [viewModel.feature.title]
    parts.append(viewModel.isAnalyzing ? "Analyzing" : viewModel.isModelReady ? "Ready" : "Model loading")
    if !viewModel.analysisStage.isEmpty {
      parts.append(viewModel.analysisStage)
    }
    if let activeModelName = viewModel.activeModelName {
      parts.append(activeModelName)
    }
    if let error = viewModel.errorMessage {
      parts.append(error)
    }
    return parts.joined(separator: ", ")
  }

  private var resultAccessibilityLabel: String {
    var parts: [String] = []
    if let alert = viewModel.proximityAlert {
      parts.append("\(alert.label) \(alert.bearing.rawValue)")
    }
    if !viewModel.transcript.isEmpty {
      parts.append(viewModel.transcript)
    }
    return parts.isEmpty ? "No result" : parts.joined(separator: ". ")
  }
}

private struct IconCaptionButtonStyle: ButtonStyle {
  @Environment(\.isEnabled) private var isEnabled

  func makeBody(configuration: Configuration) -> some View {
    configuration.label
      .scaleEffect(configuration.isPressed ? 0.96 : 1)
      .opacity(isEnabled ? (configuration.isPressed ? 0.75 : 1) : 0.45)
  }
}
