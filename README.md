# Kotlinspect

A network inspector for Kotlin Multiplatform apps that use Ktor. Add one dependency and every HTTP call shows up in a floating bubble, a toast, and a full inspector screen on **Android, iOS and Desktop**. It is off in release builds by default.

- **Capture**: method, URL, headers, bodies, status, timing and failures, including in-flight and cancelled calls. Large bodies are truncated, binary bodies are skipped, and streaming responses are passed through untouched.
- **Redaction**: `Authorization`, `Proxy-Authorization`, `Cookie` and `Set-Cookie` are hidden by default. You can redact more headers, query parameters or body content.
- **Sessions**: group calls per launch, under a fixed name, or manually, and tag single requests or whole coroutines.
- **Storage**: a private Room database with retention by age, count or size, or memory only.
- **UI**: a draggable bubble (count, in-flight ring, error tint), toasts, and an inspector with search, filters, pretty-printed JSON and *Copy as cURL*.
- **API**: observe counts and calls as `Flow`s to build your own indicators.

## Installation

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
            implementation("io.github.asterx5:kotlinspect:1.0.0-beta01")
        }
    }
}
```

To use a local build in your other projects:

```bash
./gradlew :kotlinspect:publishToMavenLocal
```

Then install the plugin in your client:

```kotlin
val client = HttpClient {
    install(KotlinspectPlugin)
}
```

That's all. On Android, the library's manifest picks up the app context automatically. On iOS and desktop, the bubble appears with the first call.

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

- `kotlinspect/`: the library.
- `shared/`, `androidApp/`, `desktopApp/`, `iosApp/`: a sample app with a playground and a Postman-style request builder. It consumes the library from Maven Local, the same way other apps do.

```bash
./gradlew :kotlinspect:jvmTest          # tests
./gradlew :kotlinspect:checkKotlinAbi   # public API check (updateKotlinAbi after deliberate changes)
./gradlew :kotlinspect:publishToMavenLocal
./gradlew :androidApp:assembleDebug
./gradlew :desktopApp:run
```

iOS: open `iosApp/` in Xcode on a Mac and run it.
