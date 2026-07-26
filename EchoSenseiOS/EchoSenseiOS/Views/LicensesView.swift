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
