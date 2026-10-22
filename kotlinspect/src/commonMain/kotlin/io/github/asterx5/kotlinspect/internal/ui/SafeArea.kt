package io.github.asterx5.kotlinspect.internal.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Keeps content clear of the status bar, notch and gesture areas.
 *
 * Per platform rather than `windowInsetsPadding(WindowInsets.safeDrawing)` in common code: on
 * desktop that property is a JVM top-level in a file newer Compose moved, so a library built
 * against the Compose floor crashed desktop apps with `NoSuchMethodError`. Desktop has no system
 * insets to keep clear of, so it pads nothing.
 */
@Composable
internal expect fun Modifier.safeAreaPadding(): Modifier
