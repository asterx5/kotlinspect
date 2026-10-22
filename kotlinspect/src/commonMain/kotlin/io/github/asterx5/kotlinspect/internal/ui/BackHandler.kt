package io.github.asterx5.kotlinspect.internal.ui

import androidx.compose.runtime.Composable

/** System back (Android back button and gesture). A no-op where the platform has none. */
@Composable
internal expect fun PlatformBackHandler(onBack: () -> Unit)
