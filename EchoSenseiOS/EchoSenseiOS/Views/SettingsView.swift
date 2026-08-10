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

import AVFoundation
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct SettingsView: View {
  @Environment(\.dismiss) private var dismiss
  @EnvironmentObject private var settings: AppSettings
  @EnvironmentObject private var modelDownloads: ModelDownloadManager
  @ObservedObject var sessionViewModel: EchoSenseSessionViewModel
  @State private var showingTerms = false
  @State private var showingLicenses = false
  @State private var showingModelSetup = false
  @State private var showingLocalModelImporter = false
  @State private var showingCloudSetup = false
  @State private var showingDeleteModelConfirmation = false
  @State private var voices: [AVSpeechSynthesisVoice] = []

  var body: some View {
    NavigationStack {
      Form {
        Section("Output") {
          Picker("Response detail", selection: $settings.responseStyle) {
            ForEach(ResponseStyle.allCases) { style in
              Text(style.rawValue.capitalized).tag(style)
            }
          }
          Toggle("Video preview", isOn: $settings.videoPreviewEnabled)
          Toggle("Text overlay", isOn: $settings.textOverlayEnabled)
        }

        Section("Object Detection") {
          Picker("Model", selection: $settings.selectedDetector) {
            ForEach(ObjectDetectorModel.allCases) { model in
              Text(model.rawValue).tag(model)
            }
          }
        }

        Section("Voice") {
          Picker("TTS voice", selection: $settings.selectedVoiceIdentifier) {
            Text("Default Voice").tag("")
            ForEach(voices, id: \.identifier) { voice in
              Text("\(voice.name) (\(voice.language))").tag(voice.identifier)
            }
          }
          VStack(alignment: .leading, spacing: 6) {
            HStack {
              Text("Safety speech speed")
              Spacer()
              Text(String(format: "%.2f×", settings.safetySpeechRate))
                .foregroundStyle(.secondary)
                .monospacedDigit()
            }
            Slider(value: $settings.safetySpeechRate, in: 0.8...1.4, step: 0.05)
              .accessibilityLabel("Safety speech speed")
              .accessibilityValue(String(format: "%.2f times", settings.safetySpeechRate))
            Text("Changes obstacle announcements only.")
              .font(.footnote)
              .foregroundStyle(.secondary)
          }
        }

        Section("On-Device AI Model") {
          if modelDownloads.availableModels.count > 1 {
            Picker("Download model", selection: Binding(
              get: { modelDownloads.selectedModelID },
              set: { modelDownloads.selectModel(id: $0) }
            )) {
              ForEach(modelDownloads.availableModels) { model in
                Text(model.displayName).tag(model.id)
              }
            }
            .disabled(modelDownloads.phase == .downloading || modelDownloads.phase == .paused || modelDownloads.phase == .verifying)
          }
          LabeledContent("Status", value: modelDownloads.statusText)
          if let name = modelDownloads.installedModelName {
            LabeledContent("Installed", value: name)
          }
          Toggle("Allow cellular downloads", isOn: $modelDownloads.allowsCellularDownload)
          Button(modelDownloads.installedModelURL == nil ? "Download Model" : "Manage Model") {
            showingModelSetup = true
          }
          Button("Import Model from Files") {
            sessionViewModel.pauseCameraForModal()
            showingLocalModelImporter = true
          }
          .disabled(sessionViewModel.isAnalyzing)
          .accessibilityHint("Opens the file picker for a compatible MediaPipe or LiteRT-LM model file.")
          if modelDownloads.installedModelURL != nil {
            Button("Delete Downloaded Model", role: .destructive) {
              showingDeleteModelConfirmation = true
            }
          }
          if let licenseURL = modelDownloads.availableModel?.licenseURL {
            Link("View model license", destination: licenseURL)
          }
        }

        Section("Online Analysis") {
          LabeledContent("Status", value: settings.isCloudConnected ? "Connected" : "Not connected")
          if settings.isCloudConnected {
            LabeledContent("Provider", value: settings.cloudProvider.legalName)
            LabeledContent("Key", value: settings.maskedCloudKey)
            LabeledContent("Usage", value: settings.cloudUsageMode.displayName)
            LabeledContent("Consent", value: settings.cloudConsentGranted ? "Granted" : "Not granted")
          }
          Button(settings.isCloudConnected ? "Manage Online Analysis" : "Connect AI Provider") {
            showingCloudSetup = true
          }
          Text("Online analysis is optional. Images and prompts are sent directly to your selected provider and may incur provider charges.")
            .font(.footnote)
            .foregroundStyle(.secondary)
        }

        Section("About") {
          Button("View terms and privacy") { showingTerms = true }
          Button("View licenses") { showingLicenses = true }
        }
      }
      .navigationTitle("Settings")
      .toolbar {
        ToolbarItem(placement: .topBarTrailing) {
          Button("Done") { dismiss() }
        }
      }
      .alert("Delete Downloaded Model?", isPresented: $showingDeleteModelConfirmation) {
        Button("Cancel", role: .cancel) {}
        Button("Delete Model", role: .destructive) {
          modelDownloads.deleteInstalledModel()
        }
      } message: {
        Text("Delete \(modelDownloads.installedModelName ?? "the downloaded model")? You will need to download the model again before on-device analysis can use it.")
      }
      .sheet(isPresented: $showingTerms) {
        TermsPrivacyView(acceptedTerms: .constant(true), viewingMode: true)
      }
      .sheet(isPresented: $showingLicenses) {
        LicensesView()
      }
      .sheet(isPresented: $showingModelSetup) {
        ModelSetupView(allowsDeferral: true)
          .environmentObject(modelDownloads)
      }
      .sheet(isPresented: $showingCloudSetup) {
        CloudConnectionView()
          .environmentObject(settings)
      }
      .fileImporter(
        isPresented: $showingLocalModelImporter,
        allowedContentTypes: [.data, .folder, .item, .content],
        allowsMultipleSelection: false
      ) { result in
        sessionViewModel.resumeCameraAfterModal()
        if case let .success(urls) = result, let url = urls.first {
          sessionViewModel.importLocalModel(url: url)
        }
      }
      .task {
        await modelDownloads.refreshCatalog()
        guard voices.isEmpty else { return }
        voices = AVSpeechSynthesisVoice.speechVoices().sorted {
          ($0.language, $0.name) < ($1.language, $1.name)
        }
      }
    }
  }
}

struct CloudConnectionView: View {
  @EnvironmentObject private var settings: AppSettings
  @Environment(\.dismiss) private var dismiss
  @State private var provider: CloudProvider = .gemini
  @State private var usageMode: CloudUsageMode = .askBeforeUse
  @State private var apiKey = ""
  @State private var isTesting = false
  @State private var errorMessage: String?
  @State private var hasAcceptedProviderDisclosure = false

  var body: some View {
    NavigationStack {
      Form {
        if !hasConsentForSelectedProvider {
          Section("Choose Provider") {
            Picker("AI provider", selection: $provider) {
              ForEach(CloudProvider.allCases.filter { $0 != .none }) { provider in
                Text(provider.displayName).tag(provider)
              }
            }
          }

          Section("Before You Connect") {
            Text("EchoSense-AI will open \(provider.legalName)'s website if you choose to get an API key. The provider and its website or content-delivery services receive standard network information such as your IP address, device or browser information, request time, and pages requested. Any account details or other information you enter on that website are provided directly to \(provider.legalName) and handled under its privacy policy.")
              .font(.footnote)
              .fixedSize(horizontal: false, vertical: true)

            Text("After you connect, EchoSense-AI may send \(provider.legalName) camera images, selected photos, document text, typed or spoken prompts, recent conversation context, relevant object or depth observations, and your API key. This data is used to authenticate your account and generate online analysis or assistant responses you request.")
              .font(.footnote)
              .fixedSize(horizontal: false, vertical: true)

            Text("On-device analysis remains available if you do not consent. Provider usage may incur charges on your account.")
              .font(.footnote)
              .foregroundStyle(.secondary)
              .fixedSize(horizontal: false, vertical: true)

            if let providerPrivacyURL = provider.privacyPolicyURL {
              Link("View \(provider.legalName) Privacy Policy", destination: providerPrivacyURL)
            }
            Link("View EchoSense-AI Privacy Policy", destination: LegalLinks.appPrivacyPolicyURL)
          }

          Section {
            Button("Accept and Continue") {
              hasAcceptedProviderDisclosure = true
            }
            .buttonStyle(.borderedProminent)

            Button("Not Now", role: .cancel) { dismiss() }
          }
        } else {
        Section("Provider") {
          Picker("AI provider", selection: $provider) {
            ForEach(CloudProvider.allCases.filter { $0 != .none }) { provider in
              Text(provider.displayName).tag(provider)
            }
          }
          if let url = provider.keyCreationURL {
            Link("Get a \(provider.displayName) API key", destination: url)
          }
        }

        Section("API Key") {
          if settings.isCloudConnected, provider == settings.cloudProvider {
            LabeledContent("Connected key", value: settings.maskedCloudKey)
          }
          SecureField(settings.isCloudConnected ? "Paste a replacement key" : "Paste API key", text: $apiKey)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
          Button("Paste") {
            apiKey = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
          }
          .disabled(UIPasteboard.general.hasStrings == false)
        }

        Section("When to use it") {
          Picker("Online analysis", selection: $usageMode) {
            ForEach(CloudUsageMode.allCases) { mode in
              Text(mode.displayName).tag(mode)
            }
          }
          Text(usageMode.explanation)
            .font(.footnote)
            .foregroundStyle(.secondary)
        }

        Section("Privacy and cost") {
          Text("If you allow online AI, EchoSense-AI sends data directly to \(provider.legalName) to generate the analysis or assistant response you request.")
            .font(.footnote)
          Text("Depending on the feature, the data sent may include camera images, selected photos, document text, typed or spoken prompts, recent conversation context, and relevant object or depth observations. Your API key is sent to \(provider.legalName) for authentication and is stored on this device in Keychain.")
            .font(.footnote)
            .foregroundStyle(.secondary)
          Text("On-device analysis remains available if you do not consent. Provider usage may incur charges on your account.")
            .font(.footnote)
            .foregroundStyle(.secondary)
          if let providerPrivacyURL = provider.privacyPolicyURL {
            Link("View \(provider.legalName) Privacy Policy", destination: providerPrivacyURL)
          }
          Link("View EchoSense-AI Privacy Policy", destination: LegalLinks.appPrivacyPolicyURL)
        }

        if let errorMessage {
          Section { Text(errorMessage).foregroundStyle(.red) }
        }

        Section {
          Button {
            saveConnection()
          } label: {
            if isTesting {
              HStack { ProgressView(); Text("Testing connection…") }
            } else {
              Text(needsKeyValidation ? "Test and Connect" : "Save Preference")
            }
          }
          .disabled(
            isTesting ||
            (needsKeyValidation && apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
          )

          if settings.isCloudConnected, settings.cloudConsentGranted {
            Button("Withdraw Online AI Consent") {
              settings.withdrawCloudConsent()
              hasAcceptedProviderDisclosure = false
              usageMode = .askBeforeUse
            }
          }

          if settings.isCloudConnected {
            Button("Remove Connection", role: .destructive) {
              settings.removeCloudConnection()
              dismiss()
            }
          }
        }
        }
      }
      .navigationTitle("Online Analysis")
      .toolbar {
        ToolbarItem(placement: .topBarTrailing) { Button("Cancel") { dismiss() } }
      }
      .onAppear {
        if settings.isCloudConnected {
          provider = settings.cloudProvider
          usageMode = settings.cloudUsageMode
        }
      }
      .onChange(of: provider) { _, _ in
        errorMessage = nil
        hasAcceptedProviderDisclosure = false
      }
      .onChange(of: apiKey) { _, _ in errorMessage = nil }
    }
  }

  private var needsKeyValidation: Bool {
    !settings.isCloudConnected || provider != settings.cloudProvider || !apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
  }

  private var hasConsentForSelectedProvider: Bool {
    hasAcceptedProviderDisclosure ||
      (settings.cloudConsentGranted && provider == settings.cloudProvider)
  }

  private func saveConnection() {
    isTesting = true
    errorMessage = nil
    Task {
      do {
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? settings.cloudAPIKey : apiKey
        if needsKeyValidation {
          try await CloudVisionClient(provider: provider, apiKey: key).validateKey()
        }
        try settings.saveCloudConnection(
          provider: provider,
          apiKey: key,
          usageMode: usageMode,
          consentGranted: hasConsentForSelectedProvider
        )
        dismiss()
      } catch {
        errorMessage = error.localizedDescription
        isTesting = false
      }
    }
  }
}
