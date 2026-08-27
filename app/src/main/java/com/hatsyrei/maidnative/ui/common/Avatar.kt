package com.hatsyrei.maidnative.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.hatsyrei.maidnative.data.store.AvatarStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes [role]'s stored profile picture off the main thread. [stamp] is 0 when
 * none is set, and changes on each import so the previous decode is dropped.
 */
@Composable
fun rememberAvatar(role: AvatarStore.Role, stamp: Long): Painter? {
    val context = LocalContext.current
    val painter by produceState<Painter?>(null, role, stamp) {
        value = if (stamp == 0L) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    AvatarStore(context).decode(role)?.asImageBitmap()?.let(::BitmapPainter)
                }.getOrNull()
            }
        }
    }
    return painter
}

/** Circular profile picture, falling back to the first character of [name]. */
@Composable
fun Avatar(
    painter: Painter?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            // No plate behind a picture: a transparent PNG should show whatever
            // sits behind the circle rather than a tinted disc.
            .background(
                if (painter == null) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    Color.Transparent
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            val initial = remember(name) {
                val trimmed = name.trim()
                // Taking one *char* would split an emoji or any other astral
                // codepoint into half a surrogate pair.
                if (trimmed.isEmpty()) {
                    ""
                } else {
                    trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).uppercase()
                }
            }
            Text(
                text = initial,
                // Sized off the circle rather than the type scale: the circle is
                // a fixed dp, so a large font scale would overflow it.
                fontSize = (size.value * INITIAL_RATIO).sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private const val INITIAL_RATIO = 0.44f
