package io.github.asterx5.kotlinspect

/**
 * Marks public Kotlinspect API that is new and may still change shape in a later release, such as
 * the ingestion API for capture sources other than Ktor.
 */
@RequiresOptIn(
    message = "This Kotlinspect API is experimental and may change.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class ExperimentalKotlinspectApi
