# Kotlinspect in SwiftUI or UIKit iOS apps

Use this when the iOS app's UI is written in Swift (SwiftUI or UIKit) rather than Compose
Multiplatform. Kotlinspect's iOS bubble, toast and inspector are native UIKit, so the app does not
need Compose.

## 1. Depend on kotlinspect-core and export it

Swift only sees Kotlin classes that the app's framework exports. In the shared module that builds
the iOS framework, change the dependency to `api` and add an `export` for the same coordinates:

```kotlin
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"   // keep the project's existing name
            export("io.github.asterx5:kotlinspect-core:<version>")
        }
    }
    sourceSets {
        commonMain.dependencies {
            api("io.github.asterx5:kotlinspect-core:<version>")
        }
    }
}
```

If the project already has a `framework { }` block, add the `export` line inside it rather than
creating a second block. With only this step the automatic bubble already works on iOS; the rest
is for placing it yourself.

After changing the framework, the user should do **Product → Clean Build Folder** in Xcode so it
picks up the new Kotlin framework.

## 2. Place the overlay (optional)

### SwiftUI with the Swift package

In Xcode: File → Add Package Dependencies → `https://github.com/asterx5/kotlinspect`, choose the
same version as the Gradle dependency, add the `KotlinspectSwiftUI` product to the app target.

```swift
import KotlinspectSwiftUI

ContentView()
    .overlay(KotlinspectOverlay())                // current session
    // .overlay(KotlinspectOverlay(sessionId: id)) // only one session
```

`KotlinspectInspector.open()` opens the inspector from a debug menu or button.

### SwiftUI without the package

Add this to the app (replace `Shared` with the framework's module name if Swift needs the import):

```swift
import SwiftUI
import Shared

struct KotlinspectOverlay: UIViewRepresentable {
    var sessionId: String? = nil
    func makeUIView(context: Context) -> UIView {
        KotlinspectUIKit.shared.makeOverlayView(sessionId: sessionId)
    }
    func updateUIView(_ uiView: UIView, context: Context) {}
}
```

### UIKit

```swift
let overlay = KotlinspectUIKit.shared.makeOverlayView(sessionId: nil)
overlay.frame = view.bounds
overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
view.addSubview(overlay)
```

Add it to the root view controller's view so it covers every screen. Touches outside the bubble
pass through to the app.

`KotlinspectUIKit.shared.openInspector(sessionId: nil)` opens the inspector.

## Notes

- The native UI needs iOS 14 or newer.
- In release builds (non-debug Kotlin framework) the overlay view is an empty, non-interactive
  view, so it is safe to leave in place.
- If the Swift package shows nothing, the framework is almost always missing the `export` line.
