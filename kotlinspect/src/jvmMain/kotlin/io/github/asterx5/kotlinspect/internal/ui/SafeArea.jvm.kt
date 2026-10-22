package io.github.asterx5.kotlinspect.internal.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// A desktop window has no system bars or cutouts inside it.
@Composable
internal actual fun Modifier.safeAreaPadding(): Modifier = this
