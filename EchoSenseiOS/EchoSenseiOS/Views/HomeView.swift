import SwiftUI

struct HomeView: View {
  var body: some View {
    List(EchoSenseFeature.allCases) { feature in
      NavigationLink(value: feature) {
        VStack(alignment: .leading, spacing: 4) {
          Text(feature.title)
            .font(.headline)
          Text(feature.subtitle)
            .font(.subheadline)
            .foregroundStyle(.secondary)
        }
        .padding(.vertical, 8)
      }
    }
    .navigationDestination(for: EchoSenseFeature.self) { feature in
      FeatureSessionView(viewModel: EchoSenseSessionViewModel(feature: feature))
    }
    .task {
      await MediaPipeObjectDetectionService.shared.prepare()
    }
  }
}
