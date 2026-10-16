package com.voit.design.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A generous, deliberately coarse spacing scale. Voit's monochrome design leans on
 * whitespace (not color) to create hierarchy, so spacing steps are large and few.
 *
 * All values are in [Dp] and scale with the system density; text uses sp elsewhere so
 * font scaling for accessibility is respected automatically.
 */
data class VoitSpacing(
    val none: Dp = 0.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
    /** Comfortable minimum touch target per accessibility guidance. */
    val minTouchTarget: Dp = 48.dp,
)

/** Exposes [VoitSpacing] through the composition so screens read `VoitTheme.spacing`. */
val LocalVoitSpacing = staticCompositionLocalOf { VoitSpacing() }
