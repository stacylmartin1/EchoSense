import AVFoundation
import SwiftUI

struct CameraPreviewView: UIViewRepresentable {
  let session: AVCaptureSession

  func makeUIView(context: Context) -> PreviewView {
    let view = PreviewView()
    view.videoPreviewLayer.session = session
    view.videoPreviewLayer.videoGravity = .resizeAspectFill
    return view
  }

  func updateUIView(_ uiView: PreviewView, context: Context) {
    uiView.videoPreviewLayer.session = session
  }

  static func dismantleUIView(_ uiView: PreviewView, coordinator: ()) {
    // A capture session can drive more than one preview layer, but a layer from a
    // dismissed full-screen cover may retain its connection. Detach it explicitly.
    uiView.videoPreviewLayer.session = nil
  }
}

final class PreviewView: UIView {
  override class var layerClass: AnyClass {
    AVCaptureVideoPreviewLayer.self
  }

  var videoPreviewLayer: AVCaptureVideoPreviewLayer {
    layer as! AVCaptureVideoPreviewLayer
  }
}
