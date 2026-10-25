package io.github.asterx5.kotlinspect.internal

import kotlinx.coroutines.CoroutineDispatcher

/** Dispatchers.IO where it exists; web has none. */
internal expect val ioDispatcher: CoroutineDispatcher
