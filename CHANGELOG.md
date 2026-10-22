# Changelog

All versions are on Maven Central as `io.github.asterx5:kotlinspect:<version>`.

## 1.0.0-beta06

- New **Kotlog** artifact (`io.github.asterx5:kotlog`): a tiny logging API with no dependencies. `KtLog.d(TAG, "message", "datasource")`, also `v`, `i`, `w`, `e`, `wtf` and `log(LogLevel, ...)`; layers via `LogLayer.Datasource.value` (Repository, Usecase, ViewModel, UI) or any string. Entries print the platform way (Android Log, NSLog, console) and, with Kotlinspect in the app, are stored for the inspector with no setup. `kotlinspect` and `kotlinspect-core` include it.
- Inspector **Logs** tab (Compose UI: Android, desktop, Compose Multiplatform iOS): search by message, multi-select filters for tags, layers and levels, showing only values present in the session; tap an entry to expand it with its stack trace and copy it.
- Bubble shows a small log count under the call count (`bubble { showLogCount = false }` to hide). `Kotlinspect.logCount` flow.
- `logs { enabled; maxCount }` options; logs are capped at 5,000 by default and cleared with their session.
- Database version 2 with a migration that keeps existing calls and sessions.
- Not yet in the native iOS UI: the UIKit inspector has no Logs tab and the UIKit bubble no log count. On iOS, KtLog still prints and stores entries.

## 1.0.0-beta05

- New `kotlinspect-core` artifact: capture, storage, sessions, the API and a native UIKit bubble, toast and inspector, with no Compose dependency. SwiftUI and UIKit apps can use Kotlinspect without Compose. `kotlinspect` now adds the Compose UI on top of it; existing users change nothing.
- Place the bubble yourself: `KotlinspectOverlay(sessionId)` in Compose, `KotlinspectUIKit.shared.makeOverlayView(sessionId:)` in UIKit, and the `KotlinspectSwiftUI` Swift package for SwiftUI. A placed overlay hides the automatic bubble while shown; with a session id it counts, toasts and opens only that session.
- `bubble { automatic = false }` to show only placed overlays.
- `Kotlinspect.openInspector(sessionId)` opens on a given session.
- Depends on the oldest compatible versions instead of the newest: Kotlin 2.1.20, Ktor 3.0, Compose 1.7, coroutines 1.9, serialization 1.6.3, Room 2.7, compileSdk 34. Navigation 3 and kotlinx-datetime are no longer dependencies.
- Fixed a crash on Ktor 3.0 for URLs with a "/" path, and an inspector failure can no longer fail the app's request.

## 1.0.0-beta04

- iOS: the inspector opens as a standard sheet (swipe down to close). Fixes the inspector appearing and then vanishing until the app was sent to the background.
- iOS: if the inspector cannot be shown, the bubble comes back instead of staying hidden.
- Both inspectors: status filters on one row, with a method filter row underneath that lists only the methods present in the session.

## 1.0.0-beta03

- New inspector design: custom theme instead of stock Material 3, call list with live stats, filter chips with counts, method badges and per-row timing bars.
- Detail screen with status header, highlighted URL and query table, send/wait/receive timing, header tables and JSON syntax highlighting with line numbers (including newline-delimited JSON).
- Much faster inspector: the list no longer loads request and response bodies, the bubble uses a single query, and bodies are formatted off the main thread and rendered lazily.
- iOS 14+: native UIKit inspector (navigation bar, search, status filter, sessions menu, copy menu). Older iOS versions use the Compose inspector.
- The library no longer depends on Material 3.

## 1.0.0-beta02

- iOS: the inspector opens in its own window, so tapping the bubble works reliably in SwiftUI apps and after closing the inspector.
- iOS: the bubble follows the finger, stays inside the safe area and snaps to the nearest edge.

## 1.0.0-beta01

- First release: Ktor plugin capturing calls on Android, iOS and desktop; sessions; retention; redaction; bubble, toast and inspector; public Flow API; off in release builds by default.
