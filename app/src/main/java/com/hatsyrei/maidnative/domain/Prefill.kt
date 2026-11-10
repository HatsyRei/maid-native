package com.hatsyrei.maidnative.domain

import com.hatsyrei.maidnative.domain.tree.MessageNode

/**
 * The text a reply is made to start with. Kept on the user message it answers,
 * like that message's attachments, so a regenerated reply starts the same way.
 */
object Prefill {
    private const val KEY = "prefill"

    fun of(node: MessageNode?): String = node?.metadata?.get(KEY) as? String ?: ""

    fun writeInto(prefill: String, metadata: Map<String, Any?>): Map<String, Any?> =
        if (prefill.isEmpty()) metadata - KEY else metadata + (KEY to prefill)
}
