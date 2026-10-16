package com.voit.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainModelTest {

    @Test
    fun fixedClockIsDeterministic() {
        val clock = FixedClock(1_000L)
        assertEquals(1_000L, clock.nowEpochMillis())
        assertEquals(1_000L, clock.nowEpochMillis())
    }

    @Test
    fun mutableClockAdvances() {
        val clock = MutableClock(100L)
        clock.advanceBy(50L)
        assertEquals(150L, clock.nowEpochMillis())
        clock.set(10L)
        assertEquals(10L, clock.nowEpochMillis())
    }

    @Test
    fun sequentialIdGeneratorIsPredictable() {
        val ids = SequentialIdGenerator()
        assertEquals("id-1", ids.newId())
        assertEquals("id-2", ids.newId())
    }

    @Test
    fun headingRejectsOutOfRangeLevel() {
        assertFailsWith<IllegalArgumentException> { NoteBlock.Heading(0, "x") }
        assertFailsWith<IllegalArgumentException> { NoteBlock.Heading(7, "x") }
    }

    @Test
    fun headingClampedCoercesLevel() {
        assertEquals(1, NoteBlock.Heading.clamped(0, "x").level)
        assertEquals(6, NoteBlock.Heading.clamped(99, "x").level)
        assertEquals(3, NoteBlock.Heading.clamped(3, "x").level)
    }

    @Test
    fun noteCarriesStructuredBlocksTagsAndReviewFlag() {
        val note = Note(
            id = "n1",
            title = "Groceries",
            blocks = listOf(
                NoteBlock.Heading(1, "Groceries"),
                NoteBlock.Bullet("Milk"),
                NoteBlock.Task("Buy eggs", completed = false),
            ),
            tags = listOf("shopping"),
            createdAt = 1L,
            updatedAt = 2L,
            source = NoteSource.SIMULATOR,
            reviewRequired = true,
        )
        assertEquals(3, note.blocks.size)
        assertEquals(listOf("shopping"), note.tags)
        assertTrue(note.reviewRequired)
    }

    @Test
    fun reminderConfigIsSetFlag() {
        assertFalse(ReminderConfig().isSet)
        assertTrue(ReminderConfig(remindAtEpochMillis = 1L).isSet)
        assertTrue(ReminderConfig(leadMinutes = 5).isSet)
    }

    @Test
    fun actionTypeAllowListHasMvpSet() {
        // Guard the exact MVP action count so accidental additions/removals are caught.
        assertEquals(20, ActionType.entries.size)
    }
}
