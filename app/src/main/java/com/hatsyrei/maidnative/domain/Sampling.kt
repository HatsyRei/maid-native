package com.hatsyrei.maidnative.domain

import java.util.Locale
import kotlin.math.roundToInt

/**
 * A sampling or penalty field the request may carry.
 *
 * [fallback] is only ever displayed. llama.cpp reads a model's recommended
 * sampling out of its GGUF metadata and applies it to every field the request
 * leaves out, so a value we send is not "the default" — it costs the user
 * whatever the model author, and their own server flags, asked for.
 */
enum class SamplingParam(
    val wire: String,
    val label: String,
    val min: Float,
    val max: Float,
    val fallback: Float,
    val integral: Boolean = false,
    /** Outside the OpenAI spec: a strict endpoint rejects the whole request over it. */
    val extension: Boolean = false,
    val summary: String,
) {
    TEMPERATURE(
        wire = "temperature",
        label = "Temperature",
        min = 0f,
        max = 2f,
        fallback = 0.8f,
        summary = "Flattens or sharpens the odds. 0 always takes the likeliest token.",
    ),
    TOP_P(
        wire = "top_p",
        label = "Top P",
        min = 0f,
        max = 1f,
        fallback = 0.95f,
        summary = "Keeps the likeliest tokens up to this cumulative probability. 1 disables it.",
    ),
    TOP_K(
        wire = "top_k",
        label = "Top K",
        min = 0f,
        max = 100f,
        fallback = 40f,
        integral = true,
        extension = true,
        summary = "Keeps only the K likeliest tokens. 0 disables it.",
    ),
    MIN_P(
        wire = "min_p",
        label = "Min P",
        min = 0f,
        max = 1f,
        fallback = 0.05f,
        extension = true,
        summary = "Drops tokens below this fraction of the likeliest token's odds. 0 disables it.",
    ),
    FREQUENCY_PENALTY(
        wire = "frequency_penalty",
        label = "Frequency penalty",
        min = -2f,
        max = 2f,
        fallback = 0f,
        summary = "Penalises a token by how often it has already appeared.",
    ),
    PRESENCE_PENALTY(
        wire = "presence_penalty",
        label = "Presence penalty",
        min = -2f,
        max = 2f,
        fallback = 0f,
        summary = "Penalises any token that has appeared at all, pushing toward new topics.",
    ),
    ;

    /** Snaps [value] to this field's range and precision. */
    fun round(value: Float): Float {
        val clamped = value.coerceIn(min, max)
        return if (integral) clamped.roundToInt().toFloat() else (clamped * 100f).roundToInt() / 100f
    }

    // Fixed locale: the value is read next to the wire field name it sets, and a
    // comma decimal separator would not be what gets sent.
    fun format(value: Float): String =
        if (integral) value.roundToInt().toString() else String.format(Locale.US, "%.2f", value)
}

/**
 * The sampling fields the user has taken over. Every one is absent by default,
 * and an absent field is simply not sent — see [SamplingParam.fallback].
 *
 * Fields are spelled out rather than held in a map so the type stays stable for
 * Compose and `top_k` keeps its integer shape on the wire.
 */
data class Sampling(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val minP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
) {
    operator fun get(param: SamplingParam): Float? = when (param) {
        SamplingParam.TEMPERATURE -> temperature
        SamplingParam.TOP_P -> topP
        SamplingParam.TOP_K -> topK?.toFloat()
        SamplingParam.MIN_P -> minP
        SamplingParam.FREQUENCY_PENALTY -> frequencyPenalty
        SamplingParam.PRESENCE_PENALTY -> presencePenalty
    }

    /** [value] null clears the override, handing the field back to the endpoint. */
    fun with(param: SamplingParam, value: Float?): Sampling {
        val v = value?.let(param::round)
        return when (param) {
            SamplingParam.TEMPERATURE -> copy(temperature = v)
            SamplingParam.TOP_P -> copy(topP = v)
            SamplingParam.TOP_K -> copy(topK = v?.roundToInt())
            SamplingParam.MIN_P -> copy(minP = v)
            SamplingParam.FREQUENCY_PENALTY -> copy(frequencyPenalty = v)
            SamplingParam.PRESENCE_PENALTY -> copy(presencePenalty = v)
        }
    }

    val overrides: Int get() = SamplingParam.entries.count { this[it] != null }

    val isEmpty: Boolean get() = overrides == 0

    /** Request fields for the overridden values only; `top_k` stays an integer. */
    fun parameters(): Map<String, Any> = buildMap {
        for (param in SamplingParam.entries) {
            val value = this@Sampling[param] ?: continue
            // Via the float's own text: a direct widening turns 0.7f into
            // 0.699999988079071, which is what would then go out on the wire.
            put(param.wire, if (param.integral) value.roundToInt() else value.toString().toDouble())
        }
    }

    /** `wire=value` pairs; empty when nothing is overridden. */
    fun encode(): String = SamplingParam.entries
        .mapNotNull { param -> get(param)?.let { "${param.wire}=$it" } }
        .joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = ";"

        private val BY_WIRE = SamplingParam.entries.associateBy { it.wire }

        /** Unparseable or unknown entries are dropped, so a field can be retired without a migration. */
        fun decode(raw: String?): Sampling {
            if (raw.isNullOrEmpty()) return Sampling()
            return raw.split(SEPARATOR).fold(Sampling()) { acc, entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) return@fold acc
                val param = BY_WIRE[entry.substring(0, separator)] ?: return@fold acc
                val value = entry.substring(separator + 1).toFloatOrNull() ?: return@fold acc
                acc.with(param, value)
            }
        }
    }
}
