import Foundation
import UIKit
@_implementationOnly import KotlinspectCore
@_implementationOnly import KotlinspectLoader

/// Kotlinspect for iOS: records URLSession (and Alamofire) traffic and shows it in a bubble,
/// toasts and an inspector, in debug builds.
///
/// Nothing needs to be called in a Debug build: the package starts itself and captures
/// `URLSession.shared` and every session built from `.default` or `.ephemeral` configurations.
/// Call `start(_:)` early (for example in your `App` initializer) to change the configuration.
public enum Kotlinspect {
    private static let lock = NSLock()
    private static var started = false
    private static var current = Configuration()

    /// The configuration the URL protocol reads; updated by `start(_:)`.
    static var configuration: Configuration {
        lock.lock()
        defer { lock.unlock() }
        return current
    }

    /// Starts Kotlinspect, or updates the settings that can change at runtime. Idempotent; when
    /// the app calls it before the automatic start, the app's configuration wins.
    public static func start(_ configuration: Configuration = .init()) {
        lock.lock()
        current = configuration
        started = true
        lock.unlock()
        let bridge = KSBridge.shared
        bridge.setHostBuildIsDebug(isDebug: HostBuild.isDebug)
        bridge.start(config: configuration.bridgeConfig())
        if configuration.showOverlayAutomatically { onMain { bridge.showOverlay() } }
    }

    /// Called once the app has finished launching (see KotlinspectLoader).
    static func autoStart() {
        lock.lock()
        let already = started
        lock.unlock()
        if !already { start() }
    }

    /// True when calls are being captured.
    public static var isEnabled: Bool { KSBridge.shared.isEnabled }

    // MARK: Sessions

    /// Starts a session and makes it the active one.
    @discardableResult
    public static func startSession(_ name: String, tags: [String] = [], metadata: [String: String] = [:]) -> SessionID {
        SessionID(rawValue: KSBridge.shared.startSession(name: name, tags: tags, metadata: metadata))
    }

    public static var activeSession: SessionID { SessionID(rawValue: KSBridge.shared.activeSessionId()) }

    public static func activateSession(_ id: SessionID) {
        KSBridge.shared.activateSession(id: id.rawValue)
    }

    public static func clearSession(_ id: SessionID) {
        KSBridge.shared.clearSession(id: id.rawValue)
    }

    // MARK: Observation

    public static func sessions() -> AsyncStream<[Session]> {
        stream { yield in
            KSBridge.shared.observeSessions { list in yield(list.map(Session.init)) }
        }
    }

    public static func callCount(session: SessionID? = nil) -> AsyncStream<Int> {
        stream { yield in
            KSBridge.shared.observeCallCount(sessionId: session?.rawValue) { yield(Int($0.int32Value)) }
        }
    }

    public static func inFlightCount(session: SessionID? = nil) -> AsyncStream<Int> {
        stream { yield in
            KSBridge.shared.observeInFlightCount(sessionId: session?.rawValue) { yield(Int($0.int32Value)) }
        }
    }

    public static func latestCall(session: SessionID? = nil) -> AsyncStream<CallSummary?> {
        stream { yield in
            KSBridge.shared.observeLatest(sessionId: session?.rawValue) { yield($0.map(CallSummary.init)) }
        }
    }

    /// Bridges a Kotlin observer to an AsyncStream that keeps the newest value and stops the
    /// observer when the stream ends.
    private static func stream<T>(_ observe: @escaping (@escaping (T) -> Void) -> KSCancellable) -> AsyncStream<T> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let cancellable = observe { continuation.yield($0) }
            continuation.onTermination = { _ in cancellable.cancel() }
        }
    }

    // MARK: UI

    public static func showOverlay() {
        onMain { KSBridge.shared.showOverlay() }
    }

    public static func hideOverlay() {
        onMain { KSBridge.shared.hideOverlay() }
    }

    public static func openInspector(session: SessionID? = nil) {
        onMain { KSBridge.shared.openInspector(sessionId: session?.rawValue) }
    }

    // MARK: Export

    /// Exports a session (the active one when nil) as a HAR 1.2 file and returns its URL.
    public static func exportHAR(session: SessionID? = nil, options: HARExportOptions = .init()) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            onMain {
                KSBridge.shared.exportHar(
                    sessionId: session?.rawValue,
                    includeBodies: options.includeBodies,
                    includeFailed: options.includeFailed,
                    includePending: options.includePending
                ) { path, error in
                    if let path {
                        continuation.resume(returning: URL(fileURLWithPath: path))
                    } else {
                        continuation.resume(throwing: KotlinspectError.exportFailed(error ?? "Unknown error"))
                    }
                }
            }
        }
    }

    static var maxBodyBytes: Int { Int(KSBridge.shared.maxBodyBytes) }

    static func onMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread { block() } else { DispatchQueue.main.async(execute: block) }
    }
}

public enum KotlinspectError: Error, Equatable {
    case exportFailed(String)
}

/// The host app's build configuration, known only to code compiled with it.
enum HostBuild {
    static var isDebug: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }
}

/// What KotlinspectLoader did at load time, for tests and diagnostics.
enum KotlinspectLoaderState {
    static var installed: Bool { KotlinspectLoader.installed }
    static var compiledForDebug: Bool { KotlinspectLoader.compiledForDebug }
}

/// Found by KotlinspectLoader with NSClassFromString once the app has finished launching.
@objc(KotlinspectAutoStart)
final class KotlinspectAutoStart: NSObject {
    @objc static func start() {
        Kotlinspect.autoStart()
    }
}
