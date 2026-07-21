import SwiftUI

struct LicensesView: View {
  @Environment(\.dismiss) private var dismiss

  var body: some View {
    NavigationStack {
      List(licenses, id: \.name) { item in
        VStack(alignment: .leading, spacing: 4) {
          Text(item.name).font(.headline)
          Text(item.license).font(.subheadline).foregroundStyle(.blue)
          Text(item.notice).font(.footnote).foregroundStyle(.secondary)
        }
        .padding(.vertical, 6)
      }
      .navigationTitle("Licenses")
      .toolbar {
        ToolbarItem(placement: .topBarTrailing) {
          Button("Done") { dismiss() }
        }
      }
    }
  }

  private let licenses: [(name: String, license: String, notice: String)] = [
    ("EchoSense app code", "Apache License 2.0", "Application code and EchoSense modifications retain source notices."),
    ("Gemma 4 LiteRT-LM model assets", "Apache License 2.0", "The public LiteRT Community E2B and E4B repositories identify these downloadable artifacts as Apache-2.0 licensed. Imported models remain subject to their own terms."),
    ("MediaPipe Tasks iOS SDK", "Apache License 2.0", "Used for local object detection and local AI task integration."),
    ("EfficientDet Lite object detector", "Apache License 2.0", "Used for collision-avoidance object detection."),
    ("Apple Vision, AVFoundation, Speech, and AVSpeechSynthesizer", "Apple platform terms", "Used for OCR, camera, speech input, and speech output.")
  ]
}
