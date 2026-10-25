# Kotlinspect architecture notes

This file records how the spec *Kotlinspect — Spec: OkHttp/Retrofit, HAR, Web Targets, iOS Swift
Package* (2026-10-25) maps onto the existing code, the decisions taken while implementing it, and
every deviation from the spec with its reason. The overall picture of the project lives in
[`PROJECT_STATE.md`](PROJECT_STATE.md).

Work happens on the `vnext` branch; the starting point is tagged `pre-vnext`. The branch builds
version `1.0.0-SNAPSHOT` so local publishing never overwrites a released version in Maven Local.

## Owner decisions (2026-10-25)

| Question | Decision |
|---|---|
| Proposed idea: Retrofit method names as call titles | **Not kept.** `operationName` still exists in core (ingestion, storage, UI), but the OkHttp interceptor does not read Retrofit's `Invocation`, and Retrofit is not a dependency. |
| Proposed idea: source badge on list rows | **Kept.** |
| Proposed idea: `_kotlinspect` HAR fields | **Not kept.** HAR export writes standard HAR 1.2 only (plus `_error`, which Chrome uses). |
| Proposed idea: web console mirror | **Not kept.** Web is a pure no-op. |
| Query-string redaction defaults | Stay opt-in (`redactQueryParameters`); no new defaults, so existing apps see no change. |
| `androidx.core` for the FileProvider | Not added. A small library-owned `ContentProvider` serves only `cache/kotlinspect/exports/`. |
| vNEXT number | Not chosen yet. The changelog section is `## Unreleased`; rename it to the version when releasing (the release workflow reads notes by exact version heading). |
| Web floor gap | None: Compose 1.7.0, Ktor 3.0.0, coroutines 1.9.0 and serialization 1.6.3 all publish `js` and `wasmJs`. |
| `iosX64` | Not published today, so it is not added (spec rule: use exactly the published iOS targets). |
| Publishing | Nothing is published to Maven Central before the Central grace period ends (November 2026). |

## Decisions log

### Pre-phase: `KtLog` renamed to `Kotlog`

- The public object `KtLog` is now `Kotlog`, with identical members (`consoleEnabled`, `v`, `d`,
  `i`, `w`, `e`, `wtf`, `log`). No deprecated alias (owner decision).
- **Deviation:** the internal SPI types `KtLogEntry`, `KtLogSink` and `KtLogSinks` were renamed to
  `KotlogEntry`, `KotlogSink` and `KotlogSinks` too, along with the `META-INF/services` files.
  The spec asks both for "ABI diff shows only KtLog → Kotlog" and for "searching the repository
  for KtLog finds it only in the README notice and the changelog"; the second is impossible
  without renaming these. They are `@InternalKotlogApi` (opt-in, not for apps), their members are
  identical, and the ABI diff is otherwise a pure rename.
- No collision: the module, artifact and package are lowercase `kotlog`; nothing was declared
  `Kotlog`.

### Phase 0: core prerequisites

**How the spec's names map onto the existing code** ("existing code wins"):

| Spec | Existing code |
|---|---|
| `source` column, default `"ktor"` | `RecordEntity.source` already existed; Ktor stored `"http"`. Database version 3 renames it to `"ktor"` in `Migration2To3.onPostMigrate`, and `RecordSources.KTOR` is the one constant the Ktor capture uses. The plugin's logic is unchanged. |
| `httpVersion` | The existing `protocol` column. It stores the client's own spelling ("HTTP/1.1" from Ktor, "h2" from OkHttp); HAR export normalizes it. |
| Timing fields | New nullable `timingBlocked`, `timingDns`, `timingConnect`, `timingSsl`, `timingSend`, `timingWait`, `timingReceive` (ms). Ktor rows leave them null and keep deriving send/wait/receive from `requestSentAt`/`responseStartedAt`/`completedAt`. All seven are columns because the timestamps cannot express DNS or connect time, and measured values map 1:1 to HAR. |
| `operationName`, `serverIpAddress` | New nullable columns. |
| `notCapturedReason` | The existing body note columns, as "Not captured: <reason>". |
| Record source SPI | The internal `RecordSink`. The ingestion path (`IngestCapture`) runs on the runtime's op loop through `beginInLoop`/`updateInLoop`/`finishInLoop`. |
| Interrupted records | Already handled: `initialize()` marks pending records from earlier runs as cancelled ("Interrupted: …"). |

**Decisions**
- `KotlinspectIngest.begin(sessionId = …)` accepts a session id *or* a session name: ids come from `startSession` (and the Swift package); names match how Ktor's `kotlinspectSession("checkout")` and the plugin's `session` work. An existing id wins, otherwise the name is found or created.
- Disabled is free: `KotlinspectIngest.isEnabled` reads the config builder without allocating, and `begin` returns the shared `IngestCapture.Noop`.
- Handle calls copy their arguments, take the call time, and queue the rest on the op loop, so they never block, apply in order, and are ignored after the first terminal call. Failures are logged once through Kotlog and swallowed.
- Nested captures: the runtime keeps an in-memory index of in-flight records from outer sources (`ktor`, `alamofire`) keyed by method and a normalized URL (default ports and an empty path don't matter). An inner capture (`okhttp`, `urlsession`) that matches an unclaimed outer record started no later than it becomes a shadow: never stored, and its protocol, server address, operation name and timings fill the outer record's empty fields when it ends, including after the outer record has finished. Ktor registers as an outer source inside the runtime, so the plugin is untouched. `dedupeNestedCaptures = false` stores both.
- UI (Compose and UIKit): rows show the operation name as the title and the path on the second line; a source badge appears only when the session mixes sources (the one proposed idea kept); the detail shows Source, Protocol, Server and per-phase timings. Shared logic lives in `uimodel` (`sourceLabel`, `hasMixedSources`, `title`, `timingPhases`, `KsPalette.phaseColor`). Ktor-only sessions look exactly as before.
- `RecordEntity` and `RecordSummary` gained fields. They are `@InternalKotlinspectApi` in the `internal` package, which the ABI dumps exclude; `RecordSummary`'s new fields have defaults so existing code that builds it keeps compiling.

**Acceptance:** 93 tests green, including 8 ingestion, 6 nested-capture, 1 v2→v3 migration and 4 UI-model tests; existing tests unedited. ABI diff: only the `@ExperimentalKotlinspectApi` ingestion types and `dedupeNestedCaptures`. Maven Local diff: none. The ingestion tests run on the JVM; running them on an iOS simulator needs the macOS CI of Phase 4.

### Phase 1: OkHttp

- **Placement:** the preferred option worked. The code lives in kotlinspect-core in a
  `jvmAndAndroidMain` source set (package `io.github.asterx5.kotlinspect.okhttp`), created by
  extending the default hierarchy template, so `iosMain`/`appleMain` are untouched. No new
  artifact, so no extra Maven Central files.
- **Dependencies:** `compileOnly(com.squareup.okhttp3:okhttp:4.9.0)` only. The Maven Local diff is
  empty: OkHttp appears in no POM or Gradle module file. Retrofit is not a dependency (the Retrofit
  method-name idea was not kept); Retrofit calls are captured because they run on OkHttp.
- **API:** `KotlinspectInterceptor(sessionId)` with `@JvmOverloads` (Java: `new
  KotlinspectInterceptor()`, `new KotlinspectInterceptor("checkout")`),
  `OkHttpClient.Builder.kotlinspect(sessionId)`, `Request.Builder.kotlinspectSession(id)`, and the
  optional `KotlinspectEventListenerFactory(delegate)` (1.4). Stable API, not experimental.
  `sessionId` accepts an id or a name, like the Ktor plugin.
- **Read-ahead:** response bodies with `peekBody(maxBodyBytes)`; the size is the declared length,
  or the read length when the body ended within the limit, else unknown. `gzip` that the app asked
  for is decoded from the peeked bytes (a body cut by the limit decodes partially); a complete body
  that fails to decode is "Not captured: gzip"; other encodings are "Not captured: encoding <name>".
- **Timings:** without the listener, wait = `receivedResponseAtMillis - sentRequestAtMillis`. The
  listener stores phase timestamps per `Call` in a weak map. Receive usually ends after the
  interceptor returns (when the app reads the body), so it is added to the stored record later
  through `IngestCapture.addLateTimings` and `KotlinspectRuntime.fillRecord`. The body is read
  ahead before the timing snapshot, since reading a small body to its end is itself a timing event.
- **Deviation:** OkHttp allows one event listener factory, so ours wraps the app's. Only callbacks
  that exist in OkHttp 4.9 (the compile floor) can be forwarded; newer ones (5.x adds a few) do not
  reach the delegate. Documented in the KDoc and README.
- **R8:** kotlinspect-core now publishes consumer rules (`-dontwarn okhttp3.**`, `-dontwarn okio.**`).
  A scratch app with no OkHttp built a minified release both with and without them (R8 drops the
  unreachable classes), so they are a safety net for apps whose own rules keep Kotlinspect's
  classes, which would make the OkHttp references reachable.
- **Tests:** `OkHttpInterceptorTest` (15, MockWebServer, JVM) covers the 1.7 list except the two
  Retrofit-name cases. The version matrix runs with `-PokhttpTestVersion=4.9.3`, `4.12.1` (default)
  and `5.5.1`; the build forces that version onto the test classpath, because Ktor's OkHttp engine
  would otherwise pull 4.12 into the 4.9 run. All 15 pass on all three, including Ktor 3.0's
  engine on 4.9.3.
- **Sample:** a Retrofit tab (Android and desktop) with GET, POST, 404 and a 2-second timeout,
  through Retrofit 3.0 on an OkHttp client with the interceptor and the listener. Checked on the
  Android emulator: the calls appear with an OkHttp badge next to a Ktor call, and the detail
  shows Blocked/DNS/Connect/TLS/Send/Wait/Receive, protocol h2 and the server address.

### Phase 2: HAR export

- **API:** `HarExportOptions(includeBodies, includeFailed, includePending)`, `HarExportResult`
  (`Success(filePath, entryCount, byteSize)` / `Failure(reason)`), `Kotlinspect.exportHar(sessionId)`
  and `exportHar(recordIds)`, all `@ExperimentalKotlinspectApi`. `includeKotlinspectFields` is not
  there, since the `_kotlinspect` idea was not kept; only Chrome's `_error` is written.
- **Writer:** a small hand-rolled streaming JSON writer (`HarJson`), compact, UTF-8 without BOM;
  quote, backslash and characters below U+0020 escaped (the latter as `\u00XX`). kotlinx
  serialization is a core dependency, but its escaping writes `\n` style escapes, and the spec asks
  for `\u00XX`, so it is not used for output. Session records are read with keyset paging
  (`startedAt, id`), 200 at a time. `pages` is written after `entries` (JSON key order is free), so
  each page's start time is known without a second pass.
- **Mapping:** as spec 2.3. HTTP versions: `h2`/`HTTP/2.0` map to `HTTP/2.0`, `h3`/`quic` to
  `HTTP/3.0`, unknown to `HTTP/1.1`. Cookies from `Cookie` headers (none when redacted), and
  best-effort `Set-Cookie` parsing with `Expires` turned into ISO 8601. Status is 0 and statusText ""
  for failed, cancelled and pending entries. `_error` is the error for failed calls, "cancelled"
  or "interrupted" for cancelled ones.
- **Timings:** as spec 2.4, shared by every entry (`HarEntries.timings`); a 2,000-case random test
  checks `time` always equals the sum of the phases and that no phase is negative.
- **Files:** `HarFiles` expect/actual: Android `cacheDir`, iOS `NSTemporaryDirectory()` (POSIX
  `fopen`/`fwrite`), desktop `java.io.tmpdir`, each under `kotlinspect/exports`. `.har` files older
  than 24 hours are deleted before every export. The library version for `creator` comes from a
  generated `BuildInfo.VERSION`.
- **Android delivery:** `KotlinspectFileProvider`, a small library-owned `ContentProvider` (owner
  decision: no androidx.core). Authority `${applicationId}.kotlinspect.fileprovider`, not exported,
  `grantUriPermissions`, read-only, and only files directly inside the export folder (canonical
  path check). The share intent carries the URI as `EXTRA_STREAM` and `ClipData` with a read grant.
  Checked on the emulator: the chooser shows the file name, which it reads through the provider.
- **iOS delivery:** `UIActivityViewController` presented over the inspector, anchored for iPad.
  **Deviation:** the UIKit sheet is an action sheet with *Include bodies* / *Without bodies*
  buttons, since `UIAlertController` cannot hold a switch; the Compose sheet has the switch.
- **Desktop delivery:** an AWT save dialog with the default file name; the export writes straight
  to the chosen path.
- **Progress:** exports with more than 200 entries show "Exporting n of total"; the result is
  confirmed with a short message (Compose) or the share sheet itself (iOS).
- **Tests:** `HarExportTest` (10): byte-identical golden file (`src/jvmTest/resources/har/golden.har`,
  creator version normalized), every required HAR 1.2 field with the right type, timing invariant,
  escaping (quotes, backslashes, control characters, emoji, RTL), options, redaction (no raw secret
  anywhere in the file), 10,000 records read in 51 pages, 24-hour cleanup and file names, single-call
  export, and failures for disabled and unknown sessions.
- **Verified manually:** Android export from the inspector on the emulator; the pulled file parses
  as HAR 1.2 with bodies and the Authorization header still redacted. **Not verified:** the desktop
  save dialog, the iOS share sheet (needs a Mac), and opening a file in Chrome DevTools (the owner's
  manual check).

### Phase 3: web targets (skipped for this release)

- **Done and kept:** the structural move (commit "Move the Room database into persistentMain").
  The Room database class, its constructor and migration spec, and the per-platform builders
  (`PlatformStorage`) live in `persistentMain` (Android, JVM, iOS); the runtime depends on the
  `KotlinspectStore` interface. **Deviation:** DAOs and entities stayed in `commonMain`, because
  they only use Room's annotations and `room-common` 2.7.0 publishes `js` and `wasmJs` variants;
  so the Compose UI and core keep sharing one record model. Maven Local diff: empty.
- **Blocked:** adding `js`/`wasmJs` failed with "The Kotlin/JS standard library has the ABI
  version (1.201.0) that is not compatible with the compiler's current ABI compatibility level
  (2.4)". The library builds with Kotlin 2.4.20 but pins the 2.1.20 standard library
  (`coreLibrariesVersion`); JS and Wasm only accept the compiler's own standard library, so web
  support would mean apps targeting web need Kotlin 2.4 or newer. Per the spec's floor rule this
  was reported, and the owner chose to **skip web for now**. The web work (targets, no-op Platform,
  console notice, `ioDispatcher`, web actuals for every module) is parked on branch `vnext-web`.
- **Found while investigating:** the published iOS klibs (beta12 included) have
  `abi_version=2.4.0` and `compiler_version=2.4.20` in their manifest. Kotlin/Native compilers before
  2.4 refuse klibs with a newer ABI version, so iOS apps already need Kotlin 2.4+; the "2.1.20"
  floor holds for Android and desktop only. Owner decision: document it now (README, changelog,
  project state) and fix later, by building the iOS klibs with an older toolchain. `-Xklib-abi-version`
  alone is reported not to work (it changes the manifest but linking fails on older compilers).
