package com.hatsyrei.maidnative.ui.chat

import com.hatsyrei.maidnative.domain.TurnStats
import kotlin.math.roundToInt

internal const val UNKNOWN = "N/A"

internal fun Int.formatted(): String = "%,d".format(this)

internal fun Long?.duration(): String = when {
    this == null -> UNKNOWN
    this < 1000 -> "$this ms"
    this < 60_000 -> "%.1f s".format(this / 1000.0)
    else -> "${this / 60_000} m ${(this % 60_000) / 1000} s"
}

internal fun formatTokensPerSecond(rate: Double): String =
    if (rate >= 10) "${rate.roundToInt()} tok/s" else "%.1f tok/s".format(rate)

/**
 * The footnote under an assistant reply: only what that turn actually measured,
 * or null when nothing was. Every part comes from metadata already stored, so
 * this costs no extra request and no extra column.
 *
 * An edited body carries `≥` or `≤` rather than a new number — it is longer or
 * shorter than what was counted, and that is all we honestly know, so it shows
 * no rate either. A stopped reply never shows a rate, and shows a count only
 * where the endpoint had already sent one mid-stream.
 */
internal fun formatTurnFootnote(stats: TurnStats, content: String): String? {
    val parts = buildList {
        stats.completionTokens?.let {
            val qualifier = when (stats.bound(content)) {
                TurnStats.Bound.AT_LEAST -> "\u2265 "
                TurnStats.Bound.AT_MOST -> "\u2264 "
                TurnStats.Bound.EXACT -> ""
            }
            add("$qualifier${it.formatted()} tokens")
        }
        stats.genMs?.let { add(it.duration()) }
        stats.tokensPerSecond(content)?.let { add(formatTokensPerSecond(it)) }
        // Otherwise a stopped reply reads as a slow one.
        if (stats.stopped) add("stopped")
    }
    return parts.joinToString(" \u00b7 ").ifEmpty { null }
}
