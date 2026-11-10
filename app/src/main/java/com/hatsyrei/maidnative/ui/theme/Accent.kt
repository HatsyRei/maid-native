package com.hatsyrei.maidnative.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/**
 * Rebuilds the primary family around [accent], and re-hues the fills that are
 * meant to read as accented — the model pill and the selected chat entry.
 *
 * By default menus, dialogs, the composer pill and message cards keep the tuned
 * blue-neutral ramp from [Color], so the chrome doesn't swing colour with the
 * seed. [tintSurfaces] opts into re-hueing that whole ramp too; each tone keeps
 * its saturation and lightness, so the surfaces still stack in the same order.
 * Text tones are never re-hued.
 */
internal fun ColorScheme.withAccent(accent: Color, tintSurfaces: Boolean = false): ColorScheme {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(accent.toArgb(), hsl)
    val hue = hsl[0]
    val saturation = hsl[1]
    // Hue is undefined for an achromatic colour and `colorToHSL` reports it as
    // 0 (red), so rotating the ramp by it would paint white or grey accents
    // brown. Leave the neutrals alone instead.
    val chromatic = saturation >= 0.05f
    // Light accents need a dark "on" colour and vice versa; 0.45 is where a mid
    // tone stops carrying black text comfortably.
    val onLight = accent.luminance() > 0.45f
    val surfaces = if (chromatic && tintSurfaces) withTintedSurfaces(hue) else this
    return surfaces.copy(
        primary = accent,
        onPrimary = if (onLight) tone(hue, saturation, 0.12f) else tone(hue, saturation * 0.2f, 0.97f),
        primaryContainer = tone(hue, saturation, 0.24f),
        onPrimaryContainer = tone(hue, saturation.coerceAtMost(0.55f), 0.90f),
        inversePrimary = tone(hue, saturation, 0.40f),
        primaryFixed = tone(hue, saturation.coerceAtMost(0.7f), 0.90f),
        primaryFixedDim = tone(hue, saturation, 0.80f),
        onPrimaryFixed = tone(hue, saturation, 0.10f),
        onPrimaryFixedVariant = tone(hue, saturation, 0.30f),
        surfaceVariant = if (chromatic) surfaceVariant.rehue(hue) else surfaceVariant,
    )
}

/** The AMOLED blacks are fully desaturated, so [rehue] leaves them black. */
private fun ColorScheme.withTintedSurfaces(hue: Float): ColorScheme = copy(
    surfaceBright = surfaceBright.rehue(hue),
    surfaceContainerLow = surfaceContainerLow.rehue(hue),
    surfaceContainer = surfaceContainer.rehue(hue),
    surfaceContainerHigh = surfaceContainerHigh.rehue(hue),
    surfaceContainerHighest = surfaceContainerHighest.rehue(hue),
)

private fun tone(hue: Float, saturation: Float, lightness: Float): Color =
    Color(ColorUtils.HSLToColor(floatArrayOf(hue, saturation.coerceIn(0f, 1f), lightness)))

/** Rotates hue, keeping saturation and lightness. A fully desaturated tone (the AMOLED black) is returned untouched. */
private fun Color.rehue(hue: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    if (hsl[1] < 0.01f) return this
    return Color(ColorUtils.HSLToColor(floatArrayOf(hue, hsl[1], hsl[2])))
}
