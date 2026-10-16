package com.voit.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * In-app theme override. [SYSTEM] follows the Android system setting (default);
 * [LIGHT] / [DARK] let the user pin a mode regardless of the system. Both painted modes
 * remain strictly monochrome.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

private val VoitLightColors = lightColorScheme(
    primary = VoitBlack,
    onPrimary = VoitWhite,
    secondary = VoitGrey600,
    onSecondary = VoitWhite,
    background = VoitWhite,
    onBackground = VoitBlack,
    surface = VoitWhite,
    onSurface = VoitBlack,
    surfaceVariant = VoitGrey100,
    onSurfaceVariant = VoitGrey600,
    outline = VoitGrey400,
    outlineVariant = VoitGrey200,
    // Keep "error" monochrome too: no red accent. State is signaled by icon/label/shape.
    error = VoitBlack,
    onError = VoitWhite,
)

private val VoitDarkColors = darkColorScheme(
    primary = VoitWhite,
    onPrimary = VoitBlack,
    secondary = VoitGrey400,
    onSecondary = VoitBlack,
    background = VoitBlack,
    onBackground = VoitWhite,
    surface = VoitGrey900,
    onSurface = VoitWhite,
    surfaceVariant = VoitGrey800,
    onSurfaceVariant = VoitGrey200,
    outline = VoitGrey600,
    outlineVariant = VoitGrey800,
    error = VoitWhite,
    onError = VoitBlack,
)

/**
 * Monochrome Compose theme for Voit. No dynamic/accent colors; depth comes from greyscale
 * tone, type weight, spacing, and shape only.
 *
 * @param themeMode optional in-app override; defaults to following the system theme.
 */
@Composable
fun VoitTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalVoitSpacing provides VoitSpacing()) {
        MaterialTheme(
            colorScheme = if (darkTheme) VoitDarkColors else VoitLightColors,
            typography = VoitTypography,
            shapes = VoitShapes,
            content = content,
        )
    }
}

/** Design-token accessors, read as `VoitTheme.spacing` inside composables. */
object VoitTheme {
    val spacing: VoitSpacing
        @Composable
        get() = LocalVoitSpacing.current
}
