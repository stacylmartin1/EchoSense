import SwiftUI

@main
struct EchoSenseiOSApp: App {
  @UIApplicationDelegateAdaptor(EchoSenseAppDelegate.self) private var appDelegate
  @StateObject private var settings = AppSettings()
  @StateObject private var modelDownloads = ModelDownloadManager()

  var body: some Scene {
    WindowGroup {
      RootView()
        .environmentObject(settings)
        .environmentObject(modelDownloads)
    }
  }
}
