import Foundation
@_implementationOnly import KotlinspectCore

/// Logs that print with NSLog and appear in the inspector's Logs tab, filterable by tag, layer and
/// level.
///
/// ```swift
/// Kotlog.d("Feed", "Loaded \(items.count) items", layer: "datasource")
/// Kotlog.e("Feed", "Request failed", layer: "repository", error: error)
/// ```
public enum Kotlog {
    public static func v(_ tag: String, _ message: String, layer: String? = nil) {
        engine.v(tag: tag, message: message, layer: layer)
    }

    public static func d(_ tag: String, _ message: String, layer: String? = nil) {
        engine.d(tag: tag, message: message, layer: layer)
    }

    public static func i(_ tag: String, _ message: String, layer: String? = nil) {
        engine.i(tag: tag, message: message, layer: layer)
    }

    public static func w(_ tag: String, _ message: String, layer: String? = nil, error: Error? = nil) {
        engine.w(tag: tag, message: message, layer: layer, throwable: throwable(error))
    }

    public static func e(_ tag: String, _ message: String, layer: String? = nil, error: Error? = nil) {
        engine.e(tag: tag, message: message, layer: layer, throwable: throwable(error))
    }

    private static var engine: KotlinspectCore.Kotlog { KotlinspectCore.Kotlog.shared }

    /// The error's type and description, as the stack trace shown in the inspector.
    private static func throwable(_ error: Error?) -> KotlinThrowable? {
        guard let error else { return nil }
        return KotlinThrowable(message: "\(type(of: error)): \(error.localizedDescription)")
    }
}
