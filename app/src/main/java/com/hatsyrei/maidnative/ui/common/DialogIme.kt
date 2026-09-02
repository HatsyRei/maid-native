package com.hatsyrei.maidnative.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * A dialog window shrinks to the space the keyboard leaves, which re-centres its
 * card in that strip and squeezes the contents when they no longer fit. These
 * properties leave the window at full height instead; pair them with
 * [liftAboveIme] so the card still clears the keyboard.
 *
 * Only takes effect on API 30+, where the platform honours the request; below
 * that the window resizes as before.
 */
internal val DialogOverIme = DialogProperties(decorFitsSystemWindows = false)

/**
 * Raises a centred dialog by however much the keyboard covers it, and by nothing
 * at all when it already fits above one.
 */
@Composable
internal fun Modifier.liftAboveIme(margin: Dp = 24.dp): Modifier {
    val screen = LocalView.current.resources.displayMetrics.heightPixels
    val ime = WindowInsets.ime
    return this.layout { measurable, constraints ->
        val card = measurable.measure(constraints)
        // The card is centred, so empty space left below it lifts it by half.
        val lift = (card.height + 2 * (ime.getBottom(this) + margin.roundToPx()) - screen)
            .coerceAtLeast(0)
        layout(card.width, (card.height + lift).coerceAtMost(constraints.maxHeight)) {
            card.place(0, 0)
        }
    }
}
