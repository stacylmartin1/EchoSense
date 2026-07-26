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

struct MagnifierView: View {
  @ObservedObject var viewModel: EchoSenseSessionViewModel

  @Binding var zoom: Double
  @Binding var frozenImage: UIImage?
  @Binding var highContrast: Bool
  @Binding var inverted: Bool
  @Binding var grayscale: Bool
  @Binding var torchEnabled: Bool
  let onClose: () -> Void

  var body: some View {
    VStack(spacing: 0) {
      HStack {
        Text("Magnifier")
          .font(.headline)
        Spacer()
        Button("Done", action: onClose)
          .accessibilityHint("Closes Magnifier and returns to Read.")
      }
      .padding(.horizontal, 16)
      .padding(.vertical, 10)
      .background(.regularMaterial)

      Spacer()
      controls
    }
  }

  private var controls: some View {
    VStack(spacing: 10) {
      HStack(spacing: 12) {
        Image(systemName: "minus.magnifyingglass")
          .accessibilityHidden(true)
        Slider(value: $zoom, in: 1...8, step: 0.25)
          .accessibilityLabel("Magnification")
          .accessibilityValue(String(format: "%.2g times", zoom))
        Image(systemName: "plus.magnifyingglass")
          .accessibilityHidden(true)
        Text(String(format: "%.1f×", zoom))
          .monospacedDigit()
          .frame(width: 48)
          .accessibilityHidden(true)
      }
      .padding(.horizontal, 16)

      ScrollView(.horizontal, showsIndicators: false) {
        HStack(spacing: 10) {
          magnifierButton(
            title: frozenImage == nil ? "Freeze" : "Live",
            systemImage: frozenImage == nil ? "pause.fill" : "play.fill",
            selected: frozenImage != nil
          ) {
            if frozenImage == nil {
              frozenImage = viewModel.captureMagnifierFrame()
              announce(frozenImage == nil ? "Camera frame is not ready." : "Image frozen.")
            } else {
              frozenImage = nil
              announce("Live image resumed.")
            }
          }
          magnifierButton(
            title: "Contrast",
            systemImage: "circle.lefthalf.filled",
            selected: highContrast
          ) {
            highContrast.toggle()
            announce("High contrast \(highContrast ? "on" : "off").")
          }
          magnifierButton(
            title: "Invert",
            systemImage: "circle.righthalf.filled",
            selected: inverted
          ) {
            inverted.toggle()
            announce("Color inversion \(inverted ? "on" : "off").")
          }
          magnifierButton(
            title: "Gray",
            systemImage: "circle.grid.2x2.fill",
            selected: grayscale
          ) {
            grayscale.toggle()
            announce("Grayscale \(grayscale ? "on" : "off").")
          }
          magnifierButton(
            title: "Light",
            systemImage: torchEnabled ? "flashlight.on.fill" : "flashlight.off.fill",
            selected: torchEnabled
          ) {
            torchEnabled.toggle()
            viewModel.setMagnifierTorch(enabled: torchEnabled)
            announce("Flashlight \(torchEnabled ? "on" : "off").")
          }
        }
        .padding(.horizontal, 12)
      }
      .scrollBounceBehavior(.basedOnSize)
    }
    .padding(.vertical, 12)
    .background(.regularMaterial)
  }

  private func magnifierButton(
    title: String,
    systemImage: String,
    selected: Bool,
    action: @escaping () -> Void
  ) -> some View {
    Button(action: action) {
      VStack(spacing: 3) {
        Image(systemName: systemImage)
          .font(.title2)
        Text(title)
          .font(.caption)
      }
      .frame(width: 78, height: 58)
      .foregroundStyle(selected ? Color.white : Color.primary)
      .background(selected ? Color.accentColor : Color.primary.opacity(0.08), in: Capsule())
      .overlay {
        Capsule().strokeBorder(
          selected ? Color.accentColor : Color.secondary.opacity(0.4),
          lineWidth: 1
        )
      }
    }
    .buttonStyle(.plain)
    .accessibilityValue(selected ? "On" : "Off")
  }

  private func announce(_ message: String) {
    guard UIAccessibility.isVoiceOverRunning || UIAccessibility.isSwitchControlRunning else {
      return
    }
    UIAccessibility.post(notification: .announcement, argument: message)
  }
}

struct OptionalColorInvert: ViewModifier {
  let enabled: Bool

  func body(content: Content) -> some View {
    // Keep the same view hierarchy while inversion changes. A conditional
    // colorInvert() branch reconstructs UIViewRepresentable camera previews.
    content
      .overlay {
        Color.white
          .opacity(enabled ? 1 : 0)
          .blendMode(.difference)
          .allowsHitTesting(false)
          .accessibilityHidden(true)
      }
      .compositingGroup()
  }
}
