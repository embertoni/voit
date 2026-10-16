package com.voit.actions

import com.voit.domain.FixedClock
import com.voit.domain.NoteBlock
import com.voit.domain.NoteSource
import com.voit.domain.SequentialIdGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end (pure) tests of the transcript -> structured result pipeline that the
 * Simulator screen drives. No Android, no persistence: this validates the orchestration
 * of the REAL FormattingParser + IntentParser + ActionRegistry.
 */
class TranscriptProcessorTest {

    private fun processor() = TranscriptProcessor(
        registry = ActionRegistry(),
        clock = FixedClock(1_000L),
        ids = SequentialIdGenerator(),
    )

    @Test
    fun `transcript becomes a structured note with heading paragraph bullet and tag`() {
        val transcript =
            "title: Groceries new paragraph buy things for the week " +
                "bullet point milk bullet point eggs tag shopping"

        val result = processor().process(transcript)

        // The deterministic parser normalizes (lower-cases) spoken content.
        assertEquals("groceries", result.note.title)
        assertEquals(NoteSource.SIMULATOR, result.note.source)
        val blocks = result.note.blocks
        assertTrue(blocks.any { it is NoteBlock.Heading && it.text == "groceries" })
        assertTrue(blocks.any { it is NoteBlock.Paragraph && it.text.contains("buy things") })
        assertEquals(2, blocks.count { it is NoteBlock.Bullet })
        assertTrue(result.note.tags.contains("shopping"))
    }

    @Test
    fun `transcript with a task directive produces an in-note task block`() {
        val transcript = "new note task item call the dentist"
        val result = processor().process(transcript)
        assertTrue(result.note.blocks.any { it is NoteBlock.Task && it.text == "call the dentist" })
    }

    @Test
    fun `create-task intent produces a persisted-ready TaskItem linked to the note`() {
        val transcript = "create a task titled Pay rent for the month"
        val result = processor().process(transcript)

        assertEquals(TranscriptProcessor.INTENT_CREATE_TASK, result.intentName)
        val task = assertNotNull(result.task, "a task should be produced")
        // The title slot keeps original casing and ends at the "for" boundary.
        assertEquals("Pay rent", task.title)
        assertEquals(result.note.id, task.linkedNoteId)
        // Low-risk create is authorized, so NOT gated behind confirmation.
        assertNull(result.pendingConfirmation)
    }

    @Test
    fun `high-risk export intent is gated behind explicit confirmation and not auto-run`() {
        val transcript = "export note please"
        val result = processor().process(transcript)

        assertEquals(TranscriptProcessor.INTENT_EXPORT_NOTE, result.intentName)
        val pending = assertNotNull(
            result.pendingConfirmation,
            "export is HIGH risk and must require confirmation",
        )
        assertEquals(com.voit.domain.ActionType.EXPORT_NOTE, pending.request.type)

        // Confirming re-authorizes through the registry (the sole authorizer).
        val decision = processor().confirm(pending.request)
        assertTrue(decision is AuthorizationDecision.Authorized)
    }

    @Test
    fun `create-task fallback title is capped and not the entire transcript`() {
        // No "titled/called" slot and no heading, so the title falls back to the
        // transcript. normalize() collapses newlines, so without a cap the whole long
        // transcript would become the title. It must be capped instead.
        val transcript =
            "create a task buy milk eggs bread butter cheese coffee tea sugar " +
                "flour rice beans and a whole lot of other groceries from the store"
        val result = processor().process(transcript)

        val task = assertNotNull(result.task, "a task should be produced")
        assertTrue(
            task.title.length < transcript.length,
            "fallback title must not be the entire transcript",
        )
        assertTrue(
            task.title.length <= TranscriptProcessor.MAX_FALLBACK_TITLE_CHARS + 1,
            "fallback title length ${task.title.length} exceeds the cap",
        )
        assertTrue(task.title.endsWith("…"), "a truncated fallback title is marked with an ellipsis")
    }

    @Test
    fun `unresolved formatting flags the note for review without discarding content`() {
        val transcript = "note bold important"
        val result = processor().process(transcript)
        assertTrue(result.reviewRequired)
        assertTrue(result.note.reviewRequired)
        // Content preserved (not discarded).
        assertTrue(result.note.blocks.isNotEmpty())
    }
}
