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
