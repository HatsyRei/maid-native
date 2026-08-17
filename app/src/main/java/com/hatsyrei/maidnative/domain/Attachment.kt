package com.hatsyrei.maidnative.domain

import androidx.compose.runtime.Immutable

/**
 * A file the user attached to an outgoing message, already copied into app
 * storage. [path] is absolute because the transport layer reads the bytes at
 * request time and has no [android.content.Context] to resolve against.
 */
@Immutable
data class Attachment(
    val id: String,
    val kind: Kind,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val path: String,
) {
    enum class Kind { IMAGE, AUDIO, TEXT }
}
