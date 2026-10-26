import XCTest
import Alamofire
@_spi(KotlinspectAdapters) import Kotlinspect
import KotlinspectAlamofire

/// Answers requests to mock.kotlinspect.test without a network, as the inner session's first
/// protocol (see KotlinspectTesting.innerProtocolClasses).
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

final class KotlinspectAlamofireTests: XCTestCase {
    private static var started = false

    override func setUp() {
        super.setUp()
        KotlinspectTesting.innerProtocolClasses = [MockServer.self]
        if !Self.started {
            var configuration = Configuration(enabled: true)
            configuration.retention = .inMemoryOnly
            Kotlinspect.start(configuration)
            Self.started = true
        }
    }

    private func url(_ path: String) -> URL { URL(string: "https://\(MockServer.host)\(path)")! }

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
        let recorded = try await entries("case=af-monitor").count
        XCTAssertEqual(recorded, 1, "outer Alamofire record kept, inner URLSession one merged")
    }

    /// Alamofire's default configuration also gets the Kotlinspect protocol, which replays on the mock.
    private static func mockConfiguration() -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.af.default
        var classes = configuration.protocolClasses ?? []
        // Right after Kotlinspect's protocol, so a request it skips still reaches the mock.
        let index = classes.firstIndex { ObjectIdentifier($0) == ObjectIdentifier(KotlinspectTesting.urlProtocolClass) }.map { $0 + 1 } ?? 0
        classes.insert(MockServer.self, at: index)
        configuration.protocolClasses = classes
        return configuration
    }
}
