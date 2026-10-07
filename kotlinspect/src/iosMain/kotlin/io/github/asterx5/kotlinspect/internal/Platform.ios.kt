@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.asterx5.kotlinspect.internal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.ComposeUIViewController
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.asterx5.kotlinspect.internal.db.KotlinspectDatabase
import io.github.asterx5.kotlinspect.internal.ui.BubbleContent
import io.github.asterx5.kotlinspect.internal.ui.BubbleCounts
import io.github.asterx5.kotlinspect.internal.ui.InspectorApp
import io.github.asterx5.kotlinspect.internal.ui.KotlinspectTheme
import io.github.asterx5.kotlinspect.internal.ui.ToastCard
import io.github.asterx5.kotlinspect.internal.ui.ToastController
import io.github.asterx5.kotlinspect.internal.ui.bubbleCounts
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UISceneDidActivateNotification
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIModalPresentationPageSheet
import platform.UIKit.UIPresentationController
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.presentationController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowLevelAlert
import platform.UIKit.UIWindowScene
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_time
import platform.darwin.dispatch_get_main_queue
import kotlin.experimental.ExperimentalNativeApi
import kotlin.math.abs

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

    actual fun onRuntimeStarted(runtime: KotlinspectRuntime) {
        dispatch_async(dispatch_get_main_queue()) { IosOverlay.install(runtime) }
    }

    actual fun openInspector(runtime: KotlinspectRuntime) {
        dispatch_async(dispatch_get_main_queue()) { IosOverlay.presentInspector(runtime) }
    }
}

/**
 * The bubble, toast and inspector each live in their own window above the app. Nothing is
 * presented on the app's view controllers, so the overlay works the same whatever the app's
 * navigation looks like (SwiftUI, sheets, navigation stacks) and can never get stuck behind it.
 */
private object IosOverlay {
    private var bubble: UIWindow? = null
    private var toast: UIWindow? = null
    private var toasts: ToastController? = null
    private var inspector: UIWindow? = null
    private var native: NativeInspector? = null
    private var previousKeyWindow: UIWindow? = null
    private var pendingObserver: Any? = null

    private const val BUBBLE_POINTS = 72.0
    private const val TOAST_HEIGHT = 72.0
    private const val MARGIN = 6.0
    private const val DRAG_SLOP = 6.0

    private fun activeScene(): UIWindowScene? {
        val scenes = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
        return scenes.firstOrNull { it.activationState == UISceneActivationStateForegroundActive } ?: scenes.firstOrNull()
    }

    private fun appWindows(scene: UIWindowScene): List<UIWindow> =
        scene.windows.filterIsInstance<UIWindow>().filter { it !== bubble && it !== toast && it !== inspector }

    fun install(runtime: KotlinspectRuntime) {
        if (bubble != null) return
        val scene = activeScene()
        if (scene == null) {
            if (pendingObserver == null) {
                pendingObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                    UISceneDidActivateNotification,
                    null,
                    NSOperationQueue.mainQueue,
                ) { _ ->
                    pendingObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
                    pendingObserver = null
                    install(runtime)
                }
            }
            return
        }
        val controller = ToastController(runtime)
        toasts = controller
        val (screenWidth, screenHeight) = scene.coordinateSpace.bounds.useContents { size.width to size.height }

        val bubbleWindow = UIWindow(windowScene = scene).apply {
            setFrame(CGRectMake(screenWidth - BUBBLE_POINTS - MARGIN, screenHeight * 0.7, BUBBLE_POINTS, BUBBLE_POINTS))
            windowLevel = UIWindowLevelAlert + 1
            backgroundColor = UIColor.clearColor
        }
        bubbleWindow.rootViewController = ComposeUIViewController(configure = { opaque = false }) {
            val counts by remember { runtime.bubbleCounts() }.collectAsState(BubbleCounts())
            KotlinspectTheme {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // Positions are converted to screen points: the window moves under the
                            // finger, so local positions alone would cancel the drag out.
                            val start = screenPoint(bubbleWindow, down.position.x / density, down.position.y / density)
                            val startOrigin = origin(bubbleWindow)
                            var dragged = false
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val now = screenPoint(bubbleWindow, change.position.x / density, change.position.y / density)
                                val dx = now.first - start.first
                                val dy = now.second - start.second
                                if (!dragged && (abs(dx) > DRAG_SLOP || abs(dy) > DRAG_SLOP)) dragged = true
                                if (dragged) {
                                    change.consume()
                                    moveBubble(bubbleWindow, startOrigin.first + dx, startOrigin.second + dy)
                                }
                            }
                            if (dragged) snapBubble(bubbleWindow) else presentInspector(runtime)
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) { BubbleContent(counts) }
            }
        }.apply { view.backgroundColor = UIColor.clearColor }

        val toastWindow = UIWindow(windowScene = scene).apply {
            val top = appWindows(scene).firstOrNull()?.safeAreaInsets?.useContents { top } ?: 47.0
            setFrame(CGRectMake(0.0, top + 4, screenWidth, TOAST_HEIGHT))
            windowLevel = UIWindowLevelAlert + 1
            backgroundColor = UIColor.clearColor
            userInteractionEnabled = false
            hidden = true
        }
        toastWindow.rootViewController = ComposeUIViewController(configure = { opaque = false }) {
            val message by controller.current.collectAsState()
            KotlinspectTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { message?.let { ToastCard(it) } }
            }
        }.apply { view.backgroundColor = UIColor.clearColor }

        bubble = bubbleWindow
        toast = toastWindow
        snapBubble(bubbleWindow)

        runtime.scope.launch(Dispatchers.Main) {
            combine(runtime.bubbleVisible, controller.current) { _, _ -> }.collectLatest { refresh(runtime) }
        }
    }

    /** Shows or hides the bubble and toast from the current state; safe to call any time. */
    private fun refresh(runtime: KotlinspectRuntime) {
        val inspectorOpen = inspector != null
        bubble?.hidden = !(runtime.bubbleVisible.value && runtime.config.enabled) || inspectorOpen
        toast?.hidden = toasts?.current?.value == null || inspectorOpen
    }

    private fun origin(window: UIWindow): Pair<Double, Double> = window.frame.useContents { origin.x to origin.y }

    private fun screenPoint(window: UIWindow, localX: Float, localY: Float): Pair<Double, Double> {
        val (x, y) = origin(window)
        return (x + localX) to (y + localY)
    }

    /** Area the bubble's top-left corner may occupy: the screen inside the safe area. */
    private fun bubbleBounds(window: UIWindow): List<Double> {
        val scene = window.windowScene
        val (w, h) = (scene?.coordinateSpace?.bounds ?: window.bounds).useContents { size.width to size.height }
        val insets = scene?.let { appWindows(it).firstOrNull() }?.safeAreaInsets
        val top = insets?.useContents { top } ?: 0.0
        val bottom = insets?.useContents { bottom } ?: 0.0
        val left = insets?.useContents { left } ?: 0.0
        val right = insets?.useContents { right } ?: 0.0
        return listOf(
            left + MARGIN,
            top + MARGIN,
            (w - right - BUBBLE_POINTS - MARGIN).coerceAtLeast(left + MARGIN),
            (h - bottom - BUBBLE_POINTS - MARGIN).coerceAtLeast(top + MARGIN),
        )
    }

    private fun moveBubble(window: UIWindow, x: Double, y: Double) {
        val (minX, minY, maxX, maxY) = bubbleBounds(window)
        window.setFrame(CGRectMake(x.coerceIn(minX, maxX), y.coerceIn(minY, maxY), BUBBLE_POINTS, BUBBLE_POINTS))
    }

    /** Moves the bubble to the nearest side, like on Android. */
    private fun snapBubble(window: UIWindow) {
        val (minX, minY, maxX, maxY) = bubbleBounds(window)
        val (x, y) = origin(window)
        val targetX = if (x - minX < maxX - x) minX else maxX
        UIView.animateWithDuration(0.2) {
            window.setFrame(CGRectMake(targetX, y.coerceIn(minY, maxY), BUBBLE_POINTS, BUBBLE_POINTS))
        }
    }

    fun presentInspector(runtime: KotlinspectRuntime) {
        if (inspector != null) return
        val scene = activeScene() ?: return
        previousKeyWindow = appWindows(scene).firstOrNull { it.isKeyWindow() }

        // A transparent host in our own window; the inspector is presented on it as a standard
        // sheet. No window transforms: UIKit does not reliably animate a window's transform,
        // which left the inspector off screen until the app was backgrounded.
        val host = UIViewController(nibName = null, bundle = null).apply { view.backgroundColor = UIColor.clearColor }
        val window = UIWindow(windowScene = scene).apply {
            setFrame(scene.coordinateSpace.bounds)
            windowLevel = UIWindowLevelAlert + 2
            backgroundColor = UIColor.clearColor
            rootViewController = host
        }
        val content = if (NativeInspector.isSupported()) {
            NativeInspector(runtime, onClose = { closeInspector(runtime) }).also { native = it }.root
        } else {
            ComposeUIViewController { InspectorApp(runtime, onClose = { closeInspector(runtime) }) }
        }
        content.modalPresentationStyle = UIModalPresentationPageSheet
        content.presentationController?.delegate = dismissDelegate
        activeRuntime = runtime
        inspector = window
        refresh(runtime)
        // Key window so the search field can receive the keyboard.
        window.makeKeyAndVisible()

        // Present on the next run loop turn: the host is then in the window hierarchy, and we are
        // no longer inside the bubble's touch handling.
        dispatch_async(dispatch_get_main_queue()) {
            host.presentViewController(content, animated = true, completion = null)
            // Safety net: if UIKit refused to present, do not leave an empty window and a hidden bubble.
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 1_000_000_000L), dispatch_get_main_queue()) {
                if (inspector === window && host.presentedViewController == null) teardown(runtime)
            }
        }
    }

    private fun closeInspector(runtime: KotlinspectRuntime) {
        val presented = inspector?.rootViewController?.presentedViewController
        if (presented != null) {
            presented.dismissViewControllerAnimated(true) { teardown(runtime) }
        } else {
            teardown(runtime)
        }
    }

    /** Removes the inspector window and restores the app's key window and the bubble. */
    private fun teardown(runtime: KotlinspectRuntime) {
        val window = inspector ?: return
        window.hidden = true
        window.rootViewController = null
        native?.dispose()
        native = null
        inspector = null
        activeRuntime = null
        previousKeyWindow?.makeKeyWindow()
        previousKeyWindow = null
        refresh(runtime)
    }

    private var activeRuntime: KotlinspectRuntime? = null

    /** Handles the user swiping the sheet down. */
    private val dismissDelegate = object : NSObject(), UIAdaptivePresentationControllerDelegateProtocol {
        override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
            activeRuntime?.let(::teardown)
        }
    }
}
