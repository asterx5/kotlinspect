package io.github.asterx5.kotlinspect.internal.ui

import io.github.asterx5.kotlinspect.ExperimentalKotlinspectApi
import io.github.asterx5.kotlinspect.HarExportResult
import io.github.asterx5.kotlinspect.internal.HarTarget
import io.github.asterx5.kotlinspect.internal.KotlinspectHost

/** Android: write to the app's cache, then open the share sheet. */
@OptIn(ExperimentalKotlinspectApi::class)
internal actual suspend fun deliverHar(
    host: KotlinspectHost,
    target: HarTarget,
    includeBodies: Boolean,
    onProgress: (Int, Int) -> Unit,
): String? = when (val result = host.exportHar(target, includeBodies, onProgress = onProgress)) {
    is HarExportResult.Success -> {
        host.shareFile(result.filePath)
        "HAR exported · ${result.entryCount} ${if (result.entryCount == 1) "call" else "calls"}"
    }
    is HarExportResult.Failure -> "Export failed: ${result.reason}"
}
