import SwiftUI
import UIKit
@_implementationOnly import KotlinspectCore

extension View {
    /// Places Kotlinspect's bubble and toasts over this view; touches outside the bubble pass
    /// through. While it is on screen the automatic bubble hides. With a session, it shows and
    /// opens only that session.
    public func kotlinspectOverlay(session: SessionID? = nil) -> some View {
        overlay(KotlinspectOverlayView(session: session).ignoresSafeArea())
    }
}

private struct KotlinspectOverlayView: UIViewRepresentable {
    let session: SessionID?

    func makeUIView(context: Context) -> UIView {
        KotlinspectUIKit.shared.makeOverlayView(sessionId: session?.rawValue)
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

/// The inspector embedded in SwiftUI, for example on a debug screen. Empty when Kotlinspect is
/// disabled.
public struct KotlinspectInspectorView: UIViewControllerRepresentable {
    let session: SessionID?

    public init(session: SessionID? = nil) {
        self.session = session
    }

    public func makeCoordinator() -> Coordinator { Coordinator() }

    public func makeUIViewController(context: Context) -> UIViewController {
        guard let inspector = KSBridge.shared.makeInspector(sessionId: session?.rawValue, onClose: {}) else {
            return UIViewController()
        }
        context.coordinator.dispose = { inspector.dispose() }
        return inspector.viewController
    }

    public func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    public static func dismantleUIViewController(_ uiViewController: UIViewController, coordinator: Coordinator) {
        coordinator.dispose?()
    }

    public final class Coordinator {
        var dispose: (() -> Void)?
    }
}
