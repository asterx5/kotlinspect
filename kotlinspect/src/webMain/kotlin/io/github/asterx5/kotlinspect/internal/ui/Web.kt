package io.github.asterx5.kotlinspect.internal.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.asterx5.kotlinspect.internal.HarTarget
import io.github.asterx5.kotlinspect.internal.KotlinspectHost

// Web is a no-op: Kotlinspect is never enabled there, so none of this UI is ever shown.

@Composable
internal actual fun PlatformBackHandler(onBack: () -> Unit) = Unit

@Composable
internal actual fun Modifier.safeAreaPadding(): Modifier = this

internal actual suspend fun deliverHar(
    host: KotlinspectHost,
    target: HarTarget,
    includeBodies: Boolean,
    onProgress: (Int, Int) -> Unit,
): String? = "Export is not supported on web"
