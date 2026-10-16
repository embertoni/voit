package com.voit.feature.notes

import com.voit.domain.FakeNoteRepository
import com.voit.domain.FixedClock
import com.voit.domain.Note
import com.voit.domain.NoteBlock
import com.voit.domain.SequentialIdGenerator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {

    private suspend fun seedNote(repo: FakeNoteRepository, id: String, title: String, review: Boolean = false) {
        repo.create(
            Note(
                id = id,
                title = title,
                blocks = listOf(NoteBlock.Paragraph("content of $title")),
                tags = listOf("work"),
                createdAt = 1L,
                updatedAt = 1L,
                reviewRequired = review,
            ),
        )
    }

    @Test
    fun `list reflects repository and search filters by title`() = runTest {
        val repo = FakeNoteRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        seedNote(repo, "n1", "Alpha")
        seedNote(repo, "n2", "Beta")

        val vm = NotesViewModel(repo, FixedClock(5L), SequentialIdGenerator(), scope)
        scope.advanceUntilIdle()
        assertEquals(2, vm.uiState.value.notes.size)

        vm.onQueryChange("alph")
        scope.advanceUntilIdle()
        assertEquals(1, vm.uiState.value.notes.size)
        assertEquals("Alpha", vm.uiState.value.notes.single().title)
    }

    @Test
    fun `review notes surface separately`() = runTest {
        val repo = FakeNoteRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        seedNote(repo, "n1", "Clean", review = false)
        seedNote(repo, "n2", "Needs look", review = true)

        val vm = NotesViewModel(repo, FixedClock(5L), SequentialIdGenerator(), scope)
        scope.advanceUntilIdle()

        assertEquals(1, vm.uiState.value.reviewNotes.size)
        assertEquals("Needs look", vm.uiState.value.reviewNotes.single().title)
    }

    @Test
    fun `createNote persists a new note and returns its id`() = runTest {
        val repo = FakeNoteRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = NotesViewModel(repo, FixedClock(5L), SequentialIdGenerator(), scope)
        scope.advanceUntilIdle()

        var createdId: String? = null
        vm.createNote { createdId = it }
        scope.advanceUntilIdle()

        assertTrue(createdId != null)
        assertEquals(1, vm.uiState.value.notes.size)
    }
}
