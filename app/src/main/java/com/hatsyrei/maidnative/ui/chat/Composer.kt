package com.hatsyrei.maidnative.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.hatsyrei.maidnative.domain.Attachment
import com.hatsyrei.maidnative.domain.Modalities
import com.hatsyrei.maidnative.ui.icons.AddIcon
import com.hatsyrei.maidnative.ui.icons.ArrowUpwardIcon
import com.hatsyrei.maidnative.ui.icons.AudiotrackIcon
import com.hatsyrei.maidnative.ui.icons.CloseIcon
import com.hatsyrei.maidnative.ui.icons.DescriptionIcon
import com.hatsyrei.maidnative.ui.icons.ImageIcon
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
    attachments: List<Attachment>,
    modalities: Modalities,
    onAttach: (Uri, Attachment.Kind) -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    nameplate: Painter? = LocalNameplate.current,
) {
    val input = rememberTextFieldState()
    val text = input.text
    val canSend = enabled && (text.trim().isNotEmpty() || attachments.isNotEmpty())
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
        targetValue = if (focused || text.isNotEmpty() || attachments.isNotEmpty()) 0f else 1f,
        animationSpec = tween(700),
        label = "nameplateAlpha",
    )
    val revealAlpha by animateFloatAsState(
        targetValue = if (nameplate != null) 1f else 0f,
        animationSpec = tween(700),
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        ) {
            if (attachments.isNotEmpty()) {
                AttachmentChips(
                    attachments = attachments,
                    onRemove = onRemoveAttachment,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
                    onOpen = onOpenAttachment,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                AttachButton(enabled = enabled, modalities = modalities, onPick = onAttach)
                BasicTextField(
                    state = input,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(max = 120.dp)
                        .onFocusChanged { focused = it.isFocused },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    decorator = TextFieldDecorator { innerTextField ->
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
                                onSubmit(text.toString())
                                input.clearText()
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
}

/**
 * The `+` at the head of the pill and its attachment menu.
 *
 * A row is greyed out only when the endpoint positively said the model cannot
 * take that modality. Silence leaves it live: most OpenAI-compatible servers
 * describe nothing at all, and a rejected request is a better outcome than a
 * button that can never be pressed. Text files are always offered because they
 * are inlined as prompt text and need no modality at all.
 */
@Composable
private fun AttachButton(
    enabled: Boolean,
    modalities: Modalities,
    onPick: (Uri, Attachment.Kind) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onPick(it, Attachment.Kind.IMAGE) }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onPick(it, Attachment.Kind.AUDIO) }
    }
    val textPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onPick(it, Attachment.Kind.TEXT) }
    }

    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(
                AddIcon,
                contentDescription = "Add attachment",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
            val provider = remember(gapPx) { AboveAnchorPositionProvider(gapPx) }
            Popup(
                popupPositionProvider = provider,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                MenuSurface(Modifier.widthIn(min = 148.dp, max = 176.dp)) {
                    MenuOption(
                        text = "Image",
                        trailingIcon = { Icon(ImageIcon, contentDescription = null) },
                        enabled = modalities.vision.permitted,
                        onClick = {
                            open = false
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    )
                    MenuOption(
                        text = "Audio",
                        trailingIcon = { Icon(AudiotrackIcon, contentDescription = null) },
                        enabled = modalities.audio.permitted,
                        onClick = {
                            open = false
                            audioPicker.launch(AUDIO_MIME_TYPES)
                        },
                    )
                    MenuOption(
                        text = "Text file",
                        trailingIcon = { Icon(DescriptionIcon, contentDescription = null) },
                        onClick = {
                            open = false
                            textPicker.launch(TEXT_MIME_TYPES)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun AttachmentChips(
    attachments: List<Attachment>,
    onRemove: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
    onOpen: ((Attachment) -> Unit)? = null,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (attachment in attachments) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = attachment.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 140.dp)
                            .then(
                                if (onOpen == null) {
                                    Modifier
                                } else {
                                    Modifier.clickable { onOpen(attachment) }
                                },
                            ),
                    )
                    IconButton(
                        onClick = { onRemove(attachment) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            CloseIcon,
                            contentDescription = "Remove ${attachment.name}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Places the menu directly above the `+` button, since the composer sits at the bottom. */
private class AboveAnchorPositionProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        (anchorBounds.top - gap - popupContentSize.height)
            .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
}

// llama.cpp decodes only wav and mp3, so offering anything else guarantees a
// rejected request. Providers disagree on the wav spelling, hence both.
private val AUDIO_MIME_TYPES = arrayOf("audio/wav", "audio/x-wav", "audio/mpeg")
private val TEXT_MIME_TYPES = arrayOf("text/*", "application/json")
