# Kotlinspect

[![Maven Central](https://img.shields.io/maven-central/v/io.github.asterx5/kotlinspect?label=Maven%20Central&include_prereleases)](https://central.sonatype.com/artifact/io.github.asterx5/kotlinspect)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

Latest: **1.0.0-beta11**. See the [changelog](CHANGELOG.md).

A network inspector for Kotlin Multiplatform apps that use Ktor. Add one dependency and every HTTP call shows up in a floating bubble, a toast, and a full inspector screen on **Android, iOS and Desktop**. It is off in release builds by default.

- **Capture**: method, URL, headers, bodies, status, timing and failures, including in-flight and cancelled calls. Large bodies are truncated, binary bodies are skipped, and streaming responses are passed through untouched.
- **Redaction**: `Authorization`, `Proxy-Authorization`, `Cookie` and `Set-Cookie` are hidden by default. You can redact more headers, query parameters or body content.
- **Sessions**: group calls per launch, under a fixed name, or manually, and tag single requests or whole coroutines.
- **Storage**: a private Room database with retention by age, count or size, or memory only.
- **UI**: a draggable bubble (count, in-flight ring, error tint, log count), toasts, and an inspector with Network and Logs tabs, search, filters, pretty-printed JSON and *Copy as cURL*. Compose and native iOS (UIKit) have the same features.
- **Logging**: `KtLog.d(TAG, "message", "datasource")` prints the platform way and shows in a Logs tab with tag, layer and level filters. See [Kotlog](#logging-with-kotlog).
- **API**: observe counts and calls as `Flow`s to build your own indicators.

## For AI agents

A ready-made skill teaches coding agents (Claude Code, and other agents that read `SKILL.md`
skills) how to add Kotlinspect to a project: picking the right artifact, adding the dependency in
the right module, installing the plugin, logging with Kotlog, and the iOS-without-Compose setup.

- **Download:** [`kotlinspect-skill.zip`](https://github.com/asterx5/kotlinspect/releases/latest/download/kotlinspect-skill.zip) from the latest release.
- **Install for Claude Code:** unzip it into `~/.claude/skills/` (all your projects) or `.claude/skills/` in one project, so you get `skills/kotlinspect/SKILL.md`.
- Then ask the agent something like *"add Kotlinspect to this app so I can see its network calls"*.

The skill's source is in [`skills/kotlinspect`](skills/kotlinspect).

## Installation

Kotlinspect comes in two artifacts:

| Artifact | Contains | Use it when |
|---|---|---|
| `io.github.asterx5:kotlinspect` | Everything, with a Compose UI (bubble, toast, inspector) | Your app uses Compose Multiplatform or Jetpack Compose |
| `io.github.asterx5:kotlinspect-core` | Capture, storage, sessions, the API and the **native iOS UI**. No Compose at all | Your iOS app uses SwiftUI or UIKit, or you only need capture |
| `io.github.asterx5:kotlog` | Just the `KtLog` logging API, no dependencies | Modules that only log (both artifacts above include it) |

`kotlinspect` includes `kotlinspect-core`, so you only ever add one of them.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal() // only needed for local builds
    }
}

// build.gradle.kts of your shared module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.asterx5:kotlinspect:1.0.0-beta11")
        }
    }
}
```

To use a local build in your other projects:

```bash
./gradlew :kotlog:publishToMavenLocal :kotlinspect-core:publishToMavenLocal :kotlinspect:publishToMavenLocal
```

Then install the plugin in your client:

```kotlin
val client = HttpClient {
    install(KotlinspectPlugin)
}
```

That's all. On Android, the library's manifest picks up the app context automatically. On iOS and desktop, the bubble appears with the first call.

## Logging with Kotlog

**Kotlog** is a tiny logging API that ships with Kotlinspect. Every `KtLog` entry prints the usual
way for its platform (Android `Log`/Logcat, `NSLog` on iOS, the console on desktop) and also shows
up in the inspector's **Logs** tab, next to the network calls of the same session. Only what you log
with `KtLog` is captured, not the rest of the console.

It's included in both `kotlinspect` and `kotlinspect-core`. Modules that only log (data, domain)
can depend on `io.github.asterx5:kotlog` alone, which has no dependencies.

### Kotlin

```kotlin
import io.github.asterx5.kotlog.KtLog
import io.github.asterx5.kotlog.LogLayer
import io.github.asterx5.kotlog.LogLevel

private const val TAG = "FeedRepository"

KtLog.d(TAG, "Loaded ${items.size} items", LogLayer.Datasource.value)
KtLog.i(TAG, "Cache hit", "repository")                      // any string works as a layer
KtLog.w(TAG, "Slow response")                                 // the layer is optional
KtLog.e(TAG, "Request failed", LogLayer.Repository.value, e)  // w, e and wtf take a Throwable
KtLog.log(LogLevel.Warn, TAG, "Retrying", "datasource")       // generic: pass the level
```

| Field | Values |
|---|---|
| Level | `v` Verbose, `d` Debug, `i` Info, `w` Warn, `e` Error, `wtf` Assert, like Android. `LogLevel` for `log(...)` |
| Tag | Any string, usually the class name |
| Message | Any string |
| Layer | Any string, or `LogLayer.Datasource`, `Repository`, `Usecase`, `ViewModel`, `UI` via `.value` |

### Swift

Export `kotlog` from your framework (see [iOS without Compose](#ios-without-compose-swiftui-or-uikit)), then:

```swift
import Shared

KtLog.shared.d(tag: "Feed", message: "Loaded \(items.count) items", layer: LogLayer.datasource.value)
KtLog.shared.e(tag: "Feed", message: "Failed: \(error)", layer: "repository", throwable: nil)
KtLog.shared.log(level: .warn, tag: "Feed", message: "Retrying", layer: nil, throwable: nil)
```

### In the inspector

- **Logs tab** in both the Compose inspector and the native iOS (UIKit) inspector: newest first,
  level letter, tag, layer and time, with the first lines of the message.
- **Search** by message, and **multi-select filters** for levels, tags and layers. Filters only list
  values that appear in the session, with counts.
- **Tap an entry** for the full message and stack trace, and copy it.
- **Bubble**: a small log count under the call count, on every platform.

### Options

```kotlin
Kotlinspect.configure {
    logs {
        enabled = true      // store KtLog entries for the inspector
        maxCount = 5_000    // oldest entries are dropped past this
    }
    bubble { showLogCount = false }   // hide the log count on the bubble
}
KtLog.consoleEnabled = false          // stop printing to the console; the inspector still gets entries
```

Logs follow their session: clearing or deleting a session removes its logs too. In release builds
(Kotlinspect inactive) `KtLog` still prints but stores nothing. `Kotlinspect.logCount` is a
`Flow<Int>` for your own indicators.

## Placing the bubble yourself

By default the bubble floats over every screen. You can instead place it in your own UI, for
example only on a debug screen, or scoped to one session. While a placed overlay is on screen,
the automatic bubble hides. Pass a `sessionId` to count, toast and open only that session; leave it
out for the current session.

**Compose** (Android, desktop and Compose Multiplatform iOS):

```kotlin
Box(Modifier.fillMaxSize()) {
    AppContent()
    KotlinspectOverlay()                       // current session
    // KotlinspectOverlay(sessionId = checkoutId) // only one session
}
```

To never show the automatic bubble, only placed ones:

```kotlin
Kotlinspect.configure { bubble { automatic = false } }
```

## iOS without Compose (SwiftUI or UIKit)

Use `kotlinspect-core`: the bubble, toast and inspector are native UIKit, so no Compose is
linked into your app. Swift can only see Kotlin classes your framework exports, so export it once
in the shared module that builds your iOS framework:

```kotlin
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "Shared"
            export("io.github.asterx5:kotlinspect-core:1.0.0-beta11")
            export("io.github.asterx5:kotlog:1.0.0-beta11")   // KtLog from Swift
        }
    }
    sourceSets {
        commonMain.dependencies {
            api("io.github.asterx5:kotlinspect-core:1.0.0-beta11")
            api("io.github.asterx5:kotlog:1.0.0-beta11")
        }
    }
}
```

Without any Swift code the bubble already appears automatically. To place it yourself:

**SwiftUI**: add the Swift package from this repository
(`https://github.com/asterx5/kotlinspect`, product `KotlinspectSwiftUI`), then:

```swift
import KotlinspectSwiftUI

ContentView()
    .overlay(KotlinspectOverlay())               // current session
    // .overlay(KotlinspectOverlay(sessionId: id)) // only one session
```

Or, without the package, wrap the view yourself:

```swift
struct KotlinspectOverlay: UIViewRepresentable {
    var sessionId: String? = nil
    func makeUIView(context: Context) -> UIView {
        KotlinspectUIKit.shared.makeOverlayView(sessionId: sessionId)
    }
    func updateUIView(_ uiView: UIView, context: Context) {}
}
```

**UIKit**:

```swift
let overlay = KotlinspectUIKit.shared.makeOverlayView(sessionId: nil)
overlay.frame = view.bounds
overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
view.addSubview(overlay)
```

Open the inspector from anywhere with `KotlinspectUIKit.shared.openInspector(sessionId: nil)`.
The native inspector has the same Network and Logs tabs as the Compose one, and the native bubble
shows the log count too. It needs iOS 14 or newer.

## Requirements

Kotlinspect asks for the oldest versions it works with, so it never forces your app to upgrade.
If your app uses newer versions, Gradle keeps yours.

| | Minimum |
|---|---|
| Kotlin | 2.1.20 |
| Ktor client | 3.0.0 |
| Compose Multiplatform / Jetpack Compose | 1.7.0 |
| kotlinx-coroutines | 1.9.0 |
| kotlinx-serialization | 1.6.3 |
| Room / SQLite (bundled) | 2.7.0 / 2.5.0 |
| Android | minSdk 24, compileSdk 34, AndroidX Activity 1.8.2 |
| iOS | Native inspector on iOS 14+, Compose inspector below |

## Configuration

Call `configure` early: in `Application.onCreate`, `main`, or your iOS app's init. Every option has a default.

```kotlin
Kotlinspect.configure {
    sessionPolicy = SessionPolicy.NewPerLaunch            // or Fixed("qa"), Manual
    retention = RetentionPolicy.MaxCount(1_000)          // MaxAge(3.days), MaxSize(5_000_000), Forever, InMemoryOnly
    retentionFor("checkout", RetentionPolicy.Forever)    // per-session override
    maxBodyBytes = 256L * 1024

    redactHeaders("X-Api-Key")
    redactQueryParameters("token")
    bodyRedactor { contentType, body, isRequest -> body.replace(Regex("\"password\":\"[^\"]*\""), "\"password\":\"***\"") }

    bubble { enabled = true }
    toast {
        filter = ToastFilter.ErrorsOnly
        durationMillis = 2_500
        batchWindowMillis = 400
    }
}
```

## Sessions

```kotlin
// One request
client.get("https://api.example.com/cart") { kotlinspectSession("checkout") }

// Everything in a coroutine
withKotlinspectSession("onboarding") {
    api.register()
    api.fetchProfile()
}

// A whole client
HttpClient { install(KotlinspectPlugin) { session = "payments-sdk" } }

// At runtime
Kotlinspect.startSession(name = "Bug 123", tags = setOf("repro"), metadata = mapOf("build" to "1.4.2"))
Kotlinspect.switchSession(id)
Kotlinspect.clearSession(id)
Kotlinspect.clearAll()
```

## Observing calls

```kotlin
Kotlinspect.callCount       // Flow<Int>, current session
Kotlinspect.inFlightCount   // Flow<Int>
Kotlinspect.errorCount      // Flow<Int>
Kotlinspect.latestCall      // Flow<KotlinspectCall?>
Kotlinspect.calls           // Flow<List<KotlinspectCall>>
Kotlinspect.sessions        // Flow<List<KotlinspectSession>>

Kotlinspect.openInspector()
Kotlinspect.setBubbleVisible(false)
```

## Release builds

Kotlinspect is **off in release builds** unless you set `allowInRelease = true`. When it is off, the plugin does nothing, no database file is created, no UI is shown, and every API call is a safe no-op.

| Platform | Counts as release |
|---|---|
| Android | APK without `android:debuggable` |
| iOS | Release framework (`Platform.isDebugBinary == false`) |
| Desktop | Packaged app (jpackage / `packageDistribution`). Override with `-Dkotlinspect.debug=true\|false` |

Use `enabled = false` as a master switch in any build.

## Where data is stored

| Platform | Location |
|---|---|
| Android | The app's `databases/kotlinspect.db` |
| iOS | `Application Support/Kotlinspect/kotlinspect.db` |
| Desktop | `~/.kotlinspect/<desktopAppName or main class>/kotlinspect.db` |

## Project layout

- `kotlinspect-core/`: capture, storage, sessions, the public API and the native iOS UI.
- `kotlinspect/`: the Compose UI on top of core.
- `kotlog/`: the `KtLog` logging API, with no dependencies.
- `Package.swift`, `swift/`: the optional `KotlinspectSwiftUI` Swift package.
- `shared/`, `androidApp/`, `desktopApp/`, `iosApp/`: a sample app with a playground and a Postman-style request builder. It consumes the library from Maven Local, the same way other apps do.

```bash
./gradlew :kotlinspect-core:jvmTest     # tests
./gradlew checkKotlinAbi                # public API check for both modules (updateKotlinAbi after deliberate changes)
./gradlew :kotlog:publishToMavenLocal :kotlinspect-core:publishToMavenLocal :kotlinspect:publishToMavenLocal
./gradlew :androidApp:assembleDebug
./gradlew :desktopApp:run
```

iOS: open `iosApp/` in Xcode on a Mac and run it.
