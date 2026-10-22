@file:OptIn(ExperimentalObjCName::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.internal.OverlayView
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIColor
import platform.UIKit.UIView
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Kotlinspect's native iOS UI for UIKit and SwiftUI apps. No Compose involved.
 *
 * From Swift (the framework must export kotlinspect-core):
 * ```swift
 * let overlay = KotlinspectUIKit.shared.makeOverlayView(sessionId: nil)
 * overlay.frame = view.bounds
 * view.addSubview(overlay)
 * ```
 * The Objective-C name is fixed to `KotlinspectUIKit` regardless of the framework name, so the
 * KotlinspectSwiftUI package can find it.
 */
@ObjCName("KotlinspectUIKit", exact = true)
public object KotlinspectUIKit {
    /**
     * A transparent view with the bubble and toasts. Place it over your root view, sized to fill
     * it; touches outside the bubble pass through. While it is on screen the automatic bubble
     * hides. With [sessionId], it shows and opens only that session; with null, the current one.
     * Returns an empty pass-through view when Kotlinspect is disabled, such as in release builds.
     */
    public fun makeOverlayView(sessionId: String?): UIView {
        if (!Kotlinspect.isEnabled) {
            return UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)).apply {
                backgroundColor = UIColor.clearColor
                userInteractionEnabled = false
            }
        }
        return OverlayView(sessionId = sessionId, embedded = true)
    }

    /** Opens the inspector on [sessionId], or on the current session when null. */
    public fun openInspector(sessionId: String?) {
        Kotlinspect.openInspector(sessionId)
    }
}
