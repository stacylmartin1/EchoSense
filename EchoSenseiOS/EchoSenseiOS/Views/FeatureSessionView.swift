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
import PhotosUI
import UniformTypeIdentifiers

struct FeatureSessionView: View {
  @EnvironmentObject private var settings: AppSettings
  @ObservedObject var viewModel: EchoSenseSessionViewModel
  var onSettings: () -> Void = {}
  @State private var showingDocumentImporter = false
  @State private var showingCloudSetup = false
  @State private var confirmingOnlineAnalysis = false
  @State private var hasPendingPreferredAnalysis = false
  @State private var pendingPreferredAnalysisPrompt: String?
  @State private var showingAssistantImporter = false
  @State private var assistantPhotoItem: PhotosPickerItem?
  @State private var pendingAssistantOnlineSelection = false
  @State private var assistantFollowsLatest = true
  @State private var showingMagnifier = false
  @State private var magnifierZoom = 2.0
  @State private var magnifierFrozenImage: UIImage?
  @State private var magnifierFrozenZoom = 1.0
  @State private var magnifierHighContrast = false
  @State private var magnifierInverted = false
  @State private var magnifierGrayscale = false
  @State private var magnifierTorchEnabled = false
  @State private var showingInstantTextLanguages = false

  var body: some View {
    ZStack {
      if viewModel.feature == .assistant {
        assistantView
      } else {
        previewLayer
          .contrast(showingMagnifier && magnifierHighContrast ? 1.8 : 1)
          .grayscale(showingMagnifier && magnifierGrayscale ? 1 : 0)
          .modifier(OptionalColorInvert(enabled: showingMagnifier && magnifierInverted))
          .ignoresSafeArea()
          .accessibilityHidden(true)

        if showingMagnifier, let magnifierFrozenImage {
          GeometryReader { geometry in
            Image(uiImage: magnifierFrozenImage)
              .resizable()
              .scaledToFill()
              .frame(width: geometry.size.width, height: geometry.size.height)
              .scaleEffect(magnifierZoom / magnifierFrozenZoom)
              .contrast(magnifierHighContrast ? 1.8 : 1)
              .grayscale(magnifierGrayscale ? 1 : 0)
              .modifier(OptionalColorInvert(enabled: magnifierInverted))
              .clipped()
          }
          .ignoresSafeArea()
          .clipped()
          .accessibilityHidden(true)
        }

        if showingMagnifier {
          MagnifierView(
            viewModel: viewModel,
            zoom: $magnifierZoom,
            frozenImage: $magnifierFrozenImage,
            frozenZoom: $magnifierFrozenZoom,
            highContrast: $magnifierHighContrast,
            inverted: $magnifierInverted,
            grayscale: $magnifierGrayscale,
            torchEnabled: $magnifierTorchEnabled,
            onClose: closeMagnifier
          )
        } else {
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
      }
    }
    .onAppear { viewModel.onAppear(settings: settings) }
    .onDisappear {
      if magnifierTorchEnabled {
        viewModel.setMagnifierTorch(enabled: false)
      }
      if showingMagnifier {
        viewModel.setMagnifierMode(enabled: false)
      }
      viewModel.onDisappear()
    }
    .onChange(of: magnifierZoom) { _, newZoom in
      guard showingMagnifier, magnifierFrozenImage == nil else { return }
      viewModel.setMagnifierZoom(newZoom)
    }
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
      Text("Connect your own AI provider for optional online scene analysis. Your key is stored in Keychain and sent only to the provider you explicitly allow for authentication.")
    }
    .alert(
      "Use \(settings.cloudProvider.displayName) Analysis?",
      isPresented: $confirmingOnlineAnalysis
    ) {
      Button("Use Online") {
        runPendingPreferredAnalysis(forceOnline: true)
      }
      Button("Use On-device", role: .cancel) {
        runPendingPreferredAnalysis(forceOnline: false)
      }
    } message: {
      Text(
        pendingPreferredAnalysisPrompt == nil
          ? "The current image, prompt, and relevant object or depth observations will be sent to \(settings.cloudProvider.legalName) for this analysis. Provider charges may apply. Use on-device to keep them on this device."
          : "Your voice request, current image, and relevant object or depth observations will be sent to \(settings.cloudProvider.legalName) for this analysis. Provider charges may apply. Use on-device to keep them on this device."
      )
    }
    .confirmationDialog(
      "Instant Text Language",
      isPresented: $showingInstantTextLanguages,
      titleVisibility: .visible
    ) {
      ForEach(OCRLanguageOption.allCases) { language in
        Button(language.title) {
          viewModel.setInstantTextLanguage(language)
        }
      }
      Button("Cancel", role: .cancel) {}
    } message: {
      Text("Automatic detects the script. Choosing a language can reduce false matches and improve recognition.")
    }
    .sheet(isPresented: $showingCloudSetup, onDismiss: finishPendingAssistantOnlineSelection) {
      CloudConnectionView()
        .environmentObject(settings)
    }
    .fileImporter(
      isPresented: $showingAssistantImporter,
      allowedContentTypes: [.pdf, .image, .plainText, .text],
      allowsMultipleSelection: false
    ) { result in
      if case let .success(urls) = result, let url = urls.first {
        viewModel.attachAssistantFile(url: url)
      }
    }
    .onChange(of: assistantPhotoItem) { _, item in
      guard let item else { return }
      Task {
        if let data = try? await item.loadTransferable(type: Data.self) {
          viewModel.attachAssistantPhoto(data: data)
        }
        assistantPhotoItem = nil
      }
    }
    .onChange(of: viewModel.pendingNavigationVoiceCommand) { _, command in
      guard let command else { return }
      viewModel.clearPendingNavigationVoiceCommand()
      requestPreferredAnalysis(customPrompt: command)
    }
    .accessibilityElement(children: .contain)
  }

  private var assistantView: some View {
    VStack(spacing: 0) {
      HStack {
        VStack(alignment: .leading, spacing: 2) {
          Text("Assistant").font(.title2.bold())
          Text(viewModel.isAnalyzing ? viewModel.analysisStage : (viewModel.activeModelName ?? "Ready"))
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        Spacer()
        Button("New Chat", action: viewModel.newAssistantChat)
          .disabled(viewModel.assistantMessages.isEmpty && viewModel.assistantAttachmentName == nil)
      }
      .padding(.horizontal, 16)
      .padding(.vertical, 10)

      Divider()

      HStack(spacing: 8) {
        assistantModelButton(
          title: "On-device",
          systemImage: "iphone",
          mode: .onDevice
        ) {
          viewModel.setAssistantModelMode(.onDevice)
        }
        assistantModelButton(
          title: settings.isCloudConnected ? settings.cloudProvider.displayName : "Online",
          systemImage: viewModel.isNetworkAvailable ? "cloud.fill" : "wifi.slash",
          mode: .online
        ) {
          selectAssistantOnline()
        }
        .disabled(!viewModel.isNetworkAvailable)
        .accessibilityHint(
          viewModel.isNetworkAvailable
            ? "Uses the configured online AI provider"
            : "Unavailable without an internet connection"
        )
        Spacer()
        Text(viewModel.assistantVoiceInputStatus)
          .font(.caption2)
          .foregroundStyle(.secondary)
          .lineLimit(2)
          .multilineTextAlignment(.trailing)
      }
      .padding(.horizontal, 16)
      .padding(.vertical, 8)

      ScrollViewReader { proxy in
        ZStack(alignment: .bottomTrailing) {
          ScrollView {
            LazyVStack(spacing: 12) {
              if viewModel.assistantMessages.isEmpty {
                ContentUnavailableView(
                  "Ask EchoSense-AI",
                  systemImage: "bubble.left.and.bubble.right",
                  description: Text("Type or speak a question, or attach an image, PDF, or text document.")
                )
                .padding(.top, 50)
              }
              ForEach(viewModel.assistantMessages) { message in
                assistantBubble(message)
                  .id(message.id)
              }
            }
            .padding(16)
          }
          .simultaneousGesture(
            DragGesture(minimumDistance: 4)
              .onChanged { _ in assistantFollowsLatest = false }
          )

          if !assistantFollowsLatest, !viewModel.assistantMessages.isEmpty {
            Button {
              assistantFollowsLatest = true
              scrollAssistantToLatest(proxy)
            } label: {
              Label("Latest", systemImage: "arrow.down")
                .font(.caption.bold())
            }
            .buttonStyle(.borderedProminent)
            .padding(12)
          }
        }
        .onChange(of: viewModel.assistantMessages.count) { oldCount, _ in
          if viewModel.assistantMessages.count > oldCount {
            assistantFollowsLatest = true
          }
          scrollAssistantToLatest(proxy)
        }
        .onChange(of: viewModel.assistantMessages.last?.text.count) { _, _ in
          scrollAssistantToLatest(proxy)
        }
      }

      if let attachmentName = viewModel.assistantAttachmentName {
        HStack(spacing: 10) {
          if let image = viewModel.assistantAttachmentImage {
            Image(uiImage: image)
              .resizable()
              .scaledToFill()
              .frame(width: 48, height: 48)
              .clipShape(RoundedRectangle(cornerRadius: 8))
          } else {
            Image(systemName: "doc.text.fill")
              .font(.title2)
              .frame(width: 48, height: 48)
          }
          Text(attachmentName)
            .lineLimit(2)
            .font(.callout)
          Spacer()
          Button(role: .destructive, action: viewModel.removeAssistantAttachment) {
            Image(systemName: "xmark.circle.fill")
          }
          .accessibilityLabel("Remove attachment")
        }
        .padding(10)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal, 12)
      }

      if let error = viewModel.errorMessage {
        Text(error)
          .font(.caption)
          .foregroundStyle(.red)
          .frame(maxWidth: .infinity, alignment: .leading)
          .padding(.horizontal, 16)
          .padding(.top, 6)
      }

      HStack(spacing: 12) {
        Button(action: viewModel.captureAssistantCameraImage) {
          Image(systemName: "camera.fill")
        }
        .accessibilityLabel("Attach camera image")

        PhotosPicker(selection: $assistantPhotoItem, matching: .images) {
          Image(systemName: "photo.fill")
        }
        .accessibilityLabel("Attach photo")

        Button { showingAssistantImporter = true } label: {
          Image(systemName: "paperclip")
        }
        .accessibilityLabel("Attach document")

        TextField("Message", text: $viewModel.assistantDraft, axis: .vertical)
          .textFieldStyle(.roundedBorder)
          .lineLimit(1...4)
          .submitLabel(.send)
          .onSubmit { viewModel.sendAssistantMessage(settings: settings) }

        Button { viewModel.toggleAssistantVoice(settings: settings) } label: {
          Image(systemName: viewModel.isListeningForAssistant ? "mic.fill" : "mic")
            .foregroundStyle(viewModel.isListeningForAssistant ? .red : .primary)
        }
        .disabled(viewModel.isAnalyzing)
        .accessibilityLabel(viewModel.isListeningForAssistant ? "Finish voice message" : "Speak message")

        Button {
          if viewModel.isAnalyzing {
            viewModel.stopCurrentAction()
          } else {
            viewModel.sendAssistantMessage(settings: settings)
          }
        } label: {
          Image(systemName: viewModel.isAnalyzing ? "stop.fill" : "arrow.up.circle.fill")
            .font(.title2)
        }
        .disabled(!viewModel.isAnalyzing && viewModel.assistantDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        .accessibilityLabel(viewModel.isAnalyzing ? "Stop response" : "Send message")
      }
      .font(.title3)
      .padding(12)
      .background(.regularMaterial)

      bottomModeBar
    }
    .background(Color(uiColor: .systemGroupedBackground))
    .onChange(of: viewModel.isNetworkAvailable) { _, available in
      if !available, viewModel.assistantModelMode == .online {
        viewModel.setAssistantModelMode(.onDevice)
      }
    }
    .onChange(of: settings.isCloudConnected) { _, connected in
      if !connected, viewModel.assistantModelMode == .online {
        viewModel.setAssistantModelMode(.onDevice)
      }
    }
  }

  private func assistantBubble(_ message: AssistantChatMessage) -> some View {
    HStack {
      if message.role == .user { Spacer(minLength: 45) }
      assistantMessageText(message)
        .textSelection(.enabled)
        .padding(12)
        .foregroundStyle(message.role == .user ? Color.white : Color.primary)
        .background(
          message.role == .user ? Color.accentColor : Color(uiColor: .secondarySystemGroupedBackground),
          in: RoundedRectangle(cornerRadius: 16)
        )
        .frame(maxWidth: .infinity, alignment: message.role == .user ? .trailing : .leading)
      if message.role == .assistant { Spacer(minLength: 45) }
    }
    .accessibilityElement(children: .combine)
    .accessibilityLabel("\(message.role == .user ? "You" : "Assistant"): \(message.text)")
  }

  private func assistantMessageText(_ message: AssistantChatMessage) -> Text {
    guard message.role == .assistant, !message.text.isEmpty,
          let attributed = try? AttributedString(
            markdown: message.text,
            options: .init(interpretedSyntax: .full)
          ) else {
      return Text(message.text.isEmpty ? "Thinking…" : message.text)
    }
    return Text(attributed)
  }

  private func assistantModelButton(
    title: String,
    systemImage: String,
    mode: AssistantModelMode,
    action: @escaping () -> Void
  ) -> some View {
    Button(action: action) {
      Label(title, systemImage: systemImage)
        .font(.caption.bold())
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .foregroundStyle(viewModel.assistantModelMode == mode ? Color.white : Color.primary)
        .background(
          viewModel.assistantModelMode == mode ? Color.accentColor : Color(uiColor: .secondarySystemGroupedBackground),
          in: Capsule()
        )
    }
    .buttonStyle(.plain)
    .disabled(viewModel.isAnalyzing)
    .accessibilityAddTraits(viewModel.assistantModelMode == mode ? .isSelected : [])
  }

  private func selectAssistantOnline() {
    guard viewModel.isNetworkAvailable else { return }
    guard settings.isCloudConnected else {
      pendingAssistantOnlineSelection = true
      settings.beginCloudSetup()
      showingCloudSetup = true
      return
    }
    if settings.cloudConsentGranted {
      viewModel.setAssistantModelMode(.online)
    } else {
      pendingAssistantOnlineSelection = true
      showingCloudSetup = true
    }
  }

  private func finishPendingAssistantOnlineSelection() {
    guard pendingAssistantOnlineSelection else { return }
    pendingAssistantOnlineSelection = false
    guard settings.isCloudConnected, viewModel.isNetworkAvailable else { return }
    if settings.cloudConsentGranted {
      viewModel.setAssistantModelMode(.online)
    }
  }

  private func scrollAssistantToLatest(_ proxy: ScrollViewProxy) {
    guard assistantFollowsLatest, let id = viewModel.assistantMessages.last?.id else { return }
    proxy.scrollTo(id, anchor: .bottom)
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
      Text(viewModel.modelStatusText)
      if !viewModel.analysisStage.isEmpty {
        Text(viewModel.analysisStage)
          .foregroundStyle(.secondary)
      }
      if !viewModel.instantTextStatus.isEmpty {
        Text(viewModel.instantTextStatus)
          .foregroundStyle(.secondary)
      }
      if !viewModel.guidedDocumentStatus.isEmpty {
        Text(viewModel.guidedDocumentStatus)
          .foregroundStyle(.secondary)
      }
      if !viewModel.voiceCommandText.isEmpty {
        Text("Command: \(viewModel.voiceCommandText)")
      }
      if let activeModelName = viewModel.activeModelName {
        Text(activeModelName)
      }
      if viewModel.feature == .navigation, !viewModel.analysisSensorContext.isEmpty {
        Text(viewModel.analysisSensorContext)
          .foregroundStyle(.secondary)
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
        Label(proximityText(alert), systemImage: "exclamationmark.triangle.fill")
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
        .accessibilityHint("Opens EchoSense-AI settings.")
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
        if viewModel.feature == .documentReader {
          guidedDocumentButton
          if viewModel.guidedDocumentEnabled {
            guidedDocumentManualCaptureButton
          }
          instantTextButton
          instantTextLanguageButton
          if viewModel.instantTextEnabled {
            instantTextPauseButton
          }
          colorButton
          lightButton
          codeButton
          magnifierButton
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
    .disabled(viewModel.isAnalyzing || viewModel.instantTextEnabled)
    .accessibilityLabel("Voice command")
    .accessibilityValue(viewModel.isListeningForVoiceCommand ? "Listening" : "Not listening")
    .accessibilityHint(viewModel.isListeningForVoiceCommand ? "Stops listening." : "Listens for a custom navigation request and analyzes the current scene.")
  }

  private var primaryActionButton: some View {
    Button {
      requestPreferredAnalysis()
    } label: {
      iconLabel(
        systemName: "camera.viewfinder",
        title: viewModel.feature.actionTitle,
        accent: .accentColor
      )
    }
    .buttonStyle(IconCaptionButtonStyle())
    .disabled(
      viewModel.isAnalyzing || viewModel.isListeningForVoiceCommand ||
        viewModel.instantTextEnabled || viewModel.guidedDocumentEnabled
    )
    .accessibilityLabel(viewModel.feature.defaultActionTitle)
    .accessibilityHint("Captures the current camera frame. When online analysis is available, asks before sending; otherwise uses the on-device model.")
  }

  private var instantTextButton: some View {
    Button {
      viewModel.toggleInstantText()
    } label: {
      iconLabel(
        systemName: viewModel.instantTextEnabled ? "text.viewfinder" : "text.magnifyingglass",
        title: viewModel.instantTextEnabled ? "Instant On" : "Instant",
        accent: viewModel.instantTextEnabled ? .green : nil
      )
    }
    .buttonStyle(IconCaptionButtonStyle())
    .disabled(viewModel.isAnalyzing || viewModel.guidedDocumentEnabled)
    .accessibilityLabel(viewModel.instantTextEnabled ? "Turn off Instant Text" : "Turn on Instant Text")
    .accessibilityValue(viewModel.instantTextEnabled ? "On" : "Off")
    .accessibilityHint("Continuously recognizes stable text in the camera view and reads new text aloud.")
  }

  private var guidedDocumentButton: some View {
    Button {
      viewModel.toggleGuidedDocumentCapture()
    } label: {
      iconLabel(
        systemName: "doc.viewfinder",
        title: viewModel.guidedDocumentEnabled ? "Scan On" : "Scan",
        accent: viewModel.guidedDocumentEnabled ? .green : nil
      )
    }
    .buttonStyle(IconCaptionButtonStyle())
    .disabled(viewModel.isAnalyzing || viewModel.instantTextEnabled)
    .accessibilityLabel(
      viewModel.guidedDocumentEnabled ? "Turn off Guided Scan" : "Turn on Guided Scan"
    )
    .accessibilityValue(viewModel.guidedDocumentEnabled ? "On" : "Off")
    .accessibilityHint(
      "Guides the camera around one complete page and captures automatically when the page is stable."
    )
  }

  private var guidedDocumentManualCaptureButton: some View {
    Button {
      viewModel.captureGuidedDocumentManually()
    } label: {
      iconLabel(systemName: "camera.fill", title: "Capture")
    }
    .buttonStyle(IconCaptionButtonStyle())
    .disabled(viewModel.isAnalyzing)
    .accessibilityLabel("Capture document now")
    .accessibilityHint(
      "Captures immediately when automatic page-edge detection cannot recognize the document."
    )
  }

  private var instantTextPauseButton: some View {
    Button {
      viewModel.toggleInstantTextPause()
    } label: {
      iconLabel(
        systemName: viewModel.instantTextPaused ? "play.fill" : "pause.fill",
        title: viewModel.instantTextPaused ? "Resume" : "Pause"
      )
    }
    .buttonStyle(IconCaptionButtonStyle())
    .accessibilityLabel(viewModel.instantTextPaused ? "Resume Instant Text" : "Pause Instant Text")
    .accessibilityHint(
      viewModel.instantTextPaused
        ? "Resumes continuous text recognition."
        : "Keeps the current recognized text on screen and pauses recognition."
    )
  }

  private var instantTextLanguageButton: some View {
    Button {
      showingInstantTextLanguages = true
    } label: {
      iconLabel(systemName: "character.book.closed.fill", title: "Language")
    }
    .buttonStyle(IconCaptionButtonStyle())
    .accessibilityLabel("Text recognition language")
    .accessibilityValue(viewModel.instantTextLanguage.title)
    .accessibilityHint("Choose automatic detection or a preferred recognition language.")
  }

  private func requestPreferredAnalysis(customPrompt: String? = nil) {
    if settings.isCloudConnected && viewModel.isNetworkAvailable {
      pendingPreferredAnalysisPrompt = customPrompt
      hasPendingPreferredAnalysis = true
      confirmingOnlineAnalysis = true
    } else {
      viewModel.analyze(settings: settings, customPrompt: customPrompt, forceLocal: true)
    }
  }

  private func runPendingPreferredAnalysis(forceOnline: Bool) {
    guard hasPendingPreferredAnalysis else { return }
    let customPrompt = pendingPreferredAnalysisPrompt
    hasPendingPreferredAnalysis = false
    pendingPreferredAnalysisPrompt = nil
    if forceOnline {
      viewModel.analyze(settings: settings, customPrompt: customPrompt, forceOnline: true)
    } else {
      viewModel.analyze(settings: settings, customPrompt: customPrompt, forceLocal: true)
    }
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

  private var colorButton: some View {
    Button {
      viewModel.identifyCenterColor()
    } label: {
      iconLabel(systemName: "paintpalette.fill", title: "Color")
    }
    .buttonStyle(IconCaptionButtonStyle())
    .disabled(viewModel.isAnalyzing || viewModel.instantTextEnabled || viewModel.guidedDocumentEnabled)
    .accessibilityLabel("Identify center color")
    .accessibilityHint("Speaks the approximate color at the center of the camera view. Lighting can affect the result.")
  }

  private var lightButton: some View {
    Button {
      viewModel.measureLightLevel()
    } label: {
      iconLabel(systemName: "sun.max.fill", title: "Light")
    }
    .buttonStyle(IconCaptionButtonStyle())
    .disabled(viewModel.isAnalyzing || viewModel.instantTextEnabled || viewModel.guidedDocumentEnabled)
    .accessibilityLabel("Measure light level")
    .accessibilityHint("Speaks the approximate brightness seen by the camera. This is not a calibrated lux measurement.")
  }

  private var codeButton: some View {
    Button {
      viewModel.scanBarcodeOrQRCode()
    } label: {
      iconLabel(systemName: "barcode.viewfinder", title: "Codes")
    }
    .buttonStyle(IconCaptionButtonStyle())
    .disabled(viewModel.isAnalyzing || viewModel.instantTextEnabled || viewModel.guidedDocumentEnabled)
    .accessibilityLabel("Scan barcode or QR code")
    .accessibilityHint("Scans the current camera view. Links are reported but never opened automatically.")
  }

  private var magnifierButton: some View {
    Button {
      magnifierFrozenImage = nil
      magnifierZoom = 2
      magnifierFrozenZoom = 1
      magnifierHighContrast = false
      magnifierInverted = false
      magnifierGrayscale = false
      magnifierTorchEnabled = false
      showingMagnifier = true
      viewModel.setMagnifierMode(enabled: true)
      viewModel.setMagnifierZoom(magnifierZoom)
    } label: {
      iconLabel(systemName: "plus.magnifyingglass", title: "Magnify")
    }
    .buttonStyle(IconCaptionButtonStyle())
    .disabled(viewModel.isAnalyzing || viewModel.instantTextEnabled || viewModel.guidedDocumentEnabled)
    .accessibilityLabel("Open Magnifier")
    .accessibilityHint("Opens a full-screen magnifier with zoom, freeze, contrast, inversion, grayscale, and flashlight controls.")
  }

  private func closeMagnifier() {
    magnifierTorchEnabled = false
    viewModel.setMagnifierTorch(enabled: false)
    viewModel.setMagnifierMode(enabled: false)
    magnifierFrozenImage = nil
    showingMagnifier = false
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
    .disabled(viewModel.isAnalyzing || viewModel.instantTextEnabled || viewModel.guidedDocumentEnabled)
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
    parts.append(viewModel.modelStatusText)
    if !viewModel.analysisStage.isEmpty {
      parts.append(viewModel.analysisStage)
    }
    if let activeModelName = viewModel.activeModelName {
      parts.append(activeModelName)
    }
    if viewModel.feature == .navigation, !viewModel.analysisSensorContext.isEmpty {
      parts.append(viewModel.analysisSensorContext)
    }
    if let error = viewModel.errorMessage {
      parts.append(error)
    }
    return parts.joined(separator: ", ")
  }

  private func proximityText(_ alert: ProximityAlert) -> String {
    if let distance = alert.distanceMeters {
      return "\(alert.label) \(String(format: "%.1f m", distance)) \(alert.bearing.rawValue) · \(viewModel.depthSensingDescription)"
    }
    return "\(alert.label) \(alert.bearing.rawValue)"
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
