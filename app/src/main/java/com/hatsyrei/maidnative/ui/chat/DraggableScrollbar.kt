package com.hatsyrei.maidnative.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Port of the RN chat scroll thumb (app/chat/index.tsx): a 48dp draggable pill
// pinned to the right edge, revealed on scroll/drag and auto-hidden after an
// idle timeout. Overlays a LazyColumn and maps drag distance to scroll offset.
private const val IDLE_TIMEOUT_MS = 1200L
private const val FADE_DURATION_MS = 300
private val THUMB_HEIGHT = 48.dp
// Narrow, transparent grab strip along the right edge; kept small so it does not
// intercept normal taps/scrolls on message content near the edge.
private val HIT_WIDTH = 22.dp
private val ITEM_SPACING = 12.dp

private class ThumbMetrics(
    val visible: Boolean,
    val thumbTopPx: Float,
    val thumbTravelPx: Float,
    val maxScrollPx: Float,
)

private val HIDDEN = ThumbMetrics(false, 0f, 0f, 0f)

// Estimate scroll geometry for a variable-height LazyColumn. Item sizes are
// cached by index as they are measured so the total-content estimate (and thus
// the thumb position) stays stable while scrolling between messages of
// different sizes, instead of jumping as the visible-average shifts. The
// trailing bottom spacer (viewport - 96dp, added by ChatScaffold so the last
// message can scroll up) is a real list item and is seeded here so it is
// accounted for before it is ever measured.
private fun computeMetrics(
    state: LazyListState,
    thumbHeightPx: Float,
    spacingPx: Float,
    trailingSpacerPx: Float,
    cache: MutableMap<Int, Int>,
): ThumbMetrics {
    val info = state.layoutInfo
    val items = info.visibleItemsInfo
    val viewportPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val totalItems = info.totalItemsCount
    if (items.isEmpty() || viewportPx <= 0f || totalItems <= 0) return HIDDEN

    items.forEach { cache[it.index] = it.size }
    val avg = if (cache.isEmpty()) 0f else cache.values.average().toFloat()
    val lastIndex = totalItems - 1
    fun sizeAt(index: Int): Float {
        cache[index]?.let { return it.toFloat() }
        if (index == lastIndex && trailingSpacerPx > 0f) return trailingSpacerPx
        return avg
    }

    var contentPx = (info.beforeContentPadding + info.afterContentPadding).toFloat()
    for (i in 0 until totalItems) contentPx += sizeAt(i)
    contentPx += spacingPx * (totalItems - 1).coerceAtLeast(0)

    val maxScroll = contentPx - viewportPx
    if (maxScroll <= 0f) return HIDDEN

    var scrolled = state.firstVisibleItemScrollOffset.toFloat()
    for (i in 0 until state.firstVisibleItemIndex) scrolled += sizeAt(i) + spacingPx
    scrolled = scrolled.coerceIn(0f, maxScroll)

    val travel = (viewportPx - thumbHeightPx).coerceAtLeast(0f)
    val top = (scrolled / maxScroll) * travel
    return ThumbMetrics(true, top, travel, maxScroll)
}

@Composable
fun DraggableScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    trailingSpacerHeight: Dp = 0.dp,
) {
    val density = LocalDensity.current
    val thumbHeightPx = with(density) { THUMB_HEIGHT.toPx() }
    val spacingPx = with(density) { ITEM_SPACING.toPx() }
    val trailingSpacerPx = with(density) { trailingSpacerHeight.toPx() }
    val sizeCache = remember(listState) { mutableMapOf<Int, Int>() }

    val metrics by remember(listState) {
        derivedStateOf {
            computeMetrics(listState, thumbHeightPx, spacingPx, trailingSpacerPx, sizeCache)
        }
    }

    val opacity = remember { Animatable(0f) }
    var revealTick by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }

    // Reveal the thumb whenever the list scrolls.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { revealTick++ }
    }

    // Fade the thumb in on reveal, then out after an idle timeout (unless dragging).
    LaunchedEffect(revealTick, dragging, metrics.visible) {
        if (!metrics.visible) {
            opacity.snapTo(0f)
            return@LaunchedEffect
        }
        opacity.snapTo(1f)
        if (!dragging) {
            delay(IDLE_TIMEOUT_MS)
            opacity.animateTo(0f, tween(FADE_DURATION_MS))
        }
    }

    if (!metrics.visible) return

    val interactive = dragging || opacity.value > 0.05f

    Box(modifier = modifier.fillMaxHeight().width(HIT_WIDTH)) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, metrics.thumbTopPx.roundToInt()) }
                .height(THUMB_HEIGHT)
                .width(HIT_WIDTH)
                .graphicsLayer { alpha = opacity.value }
                .then(
                    if (interactive) {
                        Modifier.pointerInput(listState) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragging = true
                                    revealTick++
                                },
                                onDragEnd = {
                                    dragging = false
                                    revealTick++
                                },
                                onDragCancel = {
                                    dragging = false
                                    revealTick++
                                },
                            ) { change, dragAmount ->
                                change.consume()
                                val m = metrics
                                if (m.thumbTravelPx > 0f) {
                                    // Map finger travel (thumb space) to content
                                    // pixels and apply synchronously so the thumb
                                    // tracks the finger exactly 1:1.
                                    val contentDelta = (dragAmount / m.thumbTravelPx) * m.maxScrollPx
                                    listState.dispatchRawDelta(contentDelta)
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.CenterEnd,
        ) {
            // The visible pill: a thin rounded bar flush to the right edge.
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .graphicsLayer { alpha = 0.9f }
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}
