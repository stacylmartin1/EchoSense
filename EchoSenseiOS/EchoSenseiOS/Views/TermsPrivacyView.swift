import SwiftUI

struct TermsPrivacyView: View {
  @Binding var acceptedTerms: Bool
  var viewingMode = false

  var body: some View {
    NavigationStack {
      ScrollView {
        Text(Self.text)
          .frame(maxWidth: .infinity, alignment: .leading)
          .padding()
      }
      .navigationTitle("Terms and Privacy")
      .toolbar {
        ToolbarItem(placement: .bottomBar) {
          Button(viewingMode ? "Close" : "Accept & Continue") {
            acceptedTerms = true
          }
          .buttonStyle(.borderedProminent)
        }
      }
    }
  }

  private static let text = """
  EchoSense provides experimental visual, document, translation, currency, and navigation assistance. The app can make mistakes, miss hazards, hallucinate descriptions, mistranslate text, misread documents, or identify currency incorrectly.

  Do not rely on EchoSense as your only source of safety-critical information. Vision assistance and collision avoidance are not a substitute for a cane, guide dog, sighted assistance, mobility training, traffic signals, medical devices, emergency services, or your own judgment.

  Do not rely on EchoSense for personal safety, traffic decisions, obstacle avoidance, emergency response, legal, medical, financial, immigration, insurance, tax, professional advice, legal document analysis, contracts, official forms, monetary transactions, currency authenticity, account numbers, checks, bills, payment instructions, identity verification, security screening, medication labels, dosage instructions, allergens, hazardous materials, or any decision where a mistake could cause injury, loss, legal exposure, or property damage.

  EchoSense is designed primarily for on-device processing. Camera frames, selected photos, selected documents, microphone input for voice commands, OCR text, translations, and model outputs are processed on your device when local models and local system services are used.

  Some features may use Apple, Google, Android, or configured cloud AI services depending on your settings and installed components. Do not scan highly sensitive documents, credentials, financial information, medical information, private keys, or confidential content unless you understand which local and online services are active and accept the risk.

  EchoSense is provided as-is, without warranties of accuracy, availability, fitness for a particular purpose, or non-infringement.
  """
}

