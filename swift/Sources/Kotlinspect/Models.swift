import Foundation
@_implementationOnly import KotlinspectCore

/// Settings for `Kotlinspect.start(_:)`. Optional values are nil by default and mean "use
/// Kotlinspect's default", so defaults are defined once, in the engine.
public struct Configuration: Sendable {
    /// Nil: on in Debug builds, off in Release (unless `allowInRelease`).
    public var enabled: Bool? = nil
    public var allowInRelease: Bool = false
    public var sessionPolicy: SessionPolicy? = nil
    public var retention: Retention? = nil
    public var maxBodyBytes: Int? = nil
    /// Header names to redact in addition to the defaults (Authorization, cookies).
    public var redactedHeaders: [String]? = nil
    /// Records URLSession traffic automatically. Turn off to record only through the Alamofire adapter.
    public var captureURLSession: Bool = true
    /// Hosts whose requests are never captured, such as a host with certificate pinning.
    public var excludedHosts: [String] = []
    /// Shows the bubble over the app. Turn off to place `kotlinspectOverlay()` yourself.
    public var showOverlayAutomatically: Bool = true

    public init() {}

    public init(enabled: Bool?) {
        self.enabled = enabled
    }

    func bridgeConfig() -> KSConfig {
        var policy: String?
        var fixedName: String?
        switch sessionPolicy {
        case .newPerLaunch?: policy = "newPerLaunch"
        case .fixed(let name)?: policy = "fixed"; fixedName = name
        case .manual?: policy = "manual"
        case nil: policy = nil
        }
        var retentionKind: String?
        var retentionValue: Int64?
        switch retention {
        case .forever?: retentionKind = "forever"
        case .duration(let seconds)?: retentionKind = "duration"; retentionValue = Int64(seconds * 1000)
        case .maxCount(let count)?: retentionKind = "maxCount"; retentionValue = Int64(count)
        case .maxBytes(let bytes)?: retentionKind = "maxBytes"; retentionValue = Int64(bytes)
        case .inMemoryOnly?: retentionKind = "inMemoryOnly"
        case nil: retentionKind = nil
        }
        return KSConfig(
            enabled: enabled.map { KotlinBoolean(bool: $0) },
            allowInRelease: allowInRelease,
            sessionPolicy: policy,
            fixedSessionName: fixedName,
            retention: retentionKind,
            retentionValue: retentionValue.map { KotlinLong(longLong: $0) },
            maxBodyBytes: maxBodyBytes.map { KotlinLong(longLong: Int64($0)) },
            redactedHeaders: redactedHeaders,
            showOverlayAutomatically: showOverlayAutomatically
        )
    }
}

public enum SessionPolicy: Sendable, Equatable {
    case newPerLaunch
    case fixed(String)
    case manual
}

public enum Retention: Sendable, Equatable {
    case forever
    case duration(TimeInterval)
    case maxCount(Int)
    case maxBytes(Int)
    case inMemoryOnly
}

public struct SessionID: RawRepresentable, Hashable, Sendable {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }
}

public struct Session: Sendable, Identifiable {
    public let id: SessionID
    public let name: String
    public let tags: [String]
    public let metadata: [String: String]
    public let createdAt: Date
    public let callCount: Int

    init(_ s: KSSession) {
        id = SessionID(rawValue: s.id)
        name = s.name
        tags = s.tags
        metadata = s.metadata
        createdAt = Date(timeIntervalSince1970: TimeInterval(s.createdAtEpochMillis) / 1000)
        callCount = Int(s.callCount)
    }
}

public struct CallSummary: Sendable {
    public let method: String
    public let url: String
    public let statusCode: Int?
    public let durationMilliseconds: Int?
    public let failed: Bool
    public let source: String
    public let operationName: String?

    init(_ c: KSCallSummary) {
        method = c.method
        url = c.url
        statusCode = c.statusCode.map { Int($0.int32Value) }
        durationMilliseconds = c.durationMilliseconds.map { Int($0.int64Value) }
        failed = c.failed
        source = c.source
        operationName = c.operationName
    }
}

public struct HARExportOptions: Sendable {
    public var includeBodies: Bool = true
    public var includeFailed: Bool = true
    public var includePending: Bool = false

    public init(includeBodies: Bool = true, includeFailed: Bool = true, includePending: Bool = false) {
        self.includeBodies = includeBodies
        self.includeFailed = includeFailed
        self.includePending = includePending
    }
}
