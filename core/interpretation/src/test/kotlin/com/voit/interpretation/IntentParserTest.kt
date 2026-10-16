package com.voit.interpretation

import com.voit.domain.ParamValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntentParserTest {

    private val triggers = listOf(
        TriggerDefinition(
            intent = "start_timer",
            phrases = listOf("start a timer", "set a timer"),
            aliases = listOf("timer"),
            localized = mapOf("es" to listOf("inicia un temporizador")),
        ),
        TriggerDefinition(
            intent = "create_note",
            phrases = listOf("create a note", "new note"),
        ),
    )
    private val parser = IntentParser(triggers)

    @Test
    fun exactMatchHighestConfidence() {
        val intent = parser.parse("start a timer")!!
        assertEquals("start_timer", intent.name)
        assertEquals(MatchKind.EXACT.confidence, intent.confidence)
    }

    @Test
    fun aliasMatch() {
        val intent = parser.parse("timer")!!
        assertEquals("start_timer", intent.name)
        assertEquals(MatchKind.ALIAS.confidence, intent.confidence)
    }

    @Test
    fun caseInsensitiveMatch() {
        val intent = parser.parse("Create A Note")!!
        assertEquals("create_note", intent.name)
        assertEquals(MatchKind.CASE_INSENSITIVE.confidence, intent.confidence)
    }

    @Test
    fun spacingTolerantMatch() {
        val intent = parser.parse("start   a    timer")!!
        assertEquals("start_timer", intent.name)
        assertEquals(MatchKind.SPACING.confidence, intent.confidence)
    }

    @Test
    fun mixedLanguageMatch() {
        val intent = parser.parse("inicia un temporizador")!!
        assertEquals("start_timer", intent.name)
        assertEquals(MatchKind.MIXED_LANGUAGE.confidence, intent.confidence)
        assertEquals("es", intent.language)
    }

    @Test
    fun substringMatch() {
        val intent = parser.parse("hey could you start a timer please")!!
        assertEquals("start_timer", intent.name)
        assertEquals(MatchKind.SUBSTRING.confidence, intent.confidence)
    }

    @Test
    fun noMatchReturnsNull() {
        assertNull(parser.parse("what is the weather"))
        assertNull(parser.parse(""))
    }

    @Test
    fun incidentalSubstringDoesNotMatchInsideLongerWord() {
        // "timer" is an alias, but here it is a fragment of "timers" — not a standalone
        // token, so it must NOT fire the start_timer intent.
        assertNull(parser.parse("the old timers were talking about the weather"))
    }

    @Test
    fun incidentalTriggerPhraseBuriedMidSentenceStillMatchesAsStandaloneTokens() {
        // The ergonomic case: the full trigger phrase appears as whole words in a longer
        // utterance, so substring matching should still catch it.
        val intent = parser.parse("hey could you start a timer please")!!
        assertEquals("start_timer", intent.name)
        assertEquals(MatchKind.SUBSTRING.confidence, intent.confidence)
    }

    @Test
    fun durationSlotDigits() {
        val slots = SlotParser().extract("remind me in 5 minutes")
        assertEquals(ParamValue.Duration(300), slots["duration"])
    }

    @Test
    fun durationSlotWords() {
        val slots = SlotParser().extract("set a timer for two hours")
        assertEquals(ParamValue.Duration(7200), slots["duration"])
    }

    @Test
    fun relativeDateResolvesAgainstNow() {
        val base = 1_000_000L
        val slots = SlotParser(nowEpochMillis = base).extract("do it tomorrow")
        assertEquals(ParamValue.Timestamp(base + 86_400_000L), slots["date"])
    }

    @Test
    fun titleSlotExtracted() {
        val slots = SlotParser().extract("create a note titled Weekend Plans")
        assertEquals(ParamValue.Text("Weekend Plans"), slots["title"])
    }

    @Test
    fun tagSlotFromHashAndKeyword() {
        val slots = SlotParser().extract("note this #work and tag urgent")
        val tags = slots["tags"] as ParamValue.Tags
        assertTrue(tags.values.contains("work"))
        assertTrue(tags.values.contains("urgent"))
    }

    @Test
    fun numberSlotExtracted() {
        val slots = SlotParser().extract("complete task 3")
        assertEquals(ParamValue.Number(3.0), slots["number"])
    }
}
