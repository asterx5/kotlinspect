# Changelog

All versions are on Maven Central as `io.github.asterx5:kotlinspect:<version>`.

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
