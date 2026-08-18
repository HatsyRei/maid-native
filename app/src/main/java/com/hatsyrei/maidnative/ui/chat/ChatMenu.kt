package com.hatsyrei.maidnative.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * A single pop-up menu row that dims its content while pressed instead of
 * drawing a full-width highlight (no ripple/state-layer). Shared by the message,
 * drawer, and model-picker menus.
 */
@Composable
internal fun MenuOption(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val contentAlpha = when {
        !enabled -> 0.38f
        pressed -> 0.5f
        else -> 1f
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .alpha(contentAlpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailingIcon != null) {
            Spacer(Modifier.width(12.dp))
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                trailingIcon()
            }
        }
    }
}

/**
 * The shared popup chrome for every menu in the app: rounded container surface
 * plus the option-list padding. [modifier] sizes (and, for long lists, scrolls)
 * the option column.
 */
@Composable
internal fun MenuSurface(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            content = content,
        )
    }
}

/**
 * Single-slot ownership for the app's long-press context menus.
 *
 * Compose has no cross-node gesture arbitration: two fingers landing on two
 * long-pressable items are hit-tested and dispatched independently, so each
 * item's `detectTapGestures` arms its own long-press timer and, with per-item
 * `menuOpen` flags, both menus would open. Routing every menu through one
 * `openId` makes the second long-press replace the first instead.
 */
@Stable
internal class MenuController {
    var openId: String? by mutableStateOf(null)
        private set

    fun open(id: String) {
        openId = id
    }

    fun close(id: String) {
        if (openId == id) openId = null
    }
}

internal val LocalMenuController = staticCompositionLocalOf { MenuController() }

/**
 * One item's claim on the shared menu slot, plus the press bookkeeping its
 * long-press gesture needs.
 */
@Stable
internal class TapMenuState(private val menus: MenuController, private val id: String) {
    /**
     * `derivedStateOf` so the owning item recomposes when *its* menu toggles,
     * not every time any other item's does.
     */
    val expanded: Boolean by derivedStateOf { menus.openId == id }

    /** Where the long-press landed, so the popup can center on the finger. */
    var touchOffset by mutableStateOf(Offset.Zero)
        private set

    /** Latched for the duration of the touch; each item draws its own cue from it. */
    var pressed by mutableStateOf(false)
        private set

    fun close() = menus.close(id)

    internal fun setPressed(value: Boolean) {
        pressed = value
    }

    internal fun openAt(offset: Offset) {
        touchOffset = offset
        menus.open(id)
    }
}

@Composable
internal fun rememberTapMenu(id: String): TapMenuState {
    val menus = LocalMenuController.current
    val state = remember(menus, id) { TapMenuState(menus, id) }
    // Lists dispose off-screen items; drop the slot so it can't reopen on scroll back.
    DisposableEffect(state) { onDispose { state.close() } }
    return state
}

/**
 * Press-and-hold to claim [state]'s menu slot. Keyed on [state] alone so a new
 * [onTap] closure per recomposition doesn't restart the gesture detector
 * mid-touch.
 */
@Composable
internal fun Modifier.tapMenuGestures(
    state: TapMenuState,
    onTap: () -> Unit = {},
): Modifier {
    val tap by rememberUpdatedState(onTap)
    return pointerInput(state) {
        detectTapGestures(
            onPress = {
                state.setPressed(true)
                tryAwaitRelease()
                state.setPressed(false)
            },
            onTap = { tap() },
            onLongPress = { state.openAt(it) },
        )
    }
}

/**
 * A context menu that pops up centered horizontally on the user's touch point
 * (mirrors the RN app anchoring a zero-size rect at pageX/pageY). Uses a raw
 * [Popup] with a custom position provider because [androidx.compose.material3.DropdownMenu]
 * only supports anchor-relative offsets, which drift to screen edges for wide anchors.
 */
@Composable
internal fun TapContextMenu(
    expanded: Boolean,
    touchOffset: Offset,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val provider = remember(touchOffset) {
        TapMenuPositionProvider(IntOffset(touchOffset.x.roundToInt(), touchOffset.y.roundToInt()))
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        MenuSurface(Modifier.widthIn(min = 168.dp, max = 172.dp), content)
    }
}

/**
 * Centers the popup on [touch] (relative to the popup's anchor) both
 * horizontally and vertically, clamped inside the window.
 */
private class TapMenuPositionProvider(private val touch: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left + touch.x - popupContentSize.width / 2
        val y = anchorBounds.top + touch.y - popupContentSize.height / 2
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}
