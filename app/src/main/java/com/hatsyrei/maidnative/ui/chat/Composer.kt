package com.hatsyrei.maidnative.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.ui.icons.ArrowUpwardIcon
import com.hatsyrei.maidnative.ui.theme.LocalNameplate

/**
 * Published so the message list can drop a stale cursor. `stale` is true only
 * once the user has dismissed the keyboard themselves: while it is up, touching
 * the list must neither unfocus nor close it.
 */
@Stable
internal class ComposerFocus {
    var focused by mutableStateOf(false)
        private set
    var keyboardVisible by mutableStateOf(false)
        private set

    val stale: Boolean get() = focused && !keyboardVisible

    internal fun update(focused: Boolean, keyboardVisible: Boolean) {
        this.focused = focused
        this.keyboardVisible = keyboardVisible
    }
}

/**
 * Drops composer focus on the next touch, without consuming it: the tap or
 * scroll that triggers it still behaves normally.
 */
internal fun Modifier.clearFocusOnTouch(
    active: () -> Boolean,
    onClear: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (active()) onClear()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun Composer(
    enabled: Boolean,
    busy: Boolean,
    focus: ComposerFocus,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    nameplate: Painter? = LocalNameplate.current,
) {
    var text by remember { mutableStateOf("") }
    val canSend = enabled && text.trim().isNotEmpty()
    val active = busy || canSend

    // Read here rather than in the scaffold so an IME transition invalidates only
    // the composer.
    var focused by remember { mutableStateOf(false) }
    val keyboardVisible = WindowInsets.isImeVisible
    SideEffect { focus.update(focused = focused, keyboardVisible = keyboardVisible) }

    // Nameplate art fills the resting pill and fades out once the user engages
    // with it (focus, or any text present). The second factor fades it back IN
    // when the painter resolves, so the stored art arriving (settings load, or a
    // custom image finishing its off-thread decode) eases in instead of popping.
    val engagementAlpha by animateFloatAsState(
        targetValue = if (focused || text.isNotEmpty()) 0f else 1f,
        animationSpec = tween(300),
        label = "nameplateAlpha",
    )
    val revealAlpha by animateFloatAsState(
        targetValue = if (nameplate != null) 1f else 0f,
        animationSpec = tween(300),
        label = "nameplateReveal",
    )
    val nameplateAlpha = engagementAlpha * revealAlpha

    // Single full-width pill (RN prompt-input-group): the input spans the width
    // and the send/stop button lives inside the pill at the right end.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (nameplate != null && nameplateAlpha > 0f) {
            Image(
                painter = nameplate,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                alpha = nameplateAlpha,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 120.dp)
                    .onFocusChanged { focused = it.isFocused },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.padding(vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (text.isEmpty()) {
                            Text(
                                text = "Message",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            // Fades to fully transparent rather than the pill colour so the
            // nameplate is not punched out by an opaque circle at rest.
            val fabColor by animateColorAsState(
                targetValue = if (active) Color.White else Color.White.copy(alpha = 0f),
                animationSpec = tween(400),
                label = "fabColor",
            )
            val arrowTint by animateColorAsState(
                targetValue = if (canSend) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(400),
                label = "arrowTint",
            )
            Box(modifier = Modifier.background(fabColor, CircleShape)) {
                IconButton(
                    onClick = {
                        if (busy) {
                            onStop()
                        } else if (canSend) {
                            onSubmit(text)
                            text = ""
                        }
                    },
                    enabled = active,
                ) {
                    Crossfade(targetState = busy, animationSpec = tween(400), label = "sendStop") { streaming ->
                        // Fixed-size, center-aligned box so the small stop square stays
                        // centered during the crossfade instead of snapping from the
                        // top-start corner of the larger send arrow's bounds.
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (streaming) {
                                // Perfectly square = stop (material-icons-core has no Stop glyph).
                                Box(
                                    modifier = Modifier
                                        .size(13.dp)
                                        .background(Color.Black),
                                )
                            } else {
                                Icon(
                                    ArrowUpwardIcon,
                                    contentDescription = "Send",
                                    tint = arrowTint,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
