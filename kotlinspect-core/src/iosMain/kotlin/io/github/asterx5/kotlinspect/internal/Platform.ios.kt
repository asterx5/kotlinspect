@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package io.github.asterx5.kotlinspect.internal

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.asterx5.kotlinspect.internal.db.KotlinspectDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
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

    actual val isDebugBuild: Boolean
        get() = kotlin.native.Platform.isDebugBinary

    actual fun databaseBuilder(inMemory: Boolean, config: ResolvedConfig): RoomDatabase.Builder<KotlinspectDatabase> =
        if (inMemory) {
            Room.inMemoryDatabaseBuilder<KotlinspectDatabase>()
        } else {
            Room.databaseBuilder<KotlinspectDatabase>(name = databasePath())
        }

    private fun databasePath(): String {
        val manager = NSFileManager.defaultManager
        val base = manager.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)?.path
            ?: error("Application Support directory unavailable")
        val dir = "$base/Kotlinspect"
        manager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return "$dir/${KotlinspectDatabase.FILE_NAME}"
    }

    actual fun sqliteDriver(): SQLiteDriver = BundledSQLiteDriver()

    actual fun onRuntimeStarted(host: KotlinspectHost) {
        dispatch_async(dispatch_get_main_queue()) { IosUi.installAutomaticOverlay() }
    }

    actual fun openInspector(host: KotlinspectHost, sessionId: String?) {
        dispatch_async(dispatch_get_main_queue()) { IosUi.presentInspector(host, sessionId) }
    }
}

/**
 * Native UIKit UI. The automatic overlay is a pass-through window above the app holding the same
 * OverlayView apps can place themselves. The inspector is presented as a standard sheet on a
 * transparent host in its own window, so it never depends on the app's view controllers.
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

    fun installAutomaticOverlay() {
        if (overlayWindow != null) return
        whenSceneReady { scene ->
            if (overlayWindow != null) return@whenSceneReady
            val root = UIViewController(nibName = null, bundle = null)
            root.view.backgroundColor = UIColor.clearColor
            val overlay = OverlayView(sessionId = null, embedded = false)
            overlay.setFrame(root.view.bounds)
            root.view.addSubview(overlay)
            val window = PassthroughWindow(scene).apply {
                setFrame(scene.coordinateSpace.bounds)
                windowLevel = UIWindowLevelAlert + 1
                backgroundColor = UIColor.clearColor
                rootViewController = root
                hidden = false
            }
            overlayWindow = window
        }
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

    /** Handles the user swiping the sheet down. */
    private val dismissDelegate = object : NSObject(), UIAdaptivePresentationControllerDelegateProtocol {
        override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
            teardown()
        }
    }
}
