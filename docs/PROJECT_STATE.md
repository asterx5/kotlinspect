# Kotlinspect: Project State

*Snapshot as of 2026-10-22, release `1.0.0-beta07`. Written to bring a new collaborator (human or
agent) fully up to speed: what exists, how it is built, why it is built that way, what is
verified, and what comes next.*

---

## 1. In one minute

**Kotlinspect** is a network inspector for **Kotlin Multiplatform** apps that use the **Ktor**
HTTP client, on **Android, iOS and desktop (JVM)**. An app adds one dependency and one line
(`install(KotlinspectPlugin)`); every HTTP call then appears in a draggable **bubble**, a
**toast** per call, and a full **inspector** (search, filters, headers, pretty JSON, copy as cURL).
It is **off in release builds** by default, so it is safe to ship in production code.
It also ships **Kotlog** (`KtLog.d(TAG, "msg", "datasource")`), a tiny logger whose entries print
the platform way and appear in the inspector's **Logs** tab.

- **Repo:** https://github.com/asterx5/kotlinspect (public, Apache 2.0)
- **Maven Central:** `io.github.asterx5:kotlinspect`, `io.github.asterx5:kotlinspect-core` and `io.github.asterx5:kotlog`
- **Latest:** `1.0.0-beta07` (uploaded to Central; the owner clicks Publish, then the `v1.0.0-beta07` tag creates the GitHub release)
- **Owner:** Ahmed (GitHub `asterx5`). Built with Claude Code on Windows.

The goal set by the owner: **become the go-to network inspector for KMP**, usable whether or not
the app uses Compose, with zero setup and zero risk in release builds.

---

## 2. Status at a glance

| Area | State |
|---|---|
| Capture (Ktor plugin) | Done, tested |
| Storage (Room), sessions, retention | Done, tested |
| Compose UI: bubble, toast, inspector (Android, desktop, CMP iOS) | Done, verified on Android emulator |
| Native iOS inspector (UIKit) | Done, ran on the owner's iPhone (beta03); beta04 sheet presentation not yet confirmed |
| Native iOS bubble, toast, overlay view (beta05) | Done, **compiles, never run on a device** |
| Placeable overlays + session scoping | Done, verified on Android |
| Kotlog (`KtLog`) + Logs tab + bubble log count, Compose | Done, tested, verified on Android emulator (beta07) |
| Logs tab + bubble log count, native iOS (UIKit) | Done (beta07), **compiles, never run on a device** |
| `KotlinspectSwiftUI` Swift package | Done, **never compiled** (no Mac available) |
| Minimum-version dependency floors | Done, verified with an old-toolchain Android app |
| Publishing to Maven Central | Working (manual) |
| GitHub releases | Automated by a workflow on tag push |
| AI agent skill | Published as `kotlinspect-skill.zip` on every release |
| CI for tests | **Not yet** |

---

## 3. Releases so far

| Version | Highlights | On Maven Central |
|---|---|---|
| beta01 | First release: capture, sessions, retention, Compose UI everywhere | yes |
| beta02 | iOS: inspector in its own window, bubble drag fix | **no** (Publish was never clicked; superseded) |
| beta03 | Custom design (no Material 3), big speed fix, first native UIKit inspector | yes |
| beta04 | iOS inspector as a sheet (fixed "opens then vanishes"), method filter row | yes |
| beta05 | Split into `kotlinspect-core` + `kotlinspect`, native iOS bubble/toast, placeable overlays with session scoping, Swift package, oldest-compatible dependency floors | yes |
| beta06 | Kotlog (`KtLog`, new `kotlog` artifact), Compose Logs tab with tag/layer/level filters, bubble log count, database v2 migration | yes |
| beta07 | Native iOS Logs tab and bubble log count (parity with Compose), sample KtLog demo, Kotlog docs and skill | uploaded, Publish pending |

Detailed notes per version live in [`CHANGELOG.md`](../CHANGELOG.md).

---

## 4. Mindset and principles

These shaped almost every decision. Keep them when extending the library.

1. **Zero setup.** One dependency, one `install(...)` line. No `init` call. Android picks up its
   context through a manifest-merged `ContentProvider`; UI modules register themselves through
   `ServiceLoader`. Anything that needs a setup step is a regression.
2. **Zero risk in release.** Off unless it is a debug build (or `allowInRelease = true`). When off:
   the plugin is a no-op, no database file is created, no UI appears, every API call is safe.
3. **Never break the host app.** All capture code is wrapped (`inspectorSafe { }` in the plugin):
   if Kotlinspect fails, the call goes unrecorded and the app's request proceeds untouched.
   Streaming bodies are teed, never buffered whole.
4. **Don't force upgrades.** Depend on the **oldest** versions that work, not the newest. Gradle
   picks the highest requested version, so a library that asks for "latest" drags every app up.
   Tests run against the floors; the sample app runs against the latest.
5. **Native where it matters.** iOS UI is UIKit (no Compose needed); Android/desktop use Compose.
   One shared data model and palette keep both looking and behaving the same.
   **Parity rule:** every UI feature lands in Compose *and* native iOS in the same release, along
   with the README and the agent skill. beta06 shipped logs without the iOS side and the owner
   asked that this never happen again.
6. **Deliberate public API.** `explicitApi()` and ABI validation in both modules. Cross-module
   internals are public only behind the opt-in `@InternalKotlinspectApi` and live in the
   `internal` package, which ABI dumps exclude.
7. **Verify, then claim.** Things are checked on a real emulator/consumer project where possible,
   and anything unverified is stated as such (see section 12).
8. **Readable, low-jargon communication** with the owner; plain explanations, exact steps when the
   owner has to act (Central Portal, Xcode, Git Bash).

---

## 5. Repository layout

```
kotlinspect/                        repo root
├── kotlinspect-core/               core artifact: capture, storage, sessions, API, native iOS UI
├── kotlinspect/                    Compose artifact: Compose UI on top of core
├── kotlog/                         KtLog logging API, no dependencies
├── shared/ androidApp/ desktopApp/ iosApp/   sample app (Compose Multiplatform)
├── Package.swift  swift/           optional KotlinspectSwiftUI Swift package
├── skills/kotlinspect/             AI agent skill (SKILL.md + references/)
├── scripts/setup-maven-central.sh  saves Central + signing credentials to ~/.gradle
├── .github/workflows/release.yml   creates GitHub releases on v* tags
├── docs/PROJECT_STATE.md           this file
├── CHANGELOG.md  README.md  LICENSE (Apache 2.0)
└── gradle/libs.versions.toml       versions: ks-* = library floors, the rest = sample (latest)
```

---

## 6. Architecture

### 6.1 Modules

```
            app code
               │  install(KotlinspectPlugin)
               ▼
┌──────────────────────────────────────────────┐
│ kotlinspect-core                              │
│  Ktor plugin → Runtime (write queue) → Room   │
│  Sessions · Retention · Redaction · API       │
│  KotlinspectHost (facade for UI modules)      │
│  iOS: native UIKit bubble/toast/inspector     │
└──────────────────────────────────────────────┘
               ▲ ServiceLoader (Android, JVM)
┌──────────────────────────────────────────────┐
│ kotlinspect (Compose)                         │
│  Bubble · Toast · Inspector · KotlinspectOverlay │
│  Android: overlay per Activity + inspector Activity │
│  Desktop: owned dialogs + inspector window    │
└──────────────────────────────────────────────┘
```

- **`kotlinspect-core`** has no Compose dependency. SwiftUI/UIKit iOS apps use it alone.
- **`kotlinspect`** depends on core (`api`), adds Compose UI. It is what most apps add.
- **`kotlog`** (package `io.github.asterx5.kotlog`) has no dependencies. Core depends on it (`api`)
  and registers a `KtLogSink` (ServiceLoader on Android/JVM, `@EagerInitialization` on iOS), so
  `KtLog` entries reach the database with no setup.
- Both share package `io.github.asterx5.kotlinspect`; internals under `...kotlinspect.internal`.

### 6.2 Capture pipeline (`KotlinspectPlugin`, `HttpCapture`)

The plugin hooks Ktor's pipelines:

| Hook | Where | Does |
|---|---|---|
| `SetupRequest` | request pipeline | Picks up the session from the coroutine context (`withKotlinspectSession`) |
| `MonitoringHook` | `sendPipeline` Monitoring phase | Wraps the **whole** exchange (engine + receive pipeline). Creates the record (Pending), catches failures (Failed) and cancellation (Cancelled) |
| `AfterReceiveHook` | `receivePipeline` After phase | Saved bodies (normal calls) are read from Ktor's in-memory copy; streaming bodies get a **tee** channel that passes bytes through and keeps the first N |
| `Send` | `HttpSend` | A rule chain (empty in v1), reserved for mocking/error injection later |

Each redirect hop or retry is recorded separately (each runs the send pipeline).

Body handling: truncated to `maxBodyBytes` (256 KB default) with the full size kept; binary
skipped by content type or by sniffing; a multi-byte character cut by truncation is dropped;
redaction (headers, query params, custom body redactors) is applied before storing.

The streaming tee uses Ktor's `wrapWithContent` (deprecated in favor of `replaceResponse`, which
only exists from Ktor 3.2). It is kept on purpose to support Ktor 3.0/3.1.

### 6.3 Runtime (`KotlinspectRuntime`)

- Created lazily on first use, only when enabled. One per process.
- **All writes go through a single sequential queue** (a coroutine draining a channel). Capture
  submits "begin / update / finish" snapshots without waiting; ordering is guaranteed.
- On start: marks calls left Pending by a killed process as Cancelled ("Interrupted"), drops
  in-memory-only sessions, resolves the current session, applies retention.
- Retention runs again ~1.5 s after calls finish (debounced).

### 6.4 Sessions

- **Policies:** `NewPerLaunch` (default), `Fixed(name)`, `Manual` (last active, across launches).
- **Tagging:** per request `kotlinspectSession("x")`, per coroutine `withKotlinspectSession("x")`
  or `KotlinspectSessionContext`, per client `install(KotlinspectPlugin) { session = "x" }`.
- Sessions carry tags and key-value metadata. Named sessions are created on demand.

### 6.5 Storage (Room, `kotlinspect.db`)

- Tables: `records` (one row per call: generic columns + HTTP columns + HAR-style timing),
  `sessions`, `kv` (active session).
- Driver: Android framework SQLite; bundled SQLite on JVM and iOS.
- Location: Android app `databases/`; iOS `Application Support/Kotlinspect/`; desktop
  `~/.kotlinspect/<desktopAppName or main class>/`. `InMemoryOnly` retention uses no file.
- The inspector list reads a **summary projection** (no headers/bodies); the bubble uses **one
  aggregate query**. This was the main fix for an earlier sluggish inspector.
- Schema exported to `kotlinspect-core/schemas/`. Migrations: destructive fallback (it is debug data).

### 6.6 Retention

`Forever`, `MaxAge(duration)`, `MaxCount(n)` (default 1000), `MaxSize(bytes)`, `InMemoryOnly`.
Global policy plus per-session overrides (`retentionFor(name, policy)` or on `startSession`).
Pending calls are never deleted.

### 6.7 UI layer

**Core ↔ UI contract.** UI modules never touch the database or runtime directly. They use
`KotlinspectHost` (flows of sessions, call summaries, a record, counts; session actions; toast
streams) and implement `KotlinspectUiProvider` (`install`, `onStarted`, `openInspector`).
Both are `@InternalKotlinspectApi`. Core discovers providers with `ServiceLoader` on Android and
JVM (file `META-INF/services/io.github.asterx5.kotlinspect.internal.KotlinspectUiProvider` in the
Compose module). On iOS, core uses its own native UI directly.

**Shared UI model (core, `internal/uimodel`).** Status/method filters, toast batching
(`ToastController`), bubble counts, cURL builder, JSON tokenizer and the `KsPalette` colors are
written once and used by both the Compose and the UIKit UIs.

**Automatic vs placed overlay.**
- Automatic: Android adds a pass-through `ComposeView` to each started Activity's decor view;
  desktop uses transparent dialogs owned by the app's main window; iOS uses a pass-through
  `UIWindow` holding the native `OverlayView`.
- Placed: `KotlinspectOverlay(sessionId)` (Compose) or `KotlinspectUIKit.makeOverlayView(sessionId)`
  (UIKit, also used by the Swift package). A placed overlay registers itself with the host; while
  any is on screen, the automatic one hides. `bubble { automatic = false }` disables it entirely.
- A `sessionId` scopes bubble counts, toasts and which session the inspector opens on.

**Inspector.**
- Compose: list screen (session header, stats strip, search, status chips on one row, method chips
  for methods present, per-row timing bars) → detail (status hero, URL + query table, send/wait/receive
  timing, header tables, JSON highlighting with line numbers rendered lazily). Navigation is a
  small internal two-screen back stack (Navigation 3 was removed; see decisions). Android runs it in
  `KotlinspectActivity`, desktop in its own window.
- iOS: native UIKit (`NativeInspector`): navigation controller, searchable table, status and method
  segmented controls, sessions menu, copy menu, attributed-text detail. Presented as a page sheet
  on a transparent host in its own window (avoids depending on the app's view controllers, which
  broke in SwiftUI apps). iOS 14+.

**Design.** Own palette (ink-dark / paper-light, violet accent), own components on Compose
foundation (no Material 3). Bubble and toast are dark in both themes.

### 6.8 Release gating

`isEnabled = config.enabled && platform ready && (debug build || allowInRelease)`.
Debug build means: Android `FLAG_DEBUGGABLE`; iOS `Platform.isDebugBinary`; desktop "not a
jpackage app" (override with `-Dkotlinspect.debug=true|false`).

### 6.9 Logging (Kotlog)

- `KtLog.v/d/i/w/e/wtf(tag, message, layer?, throwable?)` and `log(LogLevel, ...)`. Prints via
  `android.util.Log`, `NSLog`, or stdout/stderr (`consoleEnabled` turns this off), then hands a
  `KtLogEntry` to registered sinks. Sink failures are swallowed.
- Core's sink forwards to the runtime, which batches entries (50 ms, up to 500) into the `logs`
  table of the **current** session, caps messages at 32,000 chars and trims past `logs.maxCount`
  (5,000). Release builds: printing only, nothing stored.
- Database version 2 adds `logs` with an `AutoMigration(1 → 2)`; `MigrationTest` builds a v1 file
  from the exported schema and checks data survives.
- UI: Compose `LogsScreen.kt` and native `NativeInspector.kt` (Network/Logs switch, Level/Tags/Layers
  menus). Both use the shared `filterLogs`, `presentValues`, `presentLevels` and palette
  `levelColor`. `BubbleCounts.logs` drives the small pill on both bubbles.

### 6.10 Hooks kept for v2 (built, not exposed)

Generic record model (`source`/`kind` columns), `RecordSource`/`RecordSink` SPI, endpoint key
(`GET host/users/{id}`) and fingerprint per call, the rule chain, HAR-friendly timing fields.

---

## 7. Public API (summary)

```kotlin
// Ktor
install(KotlinspectPlugin) { session = "payments"; enabled = true }
client.get(url) { kotlinspectSession("checkout") }
withKotlinspectSession("onboarding") { ... }

// Configuration (call early)
Kotlinspect.configure {
    enabled; allowInRelease; sessionPolicy; retention; maxBodyBytes; desktopAppName
    redactHeaders(...); unredactHeaders(...); redactQueryParameters(...)
    headerRedactor { name, value -> ... }; bodyRedactor { type, body, isRequest -> ... }
    retentionFor("name", policy)
    bubble { enabled; automatic; showLogCount }
    logs { enabled; maxCount }
    toast { enabled; filter; durationMillis; batchWindowMillis }
}

// Sessions and observation
Kotlinspect.startSession(name, tags, metadata, retention); switchSession; clearSession; deleteSession; clearAll
Kotlinspect.addSessionTags(...); putSessionMetadata(k, v)
Kotlinspect.callCount / inFlightCount / errorCount / logCount / latestCall / calls / sessions / currentSession   // Flows
Kotlinspect.openInspector(sessionId); setBubbleVisible(...); isEnabled

// Logging (kotlog)
KtLog.v/d/i(tag, message, layer?); KtLog.w/e/wtf(tag, message, layer?, throwable?)
KtLog.log(LogLevel, tag, message, layer?, throwable?); KtLog.consoleEnabled; LogLayer.X.value

// UI
KotlinspectOverlay(modifier, sessionId)                    // Compose
KotlinspectUIKit.shared.makeOverlayView(sessionId:)        // Swift / UIKit
KotlinspectUIKit.shared.openInspector(sessionId:)
KotlinspectOverlay(sessionId:) / KotlinspectInspector.open() // KotlinspectSwiftUI package
```

Exact signatures are tracked in `kotlinspect-core/api/`, `kotlinspect/api/` and `kotlog/api/`.

---

## 8. Dependency floors

Declared by the library (`ks-*` entries in `libs.versions.toml`). Built with Kotlin 2.4.20 but with
`languageVersion`/`apiVersion` 2.1 and `coreLibrariesVersion = 2.1.20`.

| Dependency | Minimum | Why this floor |
|---|---|---|
| Kotlin (stdlib) | 2.1.20 | uses `kotlin.concurrent.atomics`, `Uuid` |
| Ktor client | 3.0.0 | 3.x plugin and IO APIs |
| Compose (CMP / Jetpack) | 1.7.0 | foundation APIs used by the UI |
| kotlinx-coroutines | 1.9.0 | required by Ktor 3.0 |
| kotlinx-serialization-json | 1.6.3 | JSON element parsing only |
| Room / SQLite bundled | 2.7.0 / 2.5.0 | first stable KMP releases |
| AndroidX Activity (Compose module) | 1.8.2 | `enableEdgeToEdge`, `BackHandler` |
| Android | minSdk 24, compileSdk 34 | dependencies need 34 |
| iOS native UI | iOS 14 | UIKit menus/actions APIs |

Removed on purpose: **Navigation 3** (forced Compose 1.10 + Kotlin 2.2.20 for two screens),
**kotlinx-datetime** (0.7 moved `Instant`, would break apps on 0.6; replaced by platform calendars),
**Material 3**.

---

## 9. Testing and verification

**Automated** (`./gradlew :kotlinspect-core:jvmTest`): **48 tests**, run against the floor versions.

| Suite | Covers |
|---|---|
| CaptureTest | success, request body/headers, network failure, 4xx with `expectSuccess`, pending→cancelled, streaming tee, abandoned stream, binary, disabled plugin |
| RedactionAndTruncationTest | default and custom header/query/body redaction, truncation, multi-byte cut, binary sniffing |
| SessionTest | all three policies, per-request/coroutine/client sessions, start/switch/clear/delete, interrupted calls |
| RetentionTest | max count/age/size, per-session overrides, in-memory sessions, pending never removed |
| DisabledStateTest | release gating, `allowInRelease`, master switch, **no database file created**, safe no-op API |
| OverlayModelTest | session-scoped counts and toasts, embedded overlay hides automatic, `automatic = false` |
| PureLogicTest | endpoint keys, fingerprints, content types, buffers, retention codec, JSON, cURL, toasts, filters |

**ABI:** `./gradlew checkKotlinAbi` for both modules.

**Manual, on the Android emulator:** every call type in the sample, bubble/toast/inspector, filters,
redaction, sessions, release build disabled, scrolling at ~60 fps in a debug build, the old-toolchain
consumer app (Kotlin 2.1.20 / AGP 8.7 / Compose 1.7 / Ktor 3.0) building, resolving to its own
versions and running, and all overlay modes (automatic, in layout, session-scoped, back to automatic).

**Owner-tested on real devices:** Android and desktop (Windows and macOS) work well. iPhone on
beta03: bubble movement confirmed, but the inspector opened and then vanished until the app was
backgrounded. beta04's sheet presentation is the fix for that and has not been confirmed yet.

---

## 10. How to build, release and publish

Environment used: Windows, Android Studio JBR as `JAVA_HOME`, Git Bash. Common commands:

```bash
./gradlew :kotlinspect-core:jvmTest
./gradlew checkKotlinAbi                       # updateKotlinAbi after deliberate API changes
./gradlew :kotlinspect-core:publishToMavenLocal :kotlinspect:publishToMavenLocal
./gradlew :androidApp:assembleDebug            # sample consumes the library from Maven Local
./gradlew :desktopApp:run
```

iOS klibs cross-compile on Windows (`compileKotlinIos*`), which type-checks iOS code; linking a
framework or running iOS needs a Mac.

**Release checklist**
1. Bump `kotlinspect.version` in `gradle.properties` and the sample entry in `libs.versions.toml`;
   update README version mentions and add a `CHANGELOG.md` section with the exact version heading.
2. Run tests, ABI check, publish to Maven Local, build the sample.
3. Upload: `./gradlew :kotlinspect-core:publishToMavenCentral :kotlinspect:publishToMavenCentral --no-configuration-cache`.
   Both modules land in **one** deployment.
4. Owner signs in to https://central.sonatype.com **with GitHub** (that account owns the uploads)
   → Publish → Deployments → Publish. Versions are permanent.
5. Commit, then `git tag -a vX.Y.Z` and push the tag. The release workflow creates the GitHub
   release (notes from the changelog, install lines, skill zip) and marks it Latest.

**Credentials** (never in the repo): Central user token and the GPG signing key live in
`C:\Users\11\.gradle\gradle.properties`, written by `scripts/setup-maven-central.sh`. Signing key
ID `302A4FC3732B5A8A` (public key on keyserver.ubuntu.com). The `io.github.asterx5` namespace is
verified on the GitHub-login Central account.

---

## 11. Decisions log (why things are the way they are)

| Decision | Reason |
|---|---|
| Single sequential write queue | Ordering of begin/update/finish without locks; capture never waits on the database |
| Monitoring phase for lifecycle | Wraps engine and body saving, so failures while reading the body are caught |
| Tee only streaming bodies | Normal responses are already saved in memory by Ktor; streaming must not be buffered or stalled |
| Own design, no Material 3 | Owner wanted a distinctive UI; also fewer dependencies and faster rendering |
| Summary projection + one aggregate query | The list loaded every body on each update; this was the sluggishness |
| Native UIKit on iOS | Compose iOS UI felt non-native; also needed for apps without Compose |
| Inspector in its own window, as a sheet | Presenting on the app's view controllers failed in SwiftUI apps; animating a window's transform left it off screen |
| Split core / Compose artifacts | SwiftUI apps must not pull Compose |
| `KotlinspectHost` facade + `@InternalKotlinspectApi` | UI modules need data, but Room DAOs and the runtime stay internal |
| ServiceLoader for UI providers | Keeps zero setup after the split |
| Oldest dependency floors | Gradle's "highest wins" would otherwise force apps to upgrade; found and fixed real bugs (Ktor 3.0 `Url.segments` crash, compileSdk 37 requirement) |
| Removed Navigation 3 | Two screens did not justify Compose 1.10 + Kotlin 2.2.20 minimums (owner approved) |
| Swift package uses the Objective-C runtime | The package cannot know the app's framework name; `KotlinspectUIKit` has a fixed `@ObjCName(exact = true)` |

---

## 12. Known gaps, risks and gotchas

**Not verified yet (needs a Mac / iPhone):**
- beta05 native iOS bubble, toast and `OverlayView` (drag, tap, pass-through touches, toast animation).
- beta07 native iOS Logs tab (filter menus, self-sizing log rows, log detail) and the bubble log pill.
- Calling `KtLog` from Swift (`KtLog.shared.d(tag:message:layer:)`) with `kotlog` exported.
- The `KotlinspectSwiftUI` package (never compiled). It relies on the selectors
  `shared`, `makeOverlayViewSessionId:`, `openInspectorSessionId:`.
- An iOS app on Kotlin 2.1.x consuming the klibs (Android/JVM floors are verified).
- Desktop overlay after the module split (compiles; not watched running).

**Limitations:**
- Ktor only. Retrofit/OkHttp-only Android apps capture nothing yet.
- Swift needs the app framework to `export` `kotlinspect-core` (and `kotlog` to call `KtLog`).
- Native iOS log filter menus close after each pick (keeping them open needs iOS 16).
- Native iOS UI needs iOS 14.
- No CI: tests and ABI checks run only locally.

**Gotchas met during development:**
- Python on this Windows machine defaults to cp1252; always pass `encoding='utf-8'` in scripts
  that edit sources (it corrupted `·` characters once). Bash heredocs passed through tools can also
  turn `'\n'` literals into real newlines; prefer the editor for such lines.
- The Android emulator (`Medium_Phone_API_36.1`) has ~300 MB free; installs need
  `settings put global sys_storage_threshold_percentage 1` (reset when started with
  `-no-snapshot-save`).
- `jsonplaceholder.typicode.com` often resets connections from the emulator; failures there are the
  network, not the library.
- **Compose stability fields break iOS linking.** When a composable receives a value whose type
  comes from another library (e.g. `ProvidedValue` via `CompositionLocalProvider`,
  `InfiniteTransition`), the Compose compiler reads that type's `$stable` field. Compiled against
  the 1.7 floor it reads the field directly; newer Compose made it private (with a getter), so iOS
  apps on new Compose crash with `IrLinkageError` (beta09 fix). Android/JVM are unaffected. Check
  after UI changes: `klib dump-ir-signatures <kotlinspect iOS klib> | grep '\$stable'` must list only
  `io.github.asterx5` entries.
- **iOS overlay windows must refuse touches in `pointInside`.** A `hitTest` that returns null for
  everything but the bubble still left iPhone apps taking no taps (beta10 fix). The automatic
  window claims only the bubble's area, and is not created at all with `bubble { automatic = false }`.
- Kotlin/Native does not bridge a Kotlin `String` passed as a C vararg (`NSLog("%@", s)` crashed,
  beta08 fix). Pass strings only through typed parameters.
- The Compose Gradle plugin is intentionally **not** applied to the library modules (it warns when
  the library compiles against an older Compose than the plugin version).

---

## 13. Roadmap / next steps

Suggested order:

1. **Verify on iPhone**: the inspector sheet (beta04 fix), native bubble/toast/overlay, the beta07 Logs tab and log pill, Swift package, SwiftUI export setup, `KtLog` from Swift.
2. **CI workflow:** run `jvmTest` and `checkKotlinAbi` on pushes; build iOS klibs on a macOS runner.
3. **Android-only reach:**
   - OkHttp/Retrofit interceptor feeding the same store and UI.
   - `kotlinspect-no-op` artifact for `releaseImplementation`, so the code is not shipped in release APKs.
   - An Android-only test app.
4. **v2 features** (hooks already exist): mocking and error injection (rule chain), HAR export/import
   (timing fields), duplicate-call warnings and session diff (endpoint key + fingerprint),
   WebSocket/GraphQL sources (record SPI).
5. Out-of-scope items from the original v1 scope, still open: Android system notifications,
   shake-to-open, a desktop viewer pulling sessions from devices.

---

## 14. Original v1 scope vs. today

| v1 scope item | Status |
|---|---|
| Ktor plugin on Android/iOS/desktop with details, timing, failures | Done |
| Lifecycle (pending/complete/failed/cancelled) | Done |
| Truncation, binary skipping, safe streaming | Done |
| Default + custom redaction | Done |
| Session policies, runtime start/switch/clear, per-request + coroutine tagging, metadata | Done |
| Isolated Room DB, retention (global + per-session) | Done |
| Bubble (count, in-flight, error tint, draggable) | Done |
| Toast with batching and filter | Done |
| Inspector (session picker, search, status/method filters, tabs, pretty JSON, cURL) | Done |
| "Navigation 3 used internally" | **Changed:** replaced by an internal back stack (dependency floor) |
| Public API with Flows | Done |
| One dependency, runtime gating, Maven Central | Done (now two artifacts: Compose and core) |
| v2 hooks (record model, source SPI, endpoint key, rule chain, HAR timing) | Done, not exposed |
| `explicitApi()`, ABI validation, tests, sample on 3 platforms, README | Done |
| Tag 1.0.0 | **Pending:** still in beta until iOS is verified |

Beyond the original scope: native UIKit iOS UI, `kotlinspect-core` without Compose, placeable and
session-scoped overlays, Swift package, Postman-style request builder in the sample, AI agent
skill, automated GitHub releases.
