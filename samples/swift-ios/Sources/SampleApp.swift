import SwiftUI
import Alamofire
import Kotlinspect
import KotlinspectAlamofire

/// A pure SwiftUI app. It never calls `Kotlinspect.start()`: in Debug builds the package starts
/// itself and captures URLSession traffic, which is what this sample demonstrates.
@main
struct SampleApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @State private var status = "Tap a button"
    @State private var har = ""
    @State private var session: SessionID?
    private let alamofire = KotlinspectAlamofire.session()

    var body: some View {
        NavigationView {
            List {
                Section {
                    Text(status).font(.footnote.monospaced()).accessibilityIdentifier("status")
                    // Read by the UI tests to explain a failure.
                    TimelineView(.periodic(from: .now, by: 1)) { _ in
                        Text("enabled=\(Kotlinspect.isEnabled) session=\(Kotlinspect.activeSession.rawValue.prefix(8)) \(Self.windowsState())")
                            .font(.caption2.monospaced()).accessibilityIdentifier("diag")
                    }
                    if CommandLine.arguments.contains("-kotlinspect-report-db") {
                        Text(Self.databaseState()).accessibilityIdentifier("dbState")
                    }
                }
                Section("URLSession") {
                    Button("GET JSON") { load("https://jsonplaceholder.typicode.com/todos/1") }
                    Button("POST JSON") { post() }
                    Button("404") { load("https://httpbin.org/status/404") }
                    Button("Timeout") { load("https://httpbin.org/delay/10", timeout: 2) }
                    Button("Cancelled request") { cancelled() }
                    Button("Image (binary)") { load("https://httpbin.org/image/png") }
                    Button("Upload stream") { upload() }
                }
                Section("Alamofire") {
                    Button("Alamofire GET") {
                        alamofire.request("https://jsonplaceholder.typicode.com/posts/1").response { report("Alamofire GET", $0.response, $0.error) }
                    }
                    Button("Alamofire retried request") {
                        alamofire.request("https://httpbin.org/status/503", interceptor: RetryPolicy(retryLimit: 1, exponentialBackoffBase: 1, exponentialBackoffScale: 0.1))
                            .response { report("Alamofire retry", $0.response, $0.error) }
                    }
                }
                Section("Sessions") {
                    Button("Start session") { session = Kotlinspect.startSession("Checkout", tags: ["demo"]); status = "Started Checkout" }
                    Button("Tagged request") {
                        var request = URLRequest(url: URL(string: "https://jsonplaceholder.typicode.com/users/1")!)
                        if let session { request.kotlinspectSession(session) }
                        URLSession.shared.dataTask(with: request) { _, response, error in report("Tagged", response, error) }.resume()
                    }
                    Button("Clear active session") { Kotlinspect.clearSession(Kotlinspect.activeSession) }
                    Button("Open inspector") { Kotlinspect.openInspector() }
                }
                Section("Export") {
                    Button("Export HAR") { export() }
                    Text(har).font(.footnote.monospaced()).accessibilityIdentifier("harState")
                }
                Section("Kotlog") {
                    Button("Log each level") {
                        Kotlog.v("Sample", "Verbose message", layer: "ui")
                        Kotlog.d("Sample", "Debug message", layer: "datasource")
                        Kotlog.i("Sample", "Info message", layer: "repository")
                        Kotlog.w("Sample", "Warning message")
                        Kotlog.e("Sample", "Error message", layer: "viewmodel", error: URLError(.timedOut))
                        status = "Logged five entries"
                    }
                }
            }
            .navigationTitle("Kotlinspect Sample")
        }
    }

    private func load(_ url: String, timeout: TimeInterval = 15) {
        var request = URLRequest(url: URL(string: url)!)
        request.timeoutInterval = timeout
        URLSession.shared.dataTask(with: request) { _, response, error in report(url, response, error) }.resume()
    }

    private func post() {
        var request = URLRequest(url: URL(string: "https://httpbin.org/post")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer sample-token", forHTTPHeaderField: "Authorization")
        request.httpBody = Data(#"{"user":"demo","items":[1,2,3]}"#.utf8)
        URLSession.shared.dataTask(with: request) { _, response, error in report("POST", response, error) }.resume()
    }

    private func cancelled() {
        let task = URLSession.shared.dataTask(with: URL(string: "https://httpbin.org/delay/5")!) { _, response, error in report("Cancelled", response, error) }
        task.resume()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { task.cancel() }
    }

    private func upload() {
        var request = URLRequest(url: URL(string: "https://httpbin.org/post")!)
        request.httpMethod = "POST"
        request.httpBodyStream = InputStream(data: Data(repeating: 65, count: 512 * 1024))
        request.setValue("\(512 * 1024)", forHTTPHeaderField: "Content-Length")
        URLSession(configuration: .default).dataTask(with: request) { _, response, error in report("Upload", response, error) }.resume()
    }

    private func export() {
        Task {
            do {
                let file = try await Kotlinspect.exportHAR()
                har = "har: " + file.lastPathComponent
                await MainActor.run { share(file) }
            } catch {
                har = "har failed: \(error)"
            }
        }
    }

    @MainActor
    private func share(_ file: URL) {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let root = scene.windows.first(where: \.isKeyWindow)?.rootViewController else { return }
        let sheet = UIActivityViewController(activityItems: [file], applicationActivities: nil)
        sheet.popoverPresentationController?.sourceView = root.view
        root.present(sheet, animated: true)
    }

    private func report(_ label: String, _ response: URLResponse?, _ error: Error?) {
        let code = (response as? HTTPURLResponse)?.statusCode
        DispatchQueue.main.async {
            status = "\(label): " + (error.map { "\($0.localizedDescription)" } ?? "HTTP \(code ?? 0)")
        }
    }

    /// Windows and whether the bubble view is in one, for the UI tests' failure messages.
    static func windowsState() -> String {
        let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
        func find(_ view: UIView) -> UIView? {
            if view.accessibilityIdentifier == "kotlinspect.bubble" { return view }
            for sub in view.subviews { if let hit = find(sub) { return hit } }
            return nil
        }
        let levels = windows.map { "\(Int($0.windowLevel.rawValue))\($0.isHidden ? "h" : "")" }.joined(separator: ",")
        let bubble = windows.lazy.compactMap { find($0) }.first
        let state = bubble.map { "bubble hidden=\($0.isHidden) alpha=\($0.alpha) frame=\(Int($0.frame.minX)),\(Int($0.frame.minY))" } ?? "no bubble view"
        return "windows=[\(levels)] \(state)"
    }

    /// For the Release UI test: Kotlinspect must not create its database there.
    static func databaseState() -> String {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let exists = FileManager.default.fileExists(atPath: base.appendingPathComponent("Kotlinspect/kotlinspect.db").path)
        return exists ? "db: exists" : "db: none"
    }
}
