package com.voit.domain

/**
 * A validated, structured intent produced by the intent/slot parser.
 *
 * The parser never executes free text; it emits an [Intent] whose [slots] are typed
 * [ParamValue]s and whose [name] maps (downstream) onto an allow-listed action.
 *
 * @param name canonical intent name (e.g. "start_timer").
 * @param slots typed, extracted arguments keyed by slot name.
 * @param confidence match confidence in 0.0..1.0; confidence NEVER authorizes a
 *        high-risk action on its own (see the action registry's confirmation policy).
 * @param matchedTrigger the trigger phrase text that matched, for traceability.
 * @param language detected language tag of the matched input, if known.
 */
data class Intent(
    val name: String,
    val slots: Map<String, ParamValue> = emptyMap(),
    val confidence: Double = 1.0,
    val matchedTrigger: String? = null,
    val language: String? = null,
)
