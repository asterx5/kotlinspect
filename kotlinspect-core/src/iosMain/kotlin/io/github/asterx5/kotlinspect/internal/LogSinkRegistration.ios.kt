@file:OptIn(ExperimentalStdlibApi::class)

package io.github.asterx5.kotlinspect.internal

import io.github.asterx5.kotlog.KotlogSinks

// Kotlin/Native has no ServiceLoader: register the sink when the app starts, so Kotlog entries
// reach the inspector without any setup code.
@EagerInitialization
@Suppress("unused")
private val logSinkRegistration: Unit = KotlogSinks.register(KotlinspectLogSink())
