package com.hatsyrei.maidnative.domain

import androidx.compose.runtime.Immutable

/**
 * Tri-state on purpose: "the endpoint never said" is not the same answer as
 * "no". Only llama.cpp-style servers advertise modalities at all, so treating
 * silence as a denial would permanently grey out attachments against every
 * plain OpenAI-compatible endpoint. A rejected request is the better failure.
 */
enum class Support {
    YES,
    NO,
    UNKNOWN,
    ;

    /** Offer the option unless the server positively denied it. */
    val permitted: Boolean get() = this != NO
}

/** What the active model accepts as *input*, beyond text. */
@Immutable
data class Modalities(
    val vision: Support = Support.UNKNOWN,
    val audio: Support = Support.UNKNOWN,
) {
    companion object {
        val UNKNOWN = Modalities()
    }
}
