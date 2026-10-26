package com.hatsyrei.maidnative.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hatsyrei.maidnative.R

/**
 * The app's card dialog, used in place of Material's `AlertDialog`.
 *
 * That one keeps the platform default window width, which in landscape comes
 * out narrower than the full-window edit dialog, and only its body scrolls: with
 * the keyboard up in landscape, title and buttons alone overflow the strip above
 * it and the body gets no room. Here the window spans the screen and the card is
 * placed by [cardFrame]; when the height left is too small for fixed chrome, the
 * whole card scrolls instead, so a focused field can always be brought into view.
 *
 * [text] must not scroll itself: the dialog provides the scroll.
 */
@Composable
internal fun AppDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    /** A secondary action such as Delete, kept apart at the start of the button row. */
    neutralButton: (@Composable () -> Unit)? = null,
    text: @Composable () -> Unit,
) {
    val pane = stringResource(R.string.common_dialog_pane_title)
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BoxWithConstraints(Modifier.cardFrame(onDismissRequest)) {
            // Below this, a fixed title and button row leave the body almost no room.
            val compact = maxHeight < 280.dp
            val scroll = rememberScrollState()
            Surface(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .semantics { paneTitle = pane },
                shape = AlertDialogDefaults.shape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                Column(
                    modifier = Modifier
                        .then(if (compact) Modifier.verticalScroll(scroll) else Modifier)
                        .padding(24.dp),
                ) {
                    CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.titleContentColor) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                            Box(Modifier.padding(bottom = 16.dp)) { title() }
                        }
                    }
                    CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                            Box(
                                Modifier
                                    .then(
                                        if (compact) {
                                            Modifier
                                        } else {
                                            Modifier.weight(1f, fill = false).verticalScroll(scroll)
                                        },
                                    )
                                    .padding(bottom = 24.dp),
                            ) { text() }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        neutralButton?.invoke()
                        Spacer(Modifier.weight(1f))
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

/**
 * Places the card inside the system bars with a margin, centred on the screen
 * while that also clears the keyboard, so opening one lifts the card only as
 * far as it must. The card is capped to the space that leaves.
 */
@Composable
private fun Modifier.cardFrame(onDismiss: () -> Unit): Modifier {
    val dismiss by rememberUpdatedState(onDismiss)
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val ime = WindowInsets.ime
    return this
        // The window spans the screen, so `dismissOnClickOutside` never fires.
        .pointerInput(Unit) { detectTapGestures { dismiss() } }
        .layout { measurable, constraints ->
            val margin = 24.dp.roundToPx()
            val left = bars.getLeft(this, layoutDirection) + margin
            val right = bars.getRight(this, layoutDirection) + margin
            val top = bars.getTop(this) + margin
            val bottom = maxOf(bars.getBottom(this), ime.getBottom(this)) + margin
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val card = measurable.measure(
                Constraints(
                    maxWidth = (width - left - right).coerceAtLeast(0),
                    maxHeight = (height - top - bottom).coerceAtLeast(0),
                ),
            )
            layout(width, height) {
                card.place(
                    ((width - card.width) / 2).coerceAtMost(width - right - card.width).coerceAtLeast(left),
                    ((height - card.height) / 2).coerceAtMost(height - bottom - card.height).coerceAtLeast(top),
                )
            }
        }
        // Taps on the card itself must not reach the dismiss handler above.
        .pointerInput(Unit) { detectTapGestures { } }
}
