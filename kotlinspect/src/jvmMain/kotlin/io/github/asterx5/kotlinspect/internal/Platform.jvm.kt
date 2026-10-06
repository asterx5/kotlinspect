package io.github.asterx5.kotlinspect.internal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.Dialog
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import kotlin.math.abs
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.io.File
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants

internal actual object Platform {
    actual val isReady: Boolean = true

    /**
     * Desktop has no build type, so a packaged app (jpackage, as produced by Compose's
     * packageDistribution tasks) counts as release. `-Dkotlinspect.debug=true|false` overrides.
     */
    actual val isDebugBuild: Boolean
        get() = System.getProperty("kotlinspect.debug")?.toBooleanStrictOrNull()
            ?: (System.getProperty("jpackage.app-path") == null)

    actual fun databaseBuilder(inMemory: Boolean, config: ResolvedConfig): RoomDatabase.Builder<KotlinspectDatabase> {
        if (inMemory) return Room.inMemoryDatabaseBuilder<KotlinspectDatabase>()
        val dir = File(System.getProperty("user.home"), ".kotlinspect/${config.desktopAppName ?: defaultAppName()}")
        dir.mkdirs()
        return Room.databaseBuilder<KotlinspectDatabase>(File(dir, KotlinspectDatabase.FILE_NAME).absolutePath)
    }

    private fun defaultAppName(): String {
        val command = System.getProperty("sun.java.command")?.substringBefore(' ').orEmpty()
        val name = command.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".jar")
        return name.ifBlank { "default" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    actual fun sqliteDriver(): SQLiteDriver = BundledSQLiteDriver()

    actual fun onRuntimeStarted(runtime: KotlinspectRuntime) {
        if (GraphicsEnvironment.isHeadless()) return
        SwingUtilities.invokeLater { DesktopOverlay(runtime).show() }
    }

    actual fun openInspector(runtime: KotlinspectRuntime) {
        if (GraphicsEnvironment.isHeadless()) return
        SwingUtilities.invokeLater { DesktopInspector.open(runtime) }
    }
}

private object DesktopInspector {
    private var window: ComposeWindow? = null

    fun open(runtime: KotlinspectRuntime) {
        window?.let {
            it.toFront()
            it.requestFocus()
            return
        }
        val w = ComposeWindow()
        w.title = "Kotlinspect"
        w.size = Dimension(1000, 720)
        w.minimumSize = Dimension(480, 400)
        w.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        w.setLocationRelativeTo(null)
        w.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent?) {
                window = null
            }
        })
        w.setContent { InspectorApp(runtime, onClose = { w.dispose() }) }
        window = w
        w.isVisible = true
    }

    fun owns(candidate: Window): Boolean = candidate === window
}

/**
 * Bubble and toast as small transparent dialogs owned by the app's main window. Owned dialogs stay
 * above their owner (and only their owner), minimize with it and are disposed with it. The bubble
 * is kept inside the owner's content area and follows it when it moves or resizes.
 */
private class DesktopOverlay(private val runtime: KotlinspectRuntime) {
    private val toasts = ToastController(runtime)
    private var host: Window? = null
    private var bubble: ComposeDialog? = null
    private var toast: ComposeDialog? = null
    private var seenHost = false

    // Bubble anchor, kept across resizes: which side it hugs and how far down it sits (0..1).
    private var alignRight = true
    private var yFraction = 0.75f

    private val follow = object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent?) = place()
        override fun componentResized(e: ComponentEvent?) = place()
    }

    fun show() {
        runtime.scope.launch {
            toasts.current.collectLatest { SwingUtilities.invokeLater { refresh() } }
        }
        Timer(400) { event ->
            val candidate = findHost()
            if (candidate != null) seenHost = true
            if (candidate !== host) attach(candidate)
            if (seenHost && candidate == null) {
                // The app closed its windows: retire so nothing keeps the JVM alive.
                attach(null)
                (event.source as Timer).stop()
            } else {
                refresh()
            }
        }.start()
    }

    /** The largest visible app frame that is not Kotlinspect's own. */
    private fun findHost(): Window? = Window.getWindows()
        .filter { it is Frame && it.isShowing && !DesktopInspector.owns(it) }
        .maxByOrNull { it.width * it.height }

    private fun attach(newHost: Window?) {
        host?.removeComponentListener(follow)
        bubble?.dispose()
        toast?.dispose()
        bubble = null
        toast = null
        host = newHost
        newHost ?: return
        newHost.addComponentListener(follow)
        bubble = overlayDialog(newHost).apply {
            size = Dimension(BUBBLE_PX, BUBBLE_PX)
            setContent { BubbleSurface() }
        }
        toast = overlayDialog(newHost).apply {
            size = Dimension(TOAST_WIDTH, TOAST_HEIGHT)
            setContent {
                val message by toasts.current.collectAsState()
                KotlinspectTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { message?.let { ToastCard(it) } }
                }
            }
        }
        place()
    }

    @Composable
    private fun BubbleSurface() {
        val counts by remember { runtime.bubbleCounts() }.collectAsState(BubbleCounts())
        KotlinspectTheme {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        // Track the absolute pointer: local coordinates shift as the window moves.
                        val startPointer = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
                        val startBubble = bubble?.location ?: return@awaitEachGesture
                        var dragged = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val p = MouseInfo.getPointerInfo()?.location ?: continue
                            val dx = p.x - startPointer.x
                            val dy = p.y - startPointer.y
                            if (!dragged && (abs(dx) > DRAG_SLOP || abs(dy) > DRAG_SLOP)) dragged = true
                            if (dragged) {
                                change.consume()
                                moveBubbleTo(startBubble.x + dx, startBubble.y + dy)
                            }
                        }
                        if (dragged) snap() else Platform.openInspector(runtime)
                    }
                },
                contentAlignment = Alignment.Center,
            ) { BubbleContent(counts) }
        }
    }

    /** Screen-space range for the bubble's top-left corner: the host's content area minus a margin. */
    private fun bubbleArea(): Rectangle? {
        val h = host?.takeIf { it.isShowing } ?: return null
        val origin = h.locationOnScreen
        val ins = h.insets
        val x = origin.x + ins.left + MARGIN
        val y = origin.y + ins.top + MARGIN
        val w = (h.width - ins.left - ins.right - 2 * MARGIN - BUBBLE_PX).coerceAtLeast(0)
        val hh = (h.height - ins.top - ins.bottom - 2 * MARGIN - BUBBLE_PX).coerceAtLeast(0)
        return Rectangle(x, y, w, hh)
    }

    private fun moveBubbleTo(x: Int, y: Int) {
        val area = bubbleArea() ?: return
        bubble?.setLocation(x.coerceIn(area.x, area.x + area.width), y.coerceIn(area.y, area.y + area.height))
    }

    /** After a drag, hug the nearest side and remember the position relative to the window. */
    private fun snap() {
        val area = bubbleArea() ?: return
        val b = bubble ?: return
        alignRight = b.x - area.x > area.width / 2
        yFraction = if (area.height == 0) 0f else (b.y - area.y).toFloat() / area.height
        place()
    }

    private fun place() {
        val area = bubbleArea() ?: return
        bubble?.setLocation(
            if (alignRight) area.x + area.width else area.x,
            area.y + (area.height * yFraction.coerceIn(0f, 1f)).toInt(),
        )
        val h = host ?: return
        val origin = h.locationOnScreen
        val ins = h.insets
        val contentWidth = h.width - ins.left - ins.right
        val width = minOf(TOAST_WIDTH, (contentWidth - 2 * MARGIN).coerceAtLeast(160))
        toast?.setBounds(origin.x + ins.left + (contentWidth - width) / 2, origin.y + ins.top + MARGIN, width, TOAST_HEIGHT)
    }

    private fun refresh() {
        val h = host
        val hostVisible = h != null && h.isShowing && (h as? Frame)?.state != Frame.ICONIFIED
        val showBubble = hostVisible && runtime.bubbleVisible.value && runtime.config.enabled
        bubble?.let { if (it.isVisible != showBubble) it.isVisible = showBubble }
        val showToast = hostVisible && toasts.current.value != null
        toast?.let { if (it.isVisible != showToast) it.isVisible = showToast }
    }

    private fun overlayDialog(owner: Window) = ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
        isUndecorated = true
        isTransparent = true
        background = Color(0, 0, 0, 0)
        isResizable = false
        focusableWindowState = false
        isAutoRequestFocus = false
    }

    private companion object {
        const val BUBBLE_PX = 76
        const val TOAST_WIDTH = 440
        const val TOAST_HEIGHT = 72
        const val MARGIN = 12
        const val DRAG_SLOP = 4
    }
}
