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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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

/**
 * Item heights cached by index, with a running total.
 *
 * The total is maintained incrementally so the content-size estimate is O(1).
 * It previously summed every index and re-averaged the whole map on each read,
 * i.e. once per frame while scrolling and once per streamed token while
 * generating.
 */
private class ItemSizeCache {
    private val sizes = HashMap<Int, Int>()
    private var maxIndexRecorded = -1

    var measuredSum = 0L
        private set

    val measuredCount: Int get() = sizes.size

    fun record(index: Int, size: Int) {
        val previous = sizes.put(index, size)
        measuredSum += size - (previous ?: 0)
        if (index > maxIndexRecorded) maxIndexRecorded = index
    }

    fun sizeAt(index: Int): Int? = sizes[index]

    /** Drop entries for indices that no longer exist (e.g. a deleted message). */
    fun trimTo(count: Int) {
        if (maxIndexRecorded < count) return
        val iterator = sizes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key >= count) {
                measuredSum -= entry.value
                iterator.remove()
            }
        }
        maxIndexRecorded = count - 1
    }
}

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
    cache: ItemSizeCache,
): ThumbMetrics {
    val info = state.layoutInfo
    val items = info.visibleItemsInfo
    val viewportPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val totalItems = info.totalItemsCount
    if (items.isEmpty() || viewportPx <= 0f || totalItems <= 0) return HIDDEN

    cache.trimTo(totalItems)
    items.forEach { cache.record(it.index, it.size) }

    val measured = cache.measuredCount
    val avg = if (measured == 0) 0f else cache.measuredSum.toFloat() / measured
    val lastIndex = totalItems - 1
    fun sizeAt(index: Int): Float {
        cache.sizeAt(index)?.let { return it.toFloat() }
        if (index == lastIndex && trailingSpacerPx > 0f) return trailingSpacerPx
        return avg
    }

    // Measured items contribute their exact total; the rest are estimated at the
    // running average, with the not-yet-measured trailing spacer seeded at its
    // known height.
    var contentPx = (info.beforeContentPadding + info.afterContentPadding).toFloat()
    contentPx += cache.measuredSum.toFloat()
    var unmeasured = totalItems - measured
    if (unmeasured > 0 && trailingSpacerPx > 0f && cache.sizeAt(lastIndex) == null) {
        contentPx += trailingSpacerPx
        unmeasured--
    }
    contentPx += unmeasured * avg
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

/**
 * @param resetKey identifies the content being scrolled. The measured-size cache
 * is keyed by item index, so it must be dropped when those indices start meaning
 * something else (a conversation switch) — otherwise the thumb inherits the
 * previous chat's geometry and the map grows for the life of the screen.
 */
@Composable
fun DraggableScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    trailingSpacerHeight: Dp = 0.dp,
    resetKey: Any? = null,
) {
    val density = LocalDensity.current
    val thumbHeightPx = with(density) { THUMB_HEIGHT.toPx() }
    val spacingPx = with(density) { ITEM_SPACING.toPx() }
    val trailingSpacerPx = with(density) { trailingSpacerHeight.toPx() }
    val sizeCache = remember(listState, resetKey) { ItemSizeCache() }

    val metrics = remember(listState, sizeCache, thumbHeightPx, spacingPx, trailingSpacerPx) {
        derivedStateOf {
            computeMetrics(listState, thumbHeightPx, spacingPx, trailingSpacerPx, sizeCache)
        }
    }
    // Only the visibility flag is read during composition. The thumb offset
    // changes on every scrolled pixel and is instead read in the layout phase
    // (`offset {}`) and lazily by the drag handler, so scrolling no longer
    // recomposes this subtree at all.
    val visible by remember(metrics) { derivedStateOf { metrics.value.visible } }

    val opacity = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }

    // A single long-lived collector. The previous version restarted a
    // `LaunchedEffect` keyed on a scroll-derived counter, which cost a
    // recomposition plus an effect teardown and relaunch for every scroll
    // position change during a fling.
    LaunchedEffect(listState, opacity) {
        combine(
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset },
            snapshotFlow { dragging },
            snapshotFlow { metrics.value.visible },
        ) { _, isDragging, isVisible -> isDragging to isVisible }
            .collectLatest { (isDragging, isVisible) ->
                if (!isVisible) {
                    opacity.snapTo(0f)
                    return@collectLatest
                }
                opacity.snapTo(1f)
                if (!isDragging) {
                    delay(IDLE_TIMEOUT_MS)
                    opacity.animateTo(0f, tween(FADE_DURATION_MS))
                }
            }
    }

    if (!visible) return

    // Derived so the pointer input is attached/detached only when the boolean
    // actually flips, instead of recomposing on every frame of the fade.
    val interactive by remember(opacity) {
        derivedStateOf { dragging || opacity.value > 0.05f }
    }

    Box(modifier = modifier.fillMaxHeight().width(HIT_WIDTH)) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, metrics.value.thumbTopPx.roundToInt()) }
                .height(THUMB_HEIGHT)
                .width(HIT_WIDTH)
                .graphicsLayer { alpha = opacity.value }
                .then(
                    if (interactive) {
                        Modifier.pointerInput(listState) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = true },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, dragAmount ->
                                change.consume()
                                val m = metrics.value
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
