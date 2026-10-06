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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.asterx5.kotlinspect.internal.KotlinspectRuntime
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import io.github.asterx5.kotlinspect.internal.db.RecordEntity
import io.github.asterx5.kotlinspect.internal.formatDuration
import io.github.asterx5.kotlinspect.internal.isError
import kotlin.math.roundToInt

@Immutable
internal data class BubbleCounts(val total: Int = 0, val inFlight: Int = 0, val errors: Int = 0)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun KotlinspectRuntime.bubbleCounts(): Flow<BubbleCounts> = currentSessionId.flatMapLatest { id ->
    if (id == null) {
        flowOf(BubbleCounts())
    } else {
        val records = database.records()
        combine(records.observeCount(id), records.observeInFlight(id), records.observeErrorCount(id)) { t, f, e ->
            BubbleCounts(t, f, e)
        }
    }
}

/** The circle itself: call count, a spinning ring while calls are in flight, red when errors exist. */
@Composable
internal fun BubbleContent(counts: BubbleCounts, modifier: Modifier = Modifier) {
    val hasErrors = counts.errors > 0
    val fill = if (hasErrors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(modifier.size(BUBBLE_SIZE), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(52.dp)
                .shadow(6.dp, CircleShape)
                .background(fill, CircleShape)
                .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (counts.total > 999) "999+" else counts.total.toString(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = if (counts.total > 99) 13.sp else 17.sp,
            )
        }
        if (counts.inFlight > 0) {
            CircularProgressIndicator(
                modifier = Modifier.size(60.dp),
                color = fill,
                strokeWidth = 3.dp,
            )
        }
        if (hasErrors) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
                    .background(Color.White, CircleShape)
                    .border(1.5.dp, fill, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (counts.errors > 99) "99" else counts.errors.toString(),
                    color = fill,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

internal val BUBBLE_SIZE = 64.dp

/** Bubble position shared across activities and screens, in pixels from the top-left. */
internal object BubblePosition {
    var offset: Offset? = null
}

/**
 * Full-screen overlay used on Android: a draggable bubble and the toast. Areas without a
 * pointer handler let touches through to the app underneath.
 */
@Composable
internal fun FullScreenOverlay(runtime: KotlinspectRuntime, toasts: ToastController, onOpenInspector: () -> Unit) {
    val counts by remember(runtime) { runtime.bubbleCounts() }.collectAsState(BubbleCounts())
    val visible by runtime.bubbleVisible.collectAsState()
    val toast by toasts.current.collectAsState()
    val density = LocalDensity.current

    KotlinspectTheme {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val maxX = with(density) { (maxWidth - BUBBLE_SIZE).toPx() }
            val maxY = with(density) { (maxHeight - BUBBLE_SIZE).toPx() }
            var offset by remember {
                mutableStateOf(BubblePosition.offset ?: Offset(maxX, maxY * 0.7f))
            }
            val clamped = Offset(offset.x.coerceIn(0f, maxX.coerceAtLeast(0f)), offset.y.coerceIn(0f, maxY.coerceAtLeast(0f)))

            ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))

            if (visible) {
                BubbleContent(
                    counts,
                    Modifier
                        .offset { IntOffset(clamped.x.roundToInt(), clamped.y.roundToInt()) }
                        .pointerInput(Unit) { detectTapGestures { onOpenInspector() } }
                        .pointerInput(maxX, maxY) {
                            detectDragGestures(
                                onDragEnd = {
                                    // Snap to the nearest horizontal edge.
                                    offset = Offset(if (offset.x < maxX / 2) 0f else maxX, offset.y.coerceIn(0f, maxY))
                                    BubblePosition.offset = offset
                                },
                            ) { change, drag ->
                                change.consume()
                                offset = Offset(
                                    (offset.x + drag.x).coerceIn(0f, maxX),
                                    (offset.y + drag.y).coerceIn(0f, maxY),
                                )
                            }
                        },
                )
            }
        }
    }
}

// region Toast

@Immutable
internal data class ToastMessage(val id: Long, val title: String, val subtitle: String?, val isError: Boolean)

internal fun buildToast(id: Long, records: List<RecordEntity>): ToastMessage? {
    if (records.isEmpty()) return null
    val last = records.last()
    fun line(r: RecordEntity): String {
        val status = r.statusCode?.toString() ?: r.state.uppercase()
        return "${r.method} ${r.path} · $status · ${formatDuration(r.durationMs)}"
    }
    if (records.size == 1) {
        return ToastMessage(id, line(last), null, last.isError)
    }
    val errors = records.count { it.isError }
    val title = buildString {
        append("${records.size} calls")
        if (errors > 0) append(" · $errors failed")
    }
    return ToastMessage(id, title, "Latest: ${line(last)}", errors > 0)
}

/**
 * Turns finished calls into toasts. Calls finishing within the batch window are combined into
 * one toast, and a new toast replaces the one on screen.
 */
internal class ToastController(private val runtime: KotlinspectRuntime) {
    // Single-threaded so the batch list needs no locking.
    private val scope = CoroutineScope(runtime.scope.coroutineContext + Dispatchers.Default.limitedParallelism(1))

    private val _current = MutableStateFlow<ToastMessage?>(null)
    val current: StateFlow<ToastMessage?> = _current.asStateFlow()

    private val pending = mutableListOf<RecordEntity>()
    private var batchJob: Job? = null
    private var hideJob: Job? = null
    private var nextId = 0L

    init {
        scope.launch {
            runtime.completed.collect { record ->
                val config = runtime.config
                if (!config.toastEnabled || !config.enabled) return@collect
                if (!runCatching { config.toastFilter.shouldShow(record.toCall()) }.getOrDefault(false)) return@collect
                pending += record
                if (batchJob?.isActive != true) {
                    batchJob = scope.launch {
                        delay(config.toastBatchWindowMillis)
                        val batch = pending.toList()
                        pending.clear()
                        show(buildToast(nextId++, batch))
                    }
                }
            }
        }
    }

    private fun show(message: ToastMessage?) {
        message ?: return
        _current.value = message
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(runtime.config.toastDurationMillis)
            _current.value = null
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

@Composable
internal fun ToastCard(message: ToastMessage, modifier: Modifier = Modifier) {
    val accent = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.widthIn(max = 420.dp).padding(horizontal = 12.dp),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(accent, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.fillMaxWidth(fraction = 1f)) {
                Text(
                    message.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = Mono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                message.subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = Mono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// endregion
