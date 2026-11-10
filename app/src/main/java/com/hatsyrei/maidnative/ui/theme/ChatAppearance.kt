package com.hatsyrei.maidnative.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.hatsyrei.maidnative.data.prefs.SettingsRepository

/** How message cards are drawn, provided once by the theme so the thread needs no extra parameters. */
@Immutable
data class ChatAppearance(
    /** Fill for message cards while surfaces are tinted, or null for the plain card tone. */
    val bubbleColor: Color? = null,
    /** Null when profile pictures are hidden. */
    val avatarSize: Dp? = SettingsRepository.DEFAULT_AVATAR_SIZE.dp,
)

val LocalChatAppearance = compositionLocalOf { ChatAppearance() }

/**
 * How far tinted message cards are lifted above the (already tinted) card tone,
 * so the colour reads on them. Kept under the step to `surfaceContainer`, so
 * cards still sit below menus and dialogs.
 */
private const val BUBBLE_LIFT = 0.06f

internal fun liftedBubble(card: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(card.toArgb(), hsl)
    hsl[2] = (hsl[2] + BUBBLE_LIFT).coerceAtMost(1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

/** The message card fill: the tinted bubble when enabled, else the neutral container. */
@Composable
@ReadOnlyComposable
fun chatBubbleColor(): Color =
    LocalChatAppearance.current.bubbleColor ?: MaterialTheme.colorScheme.surfaceContainerLow
