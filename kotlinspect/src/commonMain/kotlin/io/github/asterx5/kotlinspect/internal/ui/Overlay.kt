package io.github.asterx5.kotlinspect.internal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.asterx5.kotlinspect.internal.KotlinspectHost
import io.github.asterx5.kotlinspect.internal.uimodel.BubbleCounts
import io.github.asterx5.kotlinspect.internal.uimodel.KsPalette
import io.github.asterx5.kotlinspect.internal.uimodel.ToastController
import io.github.asterx5.kotlinspect.internal.uimodel.ToastMessage
import io.github.asterx5.kotlinspect.internal.db.CallCounts
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.github.asterx5.kotlinspect.internal.formatDuration
import io.github.asterx5.kotlinspect.internal.isError
import io.github.asterx5.kotlinspect.internal.toCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The bubble: a dark disc with the call count, an accent ring that spins while calls are in flight, red when errors exist. */
@Composable
internal fun BubbleContent(counts: BubbleCounts, modifier: Modifier = Modifier, showLogCount: Boolean = true) {
    val c = Ks.colors
    val hasErrors = counts.errors > 0
    val ring = if (hasErrors) c.error else c.accent
    Box(modifier.size(BUBBLE_SIZE), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(50.dp)
                .shadow(10.dp, CircleShape)
                .clip(CircleShape)
                .background(BubbleInk)
                .border(2.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            KsText(
                text = if (counts.total > 999) "999+" else counts.total.toString(),
                style = Ks.type.monoStrong.copy(color = BubbleText, fontSize = if (counts.total > 99) 13.sp else 17.sp),
                maxLines = 1,
            )
        }
        if (counts.inFlight > 0) Spinner(ring, 60.dp, stroke = 3.dp)
        if (showLogCount && counts.logs > 0) {
            // Log count: a small pill under the call count, quieter than the calls themselves.
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(50))
                    .background(LogPill)
                    .border(1.dp, ring.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                KsText(
                    "≡ " + if (counts.logs > 999) "999+" else counts.logs.toString(),
                    Ks.type.monoSmall.copy(color = BubbleText.copy(alpha = 0.85f), fontSize = 9.sp),
                    maxLines = 1,
                )
            }
        }
        if (hasErrors) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp)
                    .size(20.dp).clip(CircleShape).background(c.error),
                contentAlignment = Alignment.Center,
            ) {
                KsText(
                    if (counts.errors > 99) "99" else counts.errors.toString(),
                    Ks.type.monoSmall.copy(color = BubbleText, fontWeight = FontWeight.Bold, fontSize = 10.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

// The bubble stays dark in both themes so it reads clearly over any app.
private val BubbleInk = androidx.compose.ui.graphics.Color(KsPalette.INK)
private val BubbleText = androidx.compose.ui.graphics.Color(KsPalette.INK_TEXT)
private val LogPill = androidx.compose.ui.graphics.Color(0xFF2A2E38)

internal val BUBBLE_SIZE = 64.dp

/**
 * Where the bubble rests, shared across activities and screens: the edge it hugs and how far
 * down that edge it sits (0 top, 1 bottom).
 *
 * Kept as an anchor rather than pixels so the bubble follows the overlay's size. The first
 * layout can be smaller than the final one (a desktop window is laid out while it is still
 * being created), and pixels taken from it left the bubble mid-screen until it was dragged.
 */
internal object BubblePosition {
    var alignRight: Boolean = true
    var yFraction: Float = 0.7f
}

/**
 * Full-screen overlay used on Android: a draggable bubble and the toast. Areas without a
 * pointer handler let touches through to the app underneath.
 */
@Composable
internal fun FullScreenOverlay(
    host: KotlinspectHost,
    toasts: ToastController,
    sessionId: String? = null,
    automatic: Boolean = true,
    onOpenInspector: () -> Unit,
) {
    val counts by remember(host, sessionId) { host.bubbleCounts(sessionId) }.collectAsState(BubbleCounts())
    val bubbleVisible by host.bubbleVisible.collectAsState()
    val embedded by host.embeddedOverlays.collectAsState()
    // The automatic overlay steps aside while an overlay placed in the app's UI is showing.
    val visible = bubbleVisible && host.isActive && (!automatic || embedded == 0)
    val toast by toasts.current.collectAsState()
    val density = LocalDensity.current

    KotlinspectTheme {
        BoxWithConstraints(Modifier.fillMaxSize().safeAreaPadding()) {
            val maxX = with(density) { (maxWidth - BUBBLE_SIZE).toPx() }
            val maxY = with(density) { (maxHeight - BUBBLE_SIZE).toPx() }
            var alignRight by remember { mutableStateOf(BubblePosition.alignRight) }
            var yFraction by remember { mutableStateOf(BubblePosition.yFraction) }
            // Pixels only while a finger is down; at rest the anchor decides.
            var dragged by remember { mutableStateOf<Offset?>(null) }
            val rest = Offset(if (alignRight) maxX else 0f, maxY * yFraction)
            val position = dragged ?: rest
            val clamped = Offset(position.x.coerceIn(0f, maxX.coerceAtLeast(0f)), position.y.coerceIn(0f, maxY.coerceAtLeast(0f)))

            ToastHost(if (automatic && embedded > 0) null else toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))

            if (visible) {
                BubbleContent(
                    counts,
                    showLogCount = host.showLogCount,
                    modifier = Modifier
                        // Absolute, both the corner and the offset: the overlay sits inside the app's
                        // layout direction. In a right-to-left app the Box's start corner is the top
                        // right, so an absolute offset from it pushed the bubble off screen, and a
                        // mirrored offset put a right-edge bubble on the left and dragged it backwards.
                        .align(AbsoluteAlignment.TopLeft)
                        .absoluteOffset { IntOffset(clamped.x.roundToInt(), clamped.y.roundToInt()) }
                        .pointerInput(Unit) { detectTapGestures { onOpenInspector() } }
                        .pointerInput(maxX, maxY) {
                            // Snaps to the nearer edge, remembered as an anchor.
                            fun settle() {
                                val end = dragged ?: return
                                alignRight = end.x >= maxX / 2
                                yFraction = if (maxY > 0f) (end.y / maxY).coerceIn(0f, 1f) else yFraction
                                BubblePosition.alignRight = alignRight
                                BubblePosition.yFraction = yFraction
                                dragged = null
                            }
                            detectDragGestures(
                                // Read from the anchor state now: this block outlives a drag, so a
                                // position captured when it started could be a previous one.
                                onDragStart = {
                                    dragged = Offset(
                                        if (alignRight) maxX.coerceAtLeast(0f) else 0f,
                                        (maxY * yFraction).coerceIn(0f, maxY.coerceAtLeast(0f)),
                                    )
                                },
                                onDragEnd = ::settle,
                                onDragCancel = ::settle,
                            ) { change, drag ->
                                change.consume()
                                val from = dragged ?: return@detectDragGestures
                                dragged = Offset(
                                    (from.x + drag.x).coerceIn(0f, maxX.coerceAtLeast(0f)),
                                    (from.y + drag.y).coerceIn(0f, maxY.coerceAtLeast(0f)),
                                )
                            }
                        },
                )
            }
        }
    }
}

@Composable
internal fun ToastHost(message: ToastMessage?, modifier: Modifier = Modifier) {
    // Keep the last message around while it animates out.
    var shown by remember { mutableStateOf<ToastMessage?>(null) }
    if (message != null) shown = message
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        shown?.let { ToastCard(it) }
    }
}

/** Dark pill in both themes: status dot, method badge, then the summary. */
@Composable
internal fun ToastCard(message: ToastMessage, modifier: Modifier = Modifier) {
    val c = Ks.colors
    val accent = if (message.isError) c.error else c.ok
    Row(
        modifier
            .widthIn(max = 440.dp)
            .padding(horizontal = 12.dp)
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(BubbleInk)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(accent, 9.dp)
        Spacer(Modifier.width(10.dp))
        val method = message.method
        if (method != null) {
            MethodBadge(method)
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f, fill = false)) {
            val title = if (method != null) message.title.removePrefix(method).trimStart() else message.title
            KsText(title, Ks.type.monoSmall.copy(color = BubbleText), maxLines = 1)
            message.subtitle?.let {
                KsText(it, Ks.type.caption.copy(color = BubbleText.copy(alpha = 0.6f)), maxLines = 1)
            }
        }
    }
}

// endregion
