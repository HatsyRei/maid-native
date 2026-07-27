package com.hatsyrei.maidnative.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.hatsyrei.maidnative.ui.icons.ArrowUpwardIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Composer(
    enabled: Boolean,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val canSend = enabled && text.trim().isNotEmpty()
    val active = busy || canSend

    // Single full-width pill (RN prompt-input-group): the input spans the width
    // and the send/stop button lives inside the pill at the right end.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(30.dp),
            )
            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .heightIn(max = 120.dp),
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
        val fabColor by animateColorAsState(
            targetValue = if (active) Color.White else MaterialTheme.colorScheme.surfaceContainerHigh,
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
