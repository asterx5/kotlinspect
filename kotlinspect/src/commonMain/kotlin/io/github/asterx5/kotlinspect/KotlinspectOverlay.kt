package io.github.asterx5.kotlinspect

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.asterx5.kotlinspect.internal.ui.FullScreenOverlay

/**
 * The bubble and toasts, drawn in your own Compose UI. Place it last in your root container so it
 * sits on top; touches outside the bubble reach your content.
 *
 * ```
 * Box(Modifier.fillMaxSize()) {
 *     AppContent()
 *     KotlinspectOverlay()
 * }
 * ```
 *
 * While it is on screen, the automatic bubble hides. With [sessionId], the bubble counts, toasts
 * and the inspector it opens cover only that session; with null, the current session.
 * Draws nothing when Kotlinspect is disabled, such as in release builds.
 */
@Composable
public fun KotlinspectOverlay(modifier: Modifier = Modifier, sessionId: String? = null) {
    val host = remember { Kotlinspect.host() } ?: return
    val toasts = remember(host, sessionId) { host.toasts(sessionId) }
    DisposableEffect(host, toasts) {
        val detach = host.attachEmbeddedOverlay()
        onDispose {
            detach()
            toasts.close()
        }
    }
    Box(modifier.fillMaxSize()) {
        FullScreenOverlay(host, toasts, sessionId = sessionId, automatic = false) {
            Kotlinspect.openInspector(sessionId)
        }
    }
}
