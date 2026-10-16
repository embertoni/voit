package com.voit.interpretation

import com.voit.domain.Intent
import com.voit.domain.ParamValue

/** How a trigger phrase matched an input, best (highest confidence) first. */
enum class MatchKind(val confidence: Double) {
    EXACT(1.0),
    ALIAS(0.95),
    CASE_INSENSITIVE(0.9),
    SPACING(0.85),
    MIXED_LANGUAGE(0.8),
    SUBSTRING(0.7),
}

/**
 * A registered trigger: a canonical intent name, its primary phrases, and optional
 * aliases (including other-language equivalents).
 */
data class TriggerDefinition(
    val intent: String,
    val phrases: List<String>,
    val aliases: List<String> = emptyList(),
    /** Language-tagged alternative phrasings, keyed by BCP-47-ish tag. */
    val localized: Map<String, List<String>> = emptyMap(),
)

/**
 * Deterministic intent + slot parser.
 *
 * Trigger matching tries, in decreasing confidence: exact, alias, case-insensitive,
 * spacing-tolerant, mixed-language, then substring. It emits a validated [Intent] with
 * typed [ParamValue] slots; it never executes or echoes free text as a command.
 */
class IntentParser(private val triggers: List<TriggerDefinition>) {

    private val slotParser = SlotParser()

    /** Returns the best-matching [Intent] for [input], or null if nothing matched. */
    fun parse(input: String): Intent? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        var best: Intent? = null
        for (trigger in triggers) {
            val match = matchTrigger(trigger, trimmed) ?: continue
            if (best == null || match.confidence > best.confidence) {
                best = Intent(
                    name = trigger.intent,
                    slots = slotParser.extract(trimmed),
                    confidence = match.confidence,
                    matchedTrigger = match.phrase,
                    language = match.language,
                )
            }
        }
        return best
    }

    private data class TriggerMatch(val confidence: Double, val phrase: String, val language: String?)

    private fun matchTrigger(trigger: TriggerDefinition, input: String): TriggerMatch? {
        val inputLower = input.lowercase()
        val inputCollapsed = collapseSpaces(inputLower)

        // Exact (case-sensitive) on primary phrases.
        for (p in trigger.phrases) {
            if (input == p) return TriggerMatch(MatchKind.EXACT.confidence, p, null)
        }
        // Alias exact (case-insensitive is fine for aliases).
        for (a in trigger.aliases) {
            if (inputLower == a.lowercase()) return TriggerMatch(MatchKind.ALIAS.confidence, a, null)
        }
        // Case-insensitive on primary phrases.
        for (p in trigger.phrases) {
            if (inputLower == p.lowercase()) return TriggerMatch(MatchKind.CASE_INSENSITIVE.confidence, p, null)
        }
        // Spacing-tolerant (collapse internal whitespace runs).
        for (p in trigger.phrases + trigger.aliases) {
            if (inputCollapsed == collapseSpaces(p.lowercase())) {
                return TriggerMatch(MatchKind.SPACING.confidence, p, null)
            }
        }
        // Mixed-language localized phrasings.
        for ((lang, phrases) in trigger.localized) {
            for (p in phrases) {
                if (inputLower == p.lowercase() || inputCollapsed == collapseSpaces(p.lowercase())) {
                    return TriggerMatch(MatchKind.MIXED_LANGUAGE.confidence, p, lang)
                }
            }
        }
        // Substring (phrase appears somewhere in the input).
        for (p in trigger.phrases + trigger.aliases) {
            if (inputCollapsed.contains(collapseSpaces(p.lowercase()))) {
                return TriggerMatch(MatchKind.SUBSTRING.confidence, p, null)
            }
        }
        return null
    }

    private fun collapseSpaces(s: String): String = s.trim().replace(Regex("\\s+"), " ")
}

/**
 * Extracts typed slots from free text: durations, relative dates, titles, tags, numbers.
 *
 * @param nowEpochMillis base instant used to resolve relative dates to absolute timestamps.
 */
class SlotParser(private val nowEpochMillis: Long = 0L) {

    fun extract(input: String): Map<String, ParamValue> {
        val slots = mutableMapOf<String, ParamValue>()
        val lower = input.lowercase()

        extractDuration(lower)?.let { slots["duration"] = it }
        extractRelativeDate(lower)?.let { slots["date"] = it }
        extractTags(input).takeIf { it.values.isNotEmpty() }?.let { slots["tags"] = it }
        extractTitle(input)?.let { slots["title"] = it }
        extractNumber(lower)?.let { slots["number"] = it }

        return slots
    }

    private val numberWords = mapOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L,
        "five" to 5L, "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L, "ten" to 10L,
    )

    /** Parses "5 minutes", "two hours", "30 seconds", "1 hour" into a Duration (seconds). */
    private fun extractDuration(lower: String): ParamValue.Duration? {
        val regex = Regex("(\\d+|${numberWords.keys.joinToString("|")})\\s*(second|seconds|minute|minutes|hour|hours)")
        val m = regex.find(lower) ?: return null
        val amount = m.groupValues[1].toLongOrNull() ?: numberWords[m.groupValues[1]] ?: return null
        val unitSeconds = when {
            m.groupValues[2].startsWith("second") -> 1L
            m.groupValues[2].startsWith("minute") -> 60L
            else -> 3600L
        }
        return ParamValue.Duration(amount * unitSeconds)
    }

    /** Resolves a small set of relative dates to absolute timestamps using [nowEpochMillis]. */
    private fun extractRelativeDate(lower: String): ParamValue.Timestamp? {
        val day = 86_400_000L
        val offset = when {
            lower.contains("day after tomorrow") -> 2 * day
            lower.contains("tomorrow") -> day
            lower.contains("today") -> 0L
            lower.contains("tonight") -> 0L
            lower.contains("next week") -> 7 * day
            lower.contains("yesterday") -> -day
            else -> return null
        }
        return ParamValue.Timestamp(nowEpochMillis + offset)
    }

    /** Hash tags (#work) or "tag <word>" / "tagged <word>" become tag slots. */
    private fun extractTags(input: String): ParamValue.Tags {
        val tags = mutableListOf<String>()
        Regex("#(\\w+)").findAll(input).forEach { tags.add(it.groupValues[1]) }
        Regex("(?i)\\btag(?:ged)?\\s+(\\w+)").findAll(input).forEach { tags.add(it.groupValues[1]) }
        return ParamValue.Tags(tags.distinct())
    }

    /** A title after "title:", "titled", or "called". */
    private fun extractTitle(input: String): ParamValue.Text? {
        val regex = Regex("(?i)(?:title\\s*:|titled|called)\\s+(.+?)(?=\\s+(?:tag|tagged|for|in|at|$)|$)")
        val m = regex.find(input) ?: return null
        val title = m.groupValues[1].trim().removeSuffix(".")
        return if (title.isEmpty()) null else ParamValue.Text(title)
    }

    /** A bare number (digits or small number words), independent of durations. */
    private fun extractNumber(lower: String): ParamValue.Number? {
        Regex("\\b\\d+(?:\\.\\d+)?\\b").find(lower)?.let {
            return ParamValue.Number(it.value.toDouble())
        }
        for ((word, value) in numberWords) {
            if (Regex("\\b$word\\b").containsMatchIn(lower)) return ParamValue.Number(value.toDouble())
        }
        return null
    }
}
