@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.asterx5.kotlinspect.internal

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UISceneDidActivateNotification
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowLevelAlert
import platform.UIKit.UIWindowScene
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
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

    actual fun onRuntimeStarted(runtime: KotlinspectRuntime) {
        dispatch_async(dispatch_get_main_queue()) { IosOverlay.install(runtime) }
    }

    actual fun openInspector(runtime: KotlinspectRuntime) {
        dispatch_async(dispatch_get_main_queue()) { IosOverlay.presentInspector(runtime) }
    }
}

/**
 * The bubble and toast each live in a small transparent window above the app, so touches
 * elsewhere reach the app untouched. The bubble window moves as it is dragged.
 */
private object IosOverlay {
    private var bubble: UIWindow? = null
    private var toast: UIWindow? = null
    private var inspector: UIViewController? = null
    private var pendingObserver: Any? = null

    private const val BUBBLE_POINTS = 72.0
    private const val TOAST_HEIGHT = 72.0

    private fun activeScene(): UIWindowScene? {
        val scenes = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
        return scenes.firstOrNull { it.activationState == UISceneActivationStateForegroundActive } ?: scenes.firstOrNull()
    }

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
        val toasts = ToastController(runtime)
        val (screenWidth, screenHeight) = scene.coordinateSpace.bounds.useContents { size.width to size.height }

        val bubbleWindow = UIWindow(windowScene = scene).apply {
            setFrame(CGRectMake(screenWidth - BUBBLE_POINTS - 8, screenHeight * 0.7, BUBBLE_POINTS, BUBBLE_POINTS))
            windowLevel = UIWindowLevelAlert + 1
            backgroundColor = UIColor.clearColor
        }
        bubbleWindow.rootViewController = ComposeUIViewController(configure = { opaque = false }) {
            val counts by remember { runtime.bubbleCounts() }.collectAsState(BubbleCounts())
            val density = LocalDensity.current.density
            KotlinspectTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { presentInspector(runtime) } }
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                moveBubble(bubbleWindow, drag.x / density, drag.y / density)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { BubbleContent(counts) }
            }
        }.apply { view.backgroundColor = UIColor.clearColor }

        val toastWindow = UIWindow(windowScene = scene).apply {
            setFrame(CGRectMake(0.0, 50.0, screenWidth, TOAST_HEIGHT))
            windowLevel = UIWindowLevelAlert + 1
            backgroundColor = UIColor.clearColor
            userInteractionEnabled = false
            hidden = true
        }
        toastWindow.rootViewController = ComposeUIViewController(configure = { opaque = false }) {
            val message by toasts.current.collectAsState()
            KotlinspectTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { message?.let { ToastCard(it) } }
            }
        }.apply { view.backgroundColor = UIColor.clearColor }

        bubble = bubbleWindow
        toast = toastWindow

        runtime.scope.launch(Dispatchers.Main) {
            combine(runtime.bubbleVisible, toasts.current) { visible, message -> visible to message }
                .collectLatest { (visible, message) ->
                    bubbleWindow.hidden = !(visible && runtime.config.enabled) || inspector != null
                    toastWindow.hidden = message == null || inspector != null
                }
        }
    }

    private fun moveBubble(window: UIWindow, dx: Float, dy: Float) {
        val (x, y, w, h) = window.frame.useContents { listOf(origin.x, origin.y, size.width, size.height) }
        window.setFrame(CGRectMake(x + dx, y + dy, w, h))
    }

    fun presentInspector(runtime: KotlinspectRuntime) {
        if (inspector != null) return
        val host = topViewController() ?: return
        lateinit var controller: UIViewController
        controller = ComposeUIViewController {
            InspectorApp(runtime, onClose = {
                controller.dismissViewControllerAnimated(true) {
                    inspector = null
                    bubble?.hidden = !runtime.bubbleVisible.value
                }
            })
        }
        controller.modalPresentationStyle = UIModalPresentationFullScreen
        inspector = controller
        bubble?.hidden = true
        toast?.hidden = true
        host.presentViewController(controller, animated = true, completion = null)
    }

    private fun topViewController(): UIViewController? {
        val scene = activeScene() ?: return null
        val windows = scene.windows.filterIsInstance<UIWindow>().filter { it !== bubble && it !== toast }
        val window = windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull() ?: return null
        var top = window.rootViewController ?: return null
        while (true) top = top.presentedViewController ?: break
        return top
    }
}
