---
name: kotlinspect
description: Add the Kotlinspect network inspector (io.github.asterx5:kotlinspect) to a Kotlin Multiplatform, Android, iOS or desktop project that uses the Ktor HTTP client, so every request shows up in an in-app bubble, toasts and an inspector screen in debug builds. Use this whenever the user wants to inspect, debug, log or see network calls / HTTP traffic / API requests in a KMP or Ktor app, mentions Kotlinspect, Chucker-like or Pulse-like tools for KMP, a network bubble or overlay, or asks to "see what my app is calling", even if they don't name the library.
---

# Adding Kotlinspect

Kotlinspect is a Ktor client plugin plus UI. It records every call made through an `HttpClient`
it is installed in, stores them in a private database, and shows a draggable bubble, a toast per
call, and an inspector (search, filters, headers, pretty JSON, copy as cURL). It is off in release
builds by default, so it is safe to leave in production code.

Before changing anything, look at the project so the setup fits it. The steps below are short;
most of the work is choosing the right artifact and putting the dependency in the right module.

## 1. Inspect the project

Find out:

- **Where `HttpClient` is created.** Kotlinspect only captures Ktor. If the app uses Retrofit or
  OkHttp directly with no Ktor client, tell the user it is Ktor-only today and stop; don't swap
  their networking stack.
- **Which module owns it.** Usually the shared KMP module (`shared/`, `composeApp/`, `core/network`).
  The dependency goes in that module's `commonMain`, not in the Android app module.
- **What UI the apps use.** Look for Compose Multiplatform or Jetpack Compose (`org.jetbrains.compose`
  plugin, `@Composable`) versus a SwiftUI/UIKit iOS app (Swift views in `iosApp/` and no
  `ComposeUIViewController`).
- **Versions.** Kotlinspect needs Kotlin 2.1.20+, Ktor 3.0+, Compose 1.7+ (if Compose is used),
  Android minSdk 24 and compileSdk 34+. If the project is below these, say which one and ask
  before upgrading anything, because Gradle would otherwise pull the newer versions in silently.

## 2. Pick the artifact

| The project | Add |
|---|---|
| Uses Compose anywhere (Android, desktop, Compose Multiplatform iOS) | `io.github.asterx5:kotlinspect` |
| iOS UI is SwiftUI or UIKit, or only capture is wanted | `io.github.asterx5:kotlinspect-core` (no Compose) |

`kotlinspect` already includes `kotlinspect-core`; never add both. Use the latest version from
https://central.sonatype.com/artifact/io.github.asterx5/kotlinspect or the badge in the GitHub
README (https://github.com/asterx5/kotlinspect). The examples below use `<version>`.

## 3. Add the dependency

Make sure `mavenCentral()` is in `dependencyResolutionManagement.repositories` (it almost always is).
If the project uses a version catalog, add it there to match the project's style:

```toml
[versions]
kotlinspect = "<version>"

[libraries]
kotlinspect = { module = "io.github.asterx5:kotlinspect", version.ref = "kotlinspect" }
```

Then in the module that creates the client:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinspect)
        }
    }
}
```

For an Android-only project, put it in the app module's `dependencies { implementation(...) }`.

## 4. Install the plugin

Add one line where the client is built. Keep the existing configuration as it is:

```kotlin
import io.github.asterx5.kotlinspect.KotlinspectPlugin

val client = HttpClient(engine) {
    install(KotlinspectPlugin)
    // ...existing plugins
}
```

If the app builds several clients (for example one per API), install it in each one that should
be inspected. That is the whole setup: on Android the library's manifest captures the app context
by itself, and the bubble appears automatically on Android, iOS and desktop. There is no
`init` call to add.

## 5. Optional configuration

Only add configuration the user asked for or clearly needs. Call it early (Android
`Application.onCreate`, desktop `main`, iOS app init), before the first request:

```kotlin
Kotlinspect.configure {
    redactHeaders("X-Api-Key")                   // Authorization and cookies are already redacted
    redactQueryParameters("token")
    retention = RetentionPolicy.MaxCount(1_000)  // or MaxAge(3.days), Forever, InMemoryOnly
    toast { filter = ToastFilter.ErrorsOnly }
    // allowInRelease = true                     // only if the user explicitly wants it in release
}
```

Group calls into sessions when the user wants to separate flows:

```kotlin
client.get(url) { kotlinspectSession("checkout") }   // one request
withKotlinspectSession("onboarding") { api.load() }    // everything in a coroutine
HttpClient { install(KotlinspectPlugin) { session = "payments" } }  // a whole client
```

## 6. Placing the bubble in the UI (optional)

By default the bubble floats over every screen. If the user wants it only in part of the UI, or
scoped to one session, place it explicitly. While a placed overlay is on screen the automatic one
hides; `bubble { automatic = false }` in `configure` turns the automatic one off entirely.

Compose (put it last in the root container so it draws on top):

```kotlin
Box(Modifier.fillMaxSize()) {
    AppContent()
    KotlinspectOverlay()                 // or KotlinspectOverlay(sessionId = id)
}
```

For a SwiftUI or UIKit iOS app, read `references/ios-native.md`: it needs a framework `export`
line and then either the `KotlinspectSwiftUI` Swift package or a small wrapper.

## 7. Verify

- Run a Gradle sync and build the module (for example `./gradlew :shared:compileKotlinMetadata`
  or the Android `assembleDebug`). Fix imports if the build reports unresolved Kotlinspect symbols.
- Tell the user to run a **debug** build and make one request: the bubble count should go up and
  a toast should appear. Tapping the bubble opens the inspector.
- If nothing appears: confirm the request goes through the client the plugin is installed in,
  that it is a debug build, and (iOS without Compose) that the framework exports kotlinspect-core.

## Things to avoid

- Don't add the engine for them unless it's missing; Kotlinspect works with whatever engine the
  client already uses (OkHttp, Darwin, CIO, Java...).
- Don't wrap the plugin in `if (BuildConfig.DEBUG)`; release gating is built in and keeps a
  single code path.
- Don't move the dependency to `androidMain`/`iosMain` only; it belongs where the client lives.
- Don't change the project's Kotlin, Compose or Ktor versions without asking.
