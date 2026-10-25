import Foundation
@_implementationOnly import KotlinspectCore

/// Request properties Kotlinspect uses. They live on URLRequest via URLProtocol and never reach
/// the network.
enum RequestKeys {
    /// Already being captured (the inner copy, or a request the Alamofire adapter records itself).
    static let handled = "dev.kotlinspect.handled"
    /// The session a request is routed to (`URLRequest.kotlinspectSession(_:)`).
    static let session = "dev.kotlinspect.session"
}

extension URLRequest {
    /// Records this request in the given session instead of the active one.
    public mutating func kotlinspectSession(_ id: SessionID) {
        guard let copy = (self as NSURLRequest).mutableCopy() as? NSMutableURLRequest else { return }
        URLProtocol.setProperty(id.rawValue, forKey: RequestKeys.session, in: copy)
        self = copy as URLRequest
    }
}

/// Automatic URLSession capture. Registered for URLSession.shared and inserted into default and
/// ephemeral configurations by KotlinspectLoader. Each request is re-issued, marked as handled,
/// on one internal session; every event is forwarded to the original client.
@objc(KotlinspectURLProtocol)
final class KotlinspectURLProtocol: URLProtocol {

    override class func canInit(with request: URLRequest) -> Bool {
        guard let url = request.url, let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" else { return false }
        if URLProtocol.property(forKey: RequestKeys.handled, in: request) != nil { return false }
        let configuration = Kotlinspect.configuration
        guard configuration.captureURLSession, KSBridge.shared.isEnabled else { return false }
        if let host = url.host?.lowercased(), configuration.excludedHosts.contains(where: { $0.lowercased() == host }) { return false }
        return true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    // State, touched on the inner session's serial delegate queue and in start/stop.
    private var innerTask: URLSessionDataTask?
    private var capture: KotlinspectCapture?
    private var response: URLResponse?
    private var received = Data()
    private var receivedTotal: Int64 = 0
    private var limit = 0
    private var metrics: URLSessionTaskMetrics?
    private var completion: Error??
    private var ended = false
    /// False once a redirect was handed to the client: the rest of this task is not the client's.
    private var forwarding = true
    private var clientThread: Thread?
    private var clientModes: [RunLoop.Mode] = [.default]

    override func startLoading() {
        clientThread = Thread.current
        if let mode = RunLoop.current.currentMode, mode != .default { clientModes = [.default, mode] }
        limit = Kotlinspect.maxBodyBytes

        guard let inner = (request as NSURLRequest).mutableCopy() as? NSMutableURLRequest else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        URLProtocol.setProperty(true, forKey: RequestKeys.handled, in: inner)

        // The body: kept when small and known, never partly read from a stream that goes on.
        var body: KotlinspectBody?
        if let data = request.httpBody {
            body = .data(data)
        } else if let stream = request.httpBodyStream {
            let declared = request.value(forHTTPHeaderField: "Content-Length").flatMap { Int64($0) }
            if let declared, declared <= Int64(limit), let data = Self.read(stream, max: limit) {
                inner.httpBodyStream = nil
                inner.httpBody = data
                body = .data(data)
            } else {
                body = .notCaptured("stream")
            }
        }
        capture = KotlinspectCapture.begin(request, body: body, source: "urlsession")
        innerTask = InnerSession.shared.start(inner as URLRequest, for: self)
    }

    override func stopLoading() {
        innerTask?.cancel()
        InnerSession.shared.queue.addOperation { [self] in
            guard !ended else { return }
            ended = true
            capture?.cancel()
        }
    }

    /// Reads a whole stream of at most `max` bytes; nil if it is longer or fails.
    private static func read(_ stream: InputStream, max: Int) -> Data? {
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 16 * 1024)
        while true {
            let n = stream.read(&buffer, maxLength: buffer.count)
            if n < 0 { return nil }
            if n == 0 { break }
            data.append(buffer, count: n)
            if data.count > max { return nil }
        }
        return data
    }

    // MARK: Client calls, on the thread that started loading

    private func onClient(_ block: @escaping () -> Void) {
        guard let thread = clientThread, thread != Thread.current else { return block() }
        let box = BlockBox(block)
        box.perform(#selector(BlockBox.run), on: thread, with: nil, waitUntilDone: false, modes: clientModes.map(\.rawValue))
    }

    // MARK: Inner session events (serial delegate queue)

    func innerDidReceive(_ response: URLResponse) {
        self.response = response
        guard forwarding else { return }
        onClient { self.client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed) }
    }

    func innerDidLoad(_ data: Data) {
        receivedTotal += Int64(data.count)
        if received.count < limit { received.append(data.prefix(limit - received.count)) }
        guard forwarding else { return }
        onClient { self.client?.urlProtocol(self, didLoad: data) }
    }

    func innerWillRedirect(_ newRequest: URLRequest, response: HTTPURLResponse) {
        // This hop ends here; the follow-up request is captured as a new record.
        self.response = response
        forwarding = false
        var redirect = newRequest
        if let copy = (newRequest as NSURLRequest).mutableCopy() as? NSMutableURLRequest {
            URLProtocol.removeProperty(forKey: RequestKeys.handled, in: copy)
            redirect = copy as URLRequest
        }
        finishRecord(error: nil)
        onClient {
            self.client?.urlProtocol(self, wasRedirectedTo: redirect, redirectResponse: response)
            self.innerTask?.cancel()
            self.client?.urlProtocol(self, didFailWithError: URLError(.cancelled))
        }
    }

    func innerDidReceive(_ challenge: URLAuthenticationChallenge, completion: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        let forwarded = URLAuthenticationChallenge(authenticationChallenge: challenge, sender: ChallengeSender(completion))
        onClient { self.client?.urlProtocol(self, didReceive: forwarded) }
    }

    func innerDidCollect(_ metrics: URLSessionTaskMetrics) {
        self.metrics = metrics
        if let error = completion { finishRecord(error: error) }
    }

    func innerDidComplete(_ error: Error?) {
        if forwarding {
            onClient {
                if let error { self.client?.urlProtocol(self, didFailWithError: error) } else { self.client?.urlProtocolDidFinishLoading(self) }
            }
        }
        if metrics != nil { return finishRecord(error: error) }
        // Metrics usually arrive first; give them a moment, then record without them. The wait is
        // off the delegate queue so other requests keep flowing.
        completion = .some(error)
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.3) { [weak self] in
            InnerSession.shared.queue.addOperation { self?.finishRecord(error: error) }
        }
    }

    private func finishRecord(error: Error?) {
        guard !ended else { return }
        ended = true
        capture?.finish(response: response, body: .received(received, total: receivedTotal), metrics: metrics, error: error)
    }
}

/// Phase durations from the last transaction's metrics, in milliseconds.
struct Timings {
    var blocked, dns, connect, ssl, send, wait, receive: KotlinLong?
    var httpVersion: String?
    var serverIpAddress: String?

    init(_ metrics: URLSessionTaskMetrics?) {
        guard let m = metrics?.transactionMetrics.last else { return }
        func span(_ from: Date?, _ to: Date?) -> KotlinLong? {
            guard let from, let to, to >= from else { return nil }
            return KotlinLong(longLong: Int64(to.timeIntervalSince(from) * 1000))
        }
        dns = span(m.domainLookupStartDate, m.domainLookupEndDate)
        connect = span(m.connectStartDate, m.connectEndDate)
        ssl = span(m.secureConnectionStartDate, m.secureConnectionEndDate)
        send = span(m.requestStartDate, m.requestEndDate)
        wait = span(m.requestEndDate, m.responseStartDate)
        receive = span(m.responseStartDate, m.responseEndDate)
        blocked = span(m.fetchStartDate, m.domainLookupStartDate ?? m.connectStartDate ?? m.requestStartDate)
        httpVersion = m.networkProtocolName
        if #available(iOS 13.0, *) { serverIpAddress = m.remoteAddress }
    }
}

/// One URLSession for every re-issued request, routing delegate events to their protocol.
final class InnerSession: NSObject, URLSessionDataDelegate {
    static let shared = InnerSession()

    /// Tests inject a mock protocol here so the package tests need no network.
    static var protocolClassesForTesting: [AnyClass]?

    let queue: OperationQueue = {
        let q = OperationQueue()
        q.maxConcurrentOperationCount = 1
        q.name = "dev.kotlinspect.urlsession"
        return q
    }()

    private var owners = [Int: KotlinspectURLProtocol]()
    private let lock = NSLock()

    private lazy var session: URLSession = {
        // The swizzled default configuration includes our protocol; the inner session must not.
        let configuration = URLSessionConfiguration.default
        var classes = (configuration.protocolClasses ?? []).filter { $0 != KotlinspectURLProtocol.self }
        if let test = Self.protocolClassesForTesting { classes = test + classes }
        configuration.protocolClasses = classes
        return URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
    }()

    func start(_ request: URLRequest, for owner: KotlinspectURLProtocol) -> URLSessionDataTask {
        let task = session.dataTask(with: request)
        lock.lock()
        owners[task.taskIdentifier] = owner
        lock.unlock()
        task.resume()
        return task
    }

    private func owner(_ task: URLSessionTask) -> KotlinspectURLProtocol? {
        lock.lock()
        defer { lock.unlock() }
        return owners[task.taskIdentifier]
    }

    private func release(_ task: URLSessionTask) {
        lock.lock()
        owners[task.taskIdentifier] = nil
        lock.unlock()
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        owner(dataTask)?.innerDidReceive(response)
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        owner(dataTask)?.innerDidLoad(data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        if let owner = owner(task) {
            owner.innerWillRedirect(request, response: response)
            completionHandler(nil)
        } else {
            completionHandler(request)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard let owner = owner(task) else { return completionHandler(.performDefaultHandling, nil) }
        owner.innerDidReceive(challenge, completion: completionHandler)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didFinishCollecting metrics: URLSessionTaskMetrics) {
        owner(task)?.innerDidCollect(metrics)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        owner(task)?.innerDidComplete(error)
        release(task)
    }
}

/// Answers a forwarded authentication challenge on behalf of the inner task.
private final class ChallengeSender: NSObject, URLAuthenticationChallengeSender {
    private let completion: (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    private var answered = false
    private let lock = NSLock()

    init(_ completion: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        self.completion = completion
    }

    private func answer(_ disposition: URLSession.AuthChallengeDisposition, _ credential: URLCredential?) {
        lock.lock()
        let first = !answered
        answered = true
        lock.unlock()
        if first { completion(disposition, credential) }
    }

    func use(_ credential: URLCredential, for challenge: URLAuthenticationChallenge) { answer(.useCredential, credential) }
    func continueWithoutCredential(for challenge: URLAuthenticationChallenge) { answer(.useCredential, nil) }
    func cancel(_ challenge: URLAuthenticationChallenge) { answer(.cancelAuthenticationChallenge, nil) }
    func performDefaultHandling(for challenge: URLAuthenticationChallenge) { answer(.performDefaultHandling, nil) }
    func rejectProtectionSpaceAndContinue(with challenge: URLAuthenticationChallenge) { answer(.rejectProtectionSpace, nil) }
}

/// Runs a closure through perform(_:on:with:waitUntilDone:modes:).
private final class BlockBox: NSObject {
    private let block: () -> Void
    init(_ block: @escaping () -> Void) { self.block = block }
    @objc func run() { block() }
}
