# Kotlinspect in pure iOS apps (Swift package)

Use this for an iOS app with no Kotlin at all. Kotlin Multiplatform apps must not add the package
(two Kotlin runtimes); they follow the Gradle steps and `ios-native.md`.

## 1. Add the package

In Xcode: File > Add Package Dependencies > `https://github.com/asterx5/kotlinspect`, a release
version, product **Kotlinspect**. Add **KotlinspectAlamofire** too if the app uses Alamofire or
Moya. With XcodeGen or a `Package.swift`, add the same URL and products.

## 2. Nothing else is required in Debug builds

The package starts itself after launch and records `URLSession.shared` and every session built from
`.default` or `.ephemeral` configurations. Run a Debug build: the bubble appears and counts calls.
If it does not, add one line early (the `App` initializer or `application(_:didFinishLaunching...)`):

```swift
import Kotlinspect
Kotlinspect.start()
```

Use `start` with a `Configuration` for redaction (`redactedHeaders`), `excludedHosts` (for example a
host with certificate pinning), `retention`, `maxBodyBytes` or `showOverlayAutomatically = false`.

## 3. Alamofire and Moya

```swift
import KotlinspectAlamofire

let session = KotlinspectAlamofire.session()   // pass the app's configuration, interceptor and monitors
let provider = MoyaProvider<API>(session: KotlinspectAlamofire.session())
```

## 4. Optional

- Sessions: `Kotlinspect.startSession("Checkout")`, `request.kotlinspectSession(id)`.
- SwiftUI: `.kotlinspectOverlay()` to place the bubble, `KotlinspectInspectorView()` to embed the
  inspector, `Kotlinspect.openInspector()` from a debug button.
- HAR: `try await Kotlinspect.exportHAR()` returns a file URL.
- Logs: `Kotlog.d("Tag", "message", layer: "datasource")`.

## Notes

- Release builds do nothing unless the app calls `Kotlinspect.start(Configuration(enabled: true))`
  with `allowInRelease = true`.
- iOS 14 or newer.
- The automatic capture re-issues requests on its own session, so custom URLCache, cookie storage
  and timeouts of the app's session don't apply to them; use `excludedHosts` or the Alamofire
  adapter if that matters.
