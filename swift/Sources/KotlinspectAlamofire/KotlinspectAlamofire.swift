import Foundation
import Alamofire
@_spi(KotlinspectAdapters) import Kotlinspect

/// Records Alamofire (and Moya, which runs on an Alamofire Session) traffic in Kotlinspect.
///
/// ```swift
/// let session = KotlinspectAlamofire.session()          // or pass your configuration, interceptor and monitors
/// let provider = MoyaProvider<API>(session: KotlinspectAlamofire.session())
/// ```
public enum KotlinspectAlamofire {
    /// Records each request from the URLSession events Alamofire forwards.
    public static let eventMonitor: EventMonitor = KotlinspectEventMonitor()

    /// Marks outgoing requests so the automatic URLSession capture leaves them to the monitor.
    /// It changes nothing else about the request.
    public static let requestInterceptor: RequestInterceptor = KotlinspectRequestMarker()

    /// An Alamofire Session with Kotlinspect's monitor added to yours and its interceptor composed
    /// with yours.
    public static func session(
        configuration: URLSessionConfiguration = URLSessionConfiguration.af.default,
        interceptor: RequestInterceptor? = nil,
        eventMonitors: [EventMonitor] = []
    ) -> Alamofire.Session {
        let composed = Interceptor(adapters: [], retriers: [], interceptors: [requestInterceptor] + (interceptor.map { [$0] } ?? []))
        return Alamofire.Session(configuration: configuration, interceptor: composed, eventMonitors: [eventMonitor] + eventMonitors)
    }
}

/// Sets the handled marker. Retries go through adapt again, so they stay marked.
struct KotlinspectRequestMarker: RequestInterceptor {
    func adapt(_ urlRequest: URLRequest, for session: Alamofire.Session, completion: @escaping (Result<URLRequest, Error>) -> Void) {
        completion(.success(urlRequest.kotlinspectHandled()))
    }
}

/// One record per URLSession task, so each retry is its own record.
final class KotlinspectEventMonitor: EventMonitor {
    let queue = DispatchQueue(label: "dev.kotlinspect.alamofire")

    private final class Record {
        let capture: KotlinspectCapture
        var data = Data()
        var total: Int64 = 0
        var metrics: URLSessionTaskMetrics?
        let streamsBody: Bool
        init(_ capture: KotlinspectCapture, streamsBody: Bool) {
            self.capture = capture
            self.streamsBody = streamsBody
        }
    }

    // Owned by `queue`.
    private var records = [ObjectIdentifier: Record]()

    func request(_ request: Request, didCreateTask task: URLSessionTask) {
        guard Kotlinspect.isEnabled, let urlRequest = task.originalRequest else { return }
        let isUpload = request is UploadRequest
        let isDownload = request is DownloadRequest
        let body: KotlinspectBody?
        if let data = urlRequest.httpBody {
            body = .data(data)
        } else if isUpload || urlRequest.httpBodyStream != nil {
            body = .notCaptured("upload")
        } else {
            body = nil
        }
        let capture = KotlinspectCapture.begin(urlRequest, body: body, source: "alamofire")
        records[ObjectIdentifier(task)] = Record(capture, streamsBody: isDownload)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard let record = records[ObjectIdentifier(dataTask)] else { return }
        record.total += Int64(data.count)
        let limit = KotlinspectCapture.maxBodyBytes
        if record.data.count < limit { record.data.append(data.prefix(limit - record.data.count)) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didFinishCollecting metrics: URLSessionTaskMetrics) {
        records[ObjectIdentifier(task)]?.metrics = metrics
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let record = records.removeValue(forKey: ObjectIdentifier(task)) else { return }
        let body: KotlinspectBody = record.streamsBody ? .notCaptured("download") : .received(record.data, total: record.total)
        record.capture.finish(response: task.response, body: body, metrics: record.metrics, error: error)
    }
}
