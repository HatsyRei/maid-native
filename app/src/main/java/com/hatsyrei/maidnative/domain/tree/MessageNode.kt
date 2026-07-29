package com.hatsyrei.maidnative.domain.tree

import androidx.compose.runtime.Immutable

/**
 * A message node in a conversation tree. Ported from the `message-nodes` npm
 * package used by the React Native app. Immutable; tree operations return new
 * mapping objects (or the same instance when nothing changes) to mirror the JS
 * referential-equality behavior.
 *
 * Annotated `@Immutable` because the `metadata` map makes Compose infer the
 * class as unstable, which would stop `MessageItem` from skipping whenever any
 * sibling state changed.
 */
@Immutable
data class MessageNode(
    val id: String,
    val role: String,
    val content: String,
    val root: String,
    val parent: String? = null,
    val child: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

/** A conversation graph keyed by node id. Insertion order is significant. */
typealias Mappings = Map<String, MessageNode>
