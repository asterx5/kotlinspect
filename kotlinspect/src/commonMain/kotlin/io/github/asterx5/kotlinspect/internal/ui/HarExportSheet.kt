package io.github.asterx5.kotlinspect.internal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.asterx5.kotlinspect.internal.HarTarget
import io.github.asterx5.kotlinspect.internal.KotlinspectHost
import kotlinx.coroutines.launch

/**
 * Exports a session or one call as HAR and hands it over the platform's way: the share sheet on
 * Android, a save dialog on desktop. Returns the message to show, or null when the user cancelled.
 */
internal expect suspend fun deliverHar(
    host: KotlinspectHost,
    target: HarTarget,
    includeBodies: Boolean,
    onProgress: (Int, Int) -> Unit,
): String?

/** Exports with more entries than this show progress while they run. */
private const val PROGRESS_THRESHOLD = 200

/** The sheet shown before an export: what a HAR file holds, and whether bodies go in. */
@Composable
internal fun HarExportSheet(host: KotlinspectHost, target: HarTarget?, onDismiss: () -> Unit, onNotice: (String) -> Unit) {
    val c = Ks.colors
    val scope = rememberCoroutineScope()
    var includeBodies by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val visible = target != null

    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { if (!running) onDismiss() },
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(c.surface)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .padding(20.dp),
            ) {
                KsText(if (target is HarTarget.Call) "Share as HAR" else "Export as HAR", Ks.type.title)
                Spacer(Modifier.height(6.dp))
                KsText("HAR files contain request data. Sensitive headers are already redacted.", Ks.type.caption)
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !running) { includeBodies = !includeBodies }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KsText("Include bodies", Ks.type.body, modifier = Modifier.weight(1f))
                    Toggle(includeBodies)
                }
                Spacer(Modifier.height(18.dp))
                val p = progress
                if (running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spinner(c.accent, 16.dp)
                        Spacer(Modifier.width(10.dp))
                        KsText(
                            if (p != null && p.second > PROGRESS_THRESHOLD) "Exporting ${p.first} of ${p.second}…" else "Exporting…",
                            Ks.type.body,
                            color = c.textMuted,
                        )
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        Action("Cancel", onClick = onDismiss)
                        Spacer(Modifier.width(8.dp))
                        Action("Export", emphasized = true) {
                            val t = target ?: return@Action
                            running = true
                            progress = null
                            scope.launch {
                                val message = try {
                                    deliverHar(host, t, includeBodies) { written, total -> progress = written to total }
                                } catch (e: Throwable) {
                                    "Export failed: ${e.message ?: e::class.simpleName}"
                                }
                                running = false
                                onDismiss()
                                message?.let(onNotice)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A small on/off switch in the inspector's style. */
@Composable
private fun Toggle(on: Boolean) {
    val c = Ks.colors
    Box(
        Modifier.size(width = 44.dp, height = 26.dp).clip(RoundedCornerShape(50)).background(if (on) c.accent else c.surfaceAlt).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(if (on) c.onAccent else c.textFaint))
    }
}
