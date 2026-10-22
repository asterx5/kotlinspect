package io.github.asterx5.kotlinspect

/**
 * Marks declarations shared between Kotlinspect's own modules, such as the Compose UI built on top
 * of kotlinspect-core. They are not part of the supported API and can change in any release.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is internal to Kotlinspect's own modules and can change in any release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.CONSTRUCTOR,
)
public annotation class InternalKotlinspectApi
