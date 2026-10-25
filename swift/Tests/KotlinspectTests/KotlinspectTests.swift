import XCTest
import Alamofire
@testable @_spi(KotlinspectAdapters) import Kotlinspect
import KotlinspectAlamofire

/// Answers requests to mock.kotlinspect.test without a network, as the inner session's first
/// protocol (see InnerSession.protocolClassesForTesting).
final class MockServer: URLProtocol {
    static let host = "mock.kotlinspect.test"
    /// Bytes of the last request body that reached the "network".
    static var lastBodyCount = 0

    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == host }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let url = request.url!
        let path = url.path
        if path.hasPrefix("/fail") {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost))
            return
        }
        if path.hasPrefix("/redirect") {
            let target = URL(string: "https://\(Self.host)/json?from=redirect")!
            let response = HTTPURLResponse(url: url, statusCode: 302, httpVersion: "HTTP/1.1", headerFields: ["Location": target.absoluteString])!
            client?.urlProtocol(self, wasRedirectedTo: URLRequest(url: target), redirectResponse: response)
            return
        }
        if path.hasPrefix("/slow") {
            return // never answers; the test cancels it
        }
        var count = request.httpBody?.count ?? 0
        if let stream = request.httpBodyStream {
            stream.open()
            var buffer = [UInt8](repeating: 0, count: 4096)
            while true {
                let n = stream.read(&buffer, maxLength: buffer.count)
                if n <= 0 { break }
                count += n
            }
            stream.close()
        }
        Self.lastBodyCount = count
        let body = Data("{\"ok\":true,\"path\":\"\(path)\",\"received\":\(count)}".utf8)
        let response = HTTPURLResponse(url: url, statusCode: path.hasPrefix("/missing") ? 404 : 200, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

final class KotlinspectTests: XCTestCase {
    private static var started = false

    override func setUp() {
        super.setUp()
        InnerSession.protocolClassesForTesting = [MockServer.self]
        if !Self.started {
            var configuration = Configuration(enabled: true)
            configuration.retention = .inMemoryOnly
            configuration.maxBodyBytes = 64
            configuration.excludedHosts = ["excluded.kotlinspect.test"]
            Kotlinspect.start(configuration)
            Self.started = true
        }
    }

    private func url(_ path: String) -> URL { URL(string: "https://\(MockServer.host)\(path)")! }

    /// A session built from `.default`, which the loader's swizzle gives the Kotlinspect protocol.
    private lazy var session = URLSession(configuration: .default)

    @discardableResult
    private func fetch(_ request: URLRequest, using session: URLSession? = nil) async -> (Data?, URLResponse?, Error?) {
        await withCheckedContinuation { continuation in
            (session ?? self.session).dataTask(with: request) { data, response, error in
                continuation.resume(returning: (data, response, error))
            }.resume()
        }
    }

    /// HAR entries for the active session (or `session`) whose URL contains `marker`, waiting up
    /// to two seconds for them to be stored.
    private func entries(_ marker: String, session: SessionID? = nil, atLeast: Int = 1) async throws -> [[String: Any]] {
        var found: [[String: Any]] = []
        for _ in 0..<20 {
            let file = try await Kotlinspect.exportHAR(session: session, options: .init(includePending: false))
            let json = try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as! [String: Any]
            let log = json["log"] as! [String: Any]
            let all = log["entries"] as! [[String: Any]]
            found = all.filter { (($0["request"] as! [String: Any])["url"] as! String).contains(marker) }
            if found.count >= atLeast { return found }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        return found
    }

    func testLoaderInstalledAndEnabled() {
        XCTAssertTrue(KotlinspectLoaderState.installed, "KotlinspectLoader +load ran (Debug build)")
        XCTAssertTrue(Kotlinspect.isEnabled)
    }

    func testGetIsCapturedOnceWithDetails() async throws {
        var request = URLRequest(url: url("/json?case=get"))
        request.setValue("Bearer secret", forHTTPHeaderField: "Authorization")
        let (data, response, error) = await fetch(request)
        XCTAssertNil(error)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertNotNil(data)

        let found = try await entries("case=get")
        XCTAssertEqual(found.count, 1, "captured once")
        let entry = found[0]
        XCTAssertEqual((entry["response"] as! [String: Any])["status"] as? Int, 200)
        let content = (entry["response"] as! [String: Any])["content"] as! [String: Any]
        XCTAssertTrue((content["text"] as? String)?.contains("\"ok\":true") == true)
        let headers = (entry["request"] as! [String: Any])["headers"] as! [[String: String]]
        XCTAssertEqual(headers.first { $0["name"] == "Authorization" }?["value"], "██ redacted")
    }

    func testStreamBodyOverTheLimitIsNotReadAndStillDelivered() async throws {
        var request = URLRequest(url: url("/upload?case=stream"))
        request.httpMethod = "POST"
        let payload = Data(repeating: 65, count: 1024)
        request.httpBodyStream = InputStream(data: payload)
        request.setValue("1024", forHTTPHeaderField: "Content-Length")
        let (_, _, error) = await fetch(request)
        XCTAssertNil(error)
        XCTAssertEqual(MockServer.lastBodyCount, 1024, "the server got the whole body")

        let entry = try await entries("case=stream")[0]
        let postData = (entry["request"] as! [String: Any])["postData"] as? [String: Any]
        XCTAssertEqual(postData?["comment"] as? String, "Not captured: stream")
    }

    func testRedirectMakesTwoRecords() async throws {
        let (_, response, _) = await fetch(URLRequest(url: url("/redirect?case=redirect")))
        XCTAssertEqual(response?.url?.query, "from=redirect", "the final response reached the caller")
        _ = try await entries("case=redirect")
        _ = try await entries("from=redirect")
    }

    func testFailureAndCancellation() async throws {
        let (_, _, error) = await fetch(URLRequest(url: url("/fail?case=fail")))
        XCTAssertNotNil(error)
        let failed = try await entries("case=fail")[0]
        XCTAssertNotNil(failed["_error"])

        let task = session.dataTask(with: url("/slow?case=cancel"))
        task.resume()
        try await Task.sleep(nanoseconds: 200_000_000)
        task.cancel()
        let cancelled = try await entries("case=cancel")
        XCTAssertEqual(cancelled.first?["_error"] as? String, "cancelled")
    }

    func testExcludedHostsAndHandledRequestsAreNotCaptured() async throws {
        await fetch(URLRequest(url: URL(string: "https://excluded.kotlinspect.test/x?case=excluded")!))
        await fetch(URLRequest(url: url("/json?case=handled")).kotlinspectHandled())
        await fetch(URLRequest(url: url("/json?case=marker-after")))
        _ = try await entries("case=marker-after")
        let excluded = try await entries("case=excluded", atLeast: 0)
        let handled = try await entries("case=handled", atLeast: 0)
        XCTAssertTrue(excluded.isEmpty)
        XCTAssertTrue(handled.isEmpty)
    }

    func testRequestSessionRouting() async throws {
        let id = Kotlinspect.startSession("Routed")
        let active = Kotlinspect.startSession("Other")
        XCTAssertNotEqual(id, active)
        var request = URLRequest(url: url("/json?case=routed"))
        request.kotlinspectSession(id)
        await fetch(request)
        let found = try await entries("case=routed", session: id)
        XCTAssertEqual(found.count, 1)
    }

    func testAlamofireSessionHelperRecordsOnce() async throws {
        let session = KotlinspectAlamofire.session(configuration: Self.mockConfiguration())
        _ = await session.request(url("/json?case=af-helper")).serializingData().response
        let found = try await entries("case=af-helper")
        try await Task.sleep(nanoseconds: 300_000_000)
        let again = try await entries("case=af-helper")
        XCTAssertEqual(again.count, 1)
        XCTAssertEqual(found.count, 1)
    }

    func testAlamofireMonitorOnlyIsDeduplicated() async throws {
        let session = Alamofire.Session(configuration: Self.mockConfiguration(), eventMonitors: [KotlinspectAlamofire.eventMonitor])
        _ = await session.request(url("/json?case=af-monitor")).serializingData().response
        _ = try await entries("case=af-monitor")
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertEqual(try await entries("case=af-monitor").count, 1, "outer Alamofire record kept, inner URLSession one merged")
    }

    /// Alamofire's default configuration also gets the Kotlinspect protocol, which replays on the mock.
    private static func mockConfiguration() -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.af.default
        var classes = configuration.protocolClasses ?? []
        // Right after Kotlinspect's protocol, so a request it skips still reaches the mock.
        let index = classes.firstIndex { $0 == KotlinspectURLProtocol.self }.map { $0 + 1 } ?? 0
        classes.insert(MockServer.self, at: index)
        configuration.protocolClasses = classes
        return configuration
    }

    func testObserversAndStreamCancellation() async throws {
        await fetch(URLRequest(url: url("/json?case=observe")))
        var iterator = Kotlinspect.callCount().makeAsyncIterator()
        let count = await iterator.next()
        XCTAssertNotNil(count)
        XCTAssertGreaterThan(count ?? 0, 0)
        let task = Task { for await _ in Kotlinspect.latestCall() {} }
        task.cancel()
    }

    func testExportedFileIsReadable() async throws {
        await fetch(URLRequest(url: url("/json?case=export")))
        _ = try await entries("case=export")
        let file = try await Kotlinspect.exportHAR()
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        XCTAssertEqual(file.pathExtension, "har")
    }
}
