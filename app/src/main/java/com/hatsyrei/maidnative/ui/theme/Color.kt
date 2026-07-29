package com.hatsyrei.maidnative.ui.theme

import androidx.compose.ui.graphics.Color

// Seed accent used by the React Native app (createColorScheme('#2196F3', 'dark')).
val Seed = Color(0xFF2196F3)

// ---------------------------------------------------------------------------
// Surfaces
//
// AMOLED-first ramp: the canvas is literal #000000 so unlit pixels stay off,
// and every container tone above it is a *blue-tinted* near-black (hue ~215)
// rather than Material's baseline dark neutrals, which are purple-tinted
// (#1D1B20 / #211F26 / #2B2930, from PaletteTokens.Neutral*) and clash with the
// blue seed.
// ---------------------------------------------------------------------------
val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF000000)
val DarkSurfaceDim = Color(0xFF000000)
val DarkSurfaceBright = Color(0xFF232B36)

val DarkSurfaceContainerLowest = Color(0xFF000000)
val DarkSurfaceContainerLow = Color(0xFF0C1015) // message cards
val DarkSurfaceContainer = Color(0xFF11161D) // pop-up menus
val DarkSurfaceContainerHigh = Color(0xFF171D26) // composer pill, dialogs
val DarkSurfaceContainerHighest = Color(0xFF1F2630)

val DarkSurfaceVariant = Color(0xFF2A333F) // model pill, selected chat pill

val DarkOnBackground = Color(0xFFE2E6EC)
val DarkOnSurface = Color(0xFFE2E6EC)
val DarkOnSurfaceVariant = Color(0xFFB9C2CE)

val DarkOutline = Color(0xFF7C8794)
val DarkOutlineVariant = Color(0xFF333C47) // scrollbar thumb, dividers

val DarkInverseSurface = Color(0xFFE2E6EC)
val DarkInverseOnSurface = Color(0xFF171D26)
val DarkScrim = Color(0xFF000000)

// ---------------------------------------------------------------------------
// Accents (all hue-aligned to the blue seed; no purple anywhere)
// ---------------------------------------------------------------------------
val DarkPrimary = Color(0xFF90CAF9)
val DarkOnPrimary = Color(0xFF00344C)
val DarkPrimaryContainer = Color(0xFF004C6B)
val DarkOnPrimaryContainer = Color(0xFFC9E6FF)
val DarkInversePrimary = Color(0xFF00639A)

val DarkSecondary = Color(0xFFB6C9D8)
val DarkOnSecondary = Color(0xFF21333E)
val DarkSecondaryContainer = Color(0xFF374955)
val DarkOnSecondaryContainer = Color(0xFFD2E5F5)

val DarkTertiary = Color(0xFFA0CFD0)
val DarkOnTertiary = Color(0xFF003739)
val DarkTertiaryContainer = Color(0xFF1F4E50)
val DarkOnTertiaryContainer = Color(0xFFBCEBEC)

// Red A100 from the same classic Material palette the seed comes from, so
// destructive actions read as red instead of the baseline's pink-purple.
val DarkError = Color(0xFFFF8A80)
val DarkOnError = Color(0xFF4E0F0A)
val DarkErrorContainer = Color(0xFF8C1D18)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

// ---------------------------------------------------------------------------
// Fixed accents (identical in light and dark by definition; unused today, but
// overridden so nothing can fall back to the baseline purple palette).
// ---------------------------------------------------------------------------
val DarkPrimaryFixed = Color(0xFFC9E6FF)
val DarkPrimaryFixedDim = Color(0xFF90CAF9)
val DarkOnPrimaryFixed = Color(0xFF001E2E)
val DarkOnPrimaryFixedVariant = Color(0xFF004C6B)

val DarkSecondaryFixed = Color(0xFFD2E5F5)
val DarkSecondaryFixedDim = Color(0xFFB6C9D8)
val DarkOnSecondaryFixed = Color(0xFF0B1D28)
val DarkOnSecondaryFixedVariant = Color(0xFF374955)

val DarkTertiaryFixed = Color(0xFFBCEBEC)
val DarkTertiaryFixedDim = Color(0xFFA0CFD0)
val DarkOnTertiaryFixed = Color(0xFF002021)
val DarkOnTertiaryFixedVariant = Color(0xFF1F4E50)
