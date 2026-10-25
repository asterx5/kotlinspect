@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package io.github.asterx5.kotlinspect.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.popoverPresentationController
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.UIModalPresentationPageSheet
import platform.UIKit.UIPresentationController
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UISceneDidActivateNotification
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowLevelAlert
import platform.UIKit.UIWindowScene
import platform.UIKit.presentationController
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import kotlin.experimental.ExperimentalNativeApi

internal actual object Platform {
    actual val isReady: Boolean = true

    /**
     * Set by the Swift package from the app's own `#if DEBUG`, since its prebuilt framework is
     * always a release binary. KMP apps never set it and keep using their framework's build type.
     */
    var hostBuildIsDebug: Boolean? = null

    actual val isDebugBuild: Boolean
        get() = hostBuildIsDebug ?: kotlin.native.Platform.isDebugBinary

    actual fun onRuntimeStarted(host: KotlinspectHost) {
        dispatch_async(dispatch_get_main_queue()) { IosUi.updateAutomaticOverlay(host) }
    }

    actual fun onConfigChanged(host: KotlinspectHost) {
        dispatch_async(dispatch_get_main_queue()) { IosUi.updateAutomaticOverlay(host) }
    }

    actual fun openInspector(host: KotlinspectHost, sessionId: String?) {
        dispatch_async(dispatch_get_main_queue()) { IosUi.presentInspector(host, sessionId) }
    }

    actual fun shareFile(path: String) {
        dispatch_async(dispatch_get_main_queue()) { IosUi.share(path) }
    }
}

/**
 * Native UIKit UI. The automatic overlay is a pass-through window above the app holding the same
 * OverlayView apps can place themselves. It only exists while the automatic bubble is wanted: an
 * app that places its own overlays (`bubble { automatic = false }`) never gets a window above it.
 * The inspector is presented as a standard sheet on a transparent host in its own window, so it
 * never depends on the app's view controllers.
 */
internal object IosUi {
    private var overlayWindow: UIWindow? = null
    private var inspector: UIWindow? = null
    private var native: NativeInspector? = null
    private var previousKeyWindow: UIWindow? = null
    private var pendingObserver: Any? = null
    private var activeHost: KotlinspectHost? = null

    private fun activeScene(): UIWindowScene? {
        val scenes = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
        return scenes.firstOrNull { it.activationState == UISceneActivationStateForegroundActive } ?: scenes.firstOrNull()
    }

    private fun whenSceneReady(block: (UIWindowScene) -> Unit) {
        activeScene()?.let { return block(it) }
        if (pendingObserver != null) return
        pendingObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            UISceneDidActivateNotification,
            null,
            NSOperationQueue.mainQueue,
        ) { _ ->
            pendingObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
            pendingObserver = null
            activeScene()?.let(block)
        }
    }

    /** Installs the automatic overlay window when the config wants one, and removes it when not. */
    fun updateAutomaticOverlay(host: KotlinspectHost) {
        val config = host.runtime.config
        if (config.enabled && config.bubbleAutomatic) installAutomaticOverlay() else removeAutomaticOverlay()
    }

    private fun installAutomaticOverlay() {
        if (overlayWindow != null) return
        whenSceneReady { scene ->
            if (overlayWindow != null) return@whenSceneReady
            val root = UIViewController(nibName = null, bundle = null)
            root.view.backgroundColor = UIColor.clearColor
            val overlay = OverlayView(sessionId = null, embedded = false)
            overlay.setFrame(root.view.bounds)
            root.view.addSubview(overlay)
            val window = PassthroughWindow(scene, overlay).apply {
                setFrame(scene.coordinateSpace.bounds)
                windowLevel = UIWindowLevelAlert + 1
                backgroundColor = UIColor.clearColor
                rootViewController = root
                hidden = false
            }
            overlayWindow = window
        }
    }

    private fun removeAutomaticOverlay() {
        val window = overlayWindow ?: return
        overlayWindow = null
        window.hidden = true
        // Detaching the overlay view stops its collectors (see OverlayView.didMoveToWindow).
        window.rootViewController = null
    }

    fun presentInspector(host: KotlinspectHost, sessionId: String?) {
        if (inspector != null) return
        if (!NativeInspector.isSupported()) return
        whenSceneReady { scene ->
            if (inspector != null) return@whenSceneReady
            previousKeyWindow = scene.windows.filterIsInstance<UIWindow>().firstOrNull { it.isKeyWindow() }

            val hostController = UIViewController(nibName = null, bundle = null).apply { view.backgroundColor = UIColor.clearColor }
            val window = UIWindow(windowScene = scene).apply {
                setFrame(scene.coordinateSpace.bounds)
                windowLevel = UIWindowLevelAlert + 2
                backgroundColor = UIColor.clearColor
                rootViewController = hostController
            }
            val content = NativeInspector(host.runtime, sessionId, onClose = ::closeInspector).also { native = it }.root
            content.modalPresentationStyle = UIModalPresentationPageSheet
            content.presentationController?.delegate = dismissDelegate
            activeHost = host
            inspector = window
            overlayWindow?.hidden = true
            // Key window so the search field can receive the keyboard.
            window.makeKeyAndVisible()

            // Present on the next run loop turn: the host is then in the window hierarchy, and we
            // are no longer inside the bubble's touch handling.
            dispatch_async(dispatch_get_main_queue()) {
                hostController.presentViewController(content, animated = true, completion = null)
                // Safety net: if UIKit refused to present, do not leave an empty window behind.
                dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 1_000_000_000L), dispatch_get_main_queue()) {
                    if (inspector === window && hostController.presentedViewController == null) teardown()
                }
            }
        }
    }

    private fun closeInspector() {
        val presented = inspector?.rootViewController?.presentedViewController
        if (presented != null) presented.dismissViewControllerAnimated(true) { teardown() } else teardown()
    }

    private fun teardown() {
        val window = inspector ?: return
        window.hidden = true
        window.rootViewController = null
        native?.dispose()
        native = null
        inspector = null
        activeHost = null
        previousKeyWindow?.makeKeyWindow()
        previousKeyWindow = null
        overlayWindow?.hidden = false
    }

    /**
     * Shows the share sheet for an exported file over whatever is on top: the inspector when it is
     * open, otherwise the app. On iPad the sheet is a popover anchored to the bottom center.
     */
    fun share(path: String) {
        val window = inspector ?: activeScene()?.windows?.filterIsInstance<UIWindow>()?.firstOrNull { it.isKeyWindow() } ?: return
        var top = window.rootViewController ?: return
        while (true) top = top.presentedViewController ?: break
        val sheet = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(path)), applicationActivities = null)
        sheet.popoverPresentationController?.let { popover ->
            popover.sourceView = top.view
            val (w, h) = top.view.bounds.useContents { size.width to size.height }
            popover.sourceRect = CGRectMake(w / 2, h - 1.0, 1.0, 1.0)
        }
        top.presentViewController(sheet, animated = true, completion = null)
    }

    /** Handles the user swiping the sheet down. */
    private val dismissDelegate = object : NSObject(), UIAdaptivePresentationControllerDelegateProtocol {
        override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
            teardown()
        }
    }
}
