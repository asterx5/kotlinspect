package io.github.asterx5.kotlog

/** Shared between Kotlog and Kotlinspect; not part of the supported API. */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is internal to Kotlog and Kotlinspect and can change in any release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
public annotation class InternalKotlogApi
