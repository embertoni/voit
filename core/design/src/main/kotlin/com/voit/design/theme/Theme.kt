package com.voit.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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
)

private val VoitTypography = Typography()

/**
 * Monochrome Compose theme for Voit. No dynamic/accent colors.
 */
@Composable
fun VoitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) VoitDarkColors else VoitLightColors,
        typography = VoitTypography,
        content = content,
    )
}
