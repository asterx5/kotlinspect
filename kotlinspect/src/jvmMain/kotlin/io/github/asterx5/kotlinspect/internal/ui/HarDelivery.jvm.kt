package io.github.asterx5.kotlinspect.internal.ui

import io.github.asterx5.kotlinspect.ExperimentalKotlinspectApi
import io.github.asterx5.kotlinspect.HarExportResult
import io.github.asterx5.kotlinspect.internal.HarTarget
import io.github.asterx5.kotlinspect.internal.KotlinspectHost
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities

/** Desktop: ask where to save, then write the file straight there. */
@OptIn(ExperimentalKotlinspectApi::class)
internal actual suspend fun deliverHar(
    host: KotlinspectHost,
    target: HarTarget,
    includeBodies: Boolean,
    onProgress: (Int, Int) -> Unit,
): String? {
    val suggested = host.suggestedHarFileName(target)
    val chosen = onUiThread { chooseFile(suggested) } ?: return null
    return when (val result = host.exportHar(target, includeBodies, outputPath = chosen.absolutePath, onProgress = onProgress)) {
        is HarExportResult.Success -> "Saved ${chosen.name} · ${result.entryCount} ${if (result.entryCount == 1) "call" else "calls"}"
        is HarExportResult.Failure -> "Export failed: ${result.reason}"
    }
}

private fun chooseFile(suggested: String): File? {
    val dialog = FileDialog(null as Frame?, "Save HAR", FileDialog.SAVE).apply {
        file = suggested
        isVisible = true // modal: returns once the user picks a file or cancels
    }
    val name = dialog.file ?: return null
    val path = File(dialog.directory, name)
    return if (path.name.endsWith(".har", ignoreCase = true)) path else File(path.parentFile, path.name + ".har")
}

/** Compose desktop runs UI coroutines on the Swing thread; hop there if called from elsewhere. */
private fun <T> onUiThread(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
}
