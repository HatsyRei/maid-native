package com.hatsyrei.maidnative.domain

import com.hatsyrei.maidnative.domain.tree.MessageNode

/**
 * Splits an assistant message into (displayContent, reasoning). Ported from
 * utilities/reasoning.ts. Supports `<think>...</think>` and
 * `<reasoning>...</reasoning>` blocks, including the still-streaming case where
 * the closing tag has not arrived yet.
 */
object Reasoning {

    fun split(node: MessageNode): Pair<String?, String?> = split(node.content)

    fun split(raw: String): Pair<String?, String?> = when {
        raw.contains("<think>") -> splitHelper(raw, "<think>", "</think>")
        raw.contains("<reasoning>") -> splitHelper(raw, "<reasoning>", "</reasoning>")
        else -> raw to null
    }

    private fun splitHelper(
        raw: String,
        openTag: String,
        closeTag: String,
    ): Pair<String?, String?> {
        val content = raw.trim()
        val openIndex = content.indexOf(openTag)
        val closeIndex = content.indexOf(closeTag)

        if (openIndex == -1) return content to null

        if (closeIndex != -1 && closeIndex > openIndex) {
            val start = openIndex + openTag.length
            val reasoning = content.substring(start, closeIndex).trim()
            val before = content.substring(0, openIndex).trim()
            val after = content.substring(closeIndex + closeTag.length).trim()
            var merged: String? = listOf(before, after).filter { it.isNotEmpty() }.joinToString(" ").trim()
            if (merged.isNullOrEmpty()) merged = null
            return merged to reasoning
        }

        val reasoning = content.substring(openIndex + openTag.length).trim()
        return null to reasoning
    }
}
