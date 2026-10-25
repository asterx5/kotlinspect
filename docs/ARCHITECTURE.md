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
