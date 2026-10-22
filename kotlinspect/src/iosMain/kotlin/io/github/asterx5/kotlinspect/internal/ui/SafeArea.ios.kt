package io.github.asterx5.kotlinspect.internal.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun Modifier.safeAreaPadding(): Modifier = windowInsetsPadding(WindowInsets.safeDrawing)
