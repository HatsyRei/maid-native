package com.hatsyrei.maidnative.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.hatsyrei.maidnative.ui.markdown.ProvideChatMarkdownStyle

// The RN app is hardcoded dark (useTheme), so this is dark-only: no light
// scheme, no dynamic color.
//
// EVERY role is set explicitly. Anything left to `darkColorScheme()`'s defaults
// falls back to Material's baseline dark palette, whose neutrals are
// purple-tinted (surfaceContainerLow #1D1B20, surfaceContainer #211F26,
// surfaceContainerHigh #2B2930) and visibly clash with the blue seed. The
// replacements are a black-anchored blue-neutral ramp - see Color.kt.
private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    inversePrimary = DarkInversePrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceDim = DarkSurfaceDim,
    surfaceBright = DarkSurfaceBright,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    // Tonal elevation only ever applies to `surface` itself, and lifting the
    // AMOLED canvas off black is exactly what we don't want, so the tint is a
    // no-op by construction. Explicit containers stay at their exact tone.
    surfaceTint = Color.Transparent,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = DarkScrim,
    primaryFixed = DarkPrimaryFixed,
    primaryFixedDim = DarkPrimaryFixedDim,
    onPrimaryFixed = DarkOnPrimaryFixed,
    onPrimaryFixedVariant = DarkOnPrimaryFixedVariant,
    secondaryFixed = DarkSecondaryFixed,
    secondaryFixedDim = DarkSecondaryFixedDim,
    onSecondaryFixed = DarkOnSecondaryFixed,
    onSecondaryFixedVariant = DarkOnSecondaryFixedVariant,
    tertiaryFixed = DarkTertiaryFixed,
    tertiaryFixedDim = DarkTertiaryFixedDim,
    onTertiaryFixed = DarkOnTertiaryFixed,
    onTertiaryFixedVariant = DarkOnTertiaryFixedVariant,
)

@Composable
fun MaidNativeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography,
    ) {
        // Built here (above every state-reading composable) so the markdown
        // typography/padding/annotator are allocated once per activity instead
        // of once per bubble per recomposition.
        ProvideChatMarkdownStyle(content)
    }
}
