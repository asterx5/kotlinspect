package io.github.asterx5.kotlinspect

/** What goes into an exported HAR file. */
@ExperimentalKotlinspectApi
public class HarExportOptions(
    /** Request and response bodies, as stored (already redacted and truncated). */
    public val includeBodies: Boolean = true,
    /** Calls that failed or were cancelled. */
    public val includeFailed: Boolean = true,
    /** Calls still in flight. */
    public val includePending: Boolean = false,
)

/** The outcome of a HAR export. Exports never throw; problems come back as [Failure]. */
@ExperimentalKotlinspectApi
public sealed interface HarExportResult {
    /** [filePath] is a HAR 1.2 file with [entryCount] calls, [byteSize] bytes long. */
    public class Success(public val filePath: String, public val entryCount: Int, public val byteSize: Long) : HarExportResult {
        override fun toString(): String = "HarExportResult.Success($filePath, entries=$entryCount, bytes=$byteSize)"
    }

    public class Failure(public val reason: String) : HarExportResult {
        override fun toString(): String = "HarExportResult.Failure($reason)"
    }
}
