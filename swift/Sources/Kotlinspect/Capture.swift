import Foundation
@_implementationOnly import KotlinspectCore

/// A body as an adapter saw it.
@_spi(KotlinspectAdapters)
public enum KotlinspectBody {
    /// A request body known up front (at most `maxBodyBytes` is kept).
    case data(Data)
    /// A response body: the first bytes received, and how many arrived in total.
    case received(Data, total: Int64)
    /// Not captured, for this reason ("stream", "upload", "download").
    case notCaptured(String)
}

/// One captured exchange, shared by the automatic URLSession capture and the Alamofire adapter.
/// Translation only: everything else happens in the Kotlin engine.
@_spi(KotlinspectAdapters)
public final class KotlinspectCapture {
    // A KSCaptureHandle; typed loosely so the Kotlin type stays out of this public class.
    private let handle: AnyObject

    private init(_ handle: AnyObject) {
        self.handle = handle
    }

    private var ks: KSCaptureHandle { handle as! KSCaptureHandle }

    public static var maxBodyBytes: Int { Kotlinspect.maxBodyBytes }

    /// Starts a record for `request`; the session comes from `kotlinspectSession(_:)` if set.
    public static func begin(_ request: URLRequest, body: KotlinspectBody?, source: String) -> KotlinspectCapture {
        let headers = request.allHTTPHeaderFields ?? [:]
        let names = Array(headers.keys)
        var data: Data?
        var total: Int64 = -1
        var reason: String?
        switch body {
        case .data(let bytes)?:
            data = bytes.prefix(maxBodyBytes)
            total = Int64(bytes.count)
        case .received(let bytes, let count)?:
            data = bytes
            total = count
        case .notCaptured(let why)?:
            reason = why
            total = request.value(forHTTPHeaderField: "Content-Length").flatMap { Int64($0) } ?? -1
        case nil:
            break
        }
        let handle = KSBridge.shared.begin(
            method: request.httpMethod ?? "GET",
            url: request.url?.absoluteString ?? "",
            headerNames: names,
            headerValues: names.map { headers[$0] ?? "" },
            body: data,
            bodyTotalSize: total,
            bodyContentType: request.value(forHTTPHeaderField: "Content-Type"),
            bodyNotCapturedReason: reason,
            startedAtEpochMillis: Int64(Date().timeIntervalSince1970 * 1000),
            source: source,
            sessionId: URLProtocol.property(forKey: RequestKeys.session, in: request) as? String
        )
        return KotlinspectCapture(handle)
    }

    /// Ends the record with the response, body, metrics and error, whichever are known.
    public func finish(response: URLResponse?, body: KotlinspectBody, metrics: URLSessionTaskMetrics?, error: Error?) {
        let t = Timings(metrics)
        if let http = response as? HTTPURLResponse {
            let headers = http.allHeaderFields.reduce(into: [String: String]()) { $0["\($1.key)"] = "\($1.value)" }
            let names = Array(headers.keys)
            ks.responseReceived(
                statusCode: Int32(http.statusCode),
                statusText: HTTPURLResponse.localizedString(forStatusCode: http.statusCode),
                headerNames: names,
                headerValues: names.map { headers[$0] ?? "" },
                httpVersion: t.httpVersion
            )
        }
        if let error {
            if (error as? URLError)?.code == .cancelled {
                ks.cancelled()
            } else {
                ks.failed(error: error.localizedDescription, blocked: t.blocked, dns: t.dns, connect: t.connect, ssl: t.ssl, send: t.send, wait: t.wait, receive: t.receive)
            }
            return
        }
        var data: Data?
        var total: Int64 = 0
        var reason: String?
        switch body {
        case .data(let bytes): data = bytes; total = Int64(bytes.count)
        case .received(let bytes, let count): data = bytes; total = count
        case .notCaptured(let why): reason = why; total = -1
        }
        ks.completed(
            body: data,
            bodyTotalSize: total,
            bodyContentType: (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Type") ?? response?.mimeType,
            bodyNotCapturedReason: reason,
            blocked: t.blocked, dns: t.dns, connect: t.connect, ssl: t.ssl, send: t.send, wait: t.wait, receive: t.receive,
            serverIpAddress: t.serverIpAddress
        )
    }

    /// The task was cancelled before it ended (URLProtocol.stopLoading).
    public func cancel() {
        ks.cancelled()
    }
}

extension URLRequest {
    /// The request marked so the automatic URLSession capture skips it (used by adapters that
    /// record the request themselves).
    @_spi(KotlinspectAdapters)
    public func kotlinspectHandled() -> URLRequest {
        guard let copy = (self as NSURLRequest).mutableCopy() as? NSMutableURLRequest else { return self }
        URLProtocol.setProperty(true, forKey: RequestKeys.handled, in: copy)
        return copy as URLRequest
    }
}
