import SwiftUI
import UIKit

/// Kotlinspect's bubble and toasts for SwiftUI.
///
/// Put it over the root of your UI; touches outside the bubble reach your views:
///
///     ContentView()
///         .overlay(KotlinspectOverlay())
///
/// While it is on screen, the automatic bubble hides. Pass a `sessionId` to show and open only
/// that session. It shows nothing when Kotlinspect is disabled (for example in release builds)
/// or when the app's framework does not export kotlinspect-core.
public struct KotlinspectOverlay: UIViewRepresentable {
    private let sessionId: String?

    public init(sessionId: String? = nil) {
        self.sessionId = sessionId
    }

    public func makeUIView(context: Context) -> UIView {
        KotlinspectBridge.makeOverlayView(sessionId: sessionId)
    }

    public func updateUIView(_ uiView: UIView, context: Context) {}
}

/// Opens Kotlinspect's inspector from SwiftUI, for example from a debug menu.
public enum KotlinspectInspector {
    public static func open(sessionId: String? = nil) {
        KotlinspectBridge.openInspector(sessionId: sessionId)
    }
}

/// Reaches the Kotlin side through the Objective-C runtime, so this package works whatever the
/// app's KMP framework is called. KotlinspectUIKit keeps a fixed Objective-C name for this.
enum KotlinspectBridge {
    private static var shared: AnyObject? {
        guard let cls: AnyObject = NSClassFromString("KotlinspectUIKit") else { return nil }
        let selector = NSSelectorFromString("shared")
        guard cls.responds(to: selector) else { return nil }
        return cls.perform(selector)?.takeUnretainedValue()
    }

    static func makeOverlayView(sessionId: String?) -> UIView {
        let selector = NSSelectorFromString("makeOverlayViewSessionId:")
        if let shared = shared, shared.responds(to: selector),
           let view = shared.perform(selector, with: sessionId)?.takeUnretainedValue() as? UIView {
            return view
        }
        let empty = UIView()
        empty.backgroundColor = .clear
        empty.isUserInteractionEnabled = false
        return empty
    }

    static func openInspector(sessionId: String?) {
        let selector = NSSelectorFromString("openInspectorSessionId:")
        if let shared = shared, shared.responds(to: selector) {
            _ = shared.perform(selector, with: sessionId)
        }
    }
}
