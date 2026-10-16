package com.voit.feature.notes

import com.voit.domain.FakeNoteRepository
import com.voit.domain.FixedClock
import com.voit.domain.Note
import com.voit.domain.NoteBlock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorViewModelTest {

    @Test
    fun `edits title and toggles task then persists on save`() = runTest {
        val repo = FakeNoteRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        repo.create(
            Note(
                id = "n1",
                title = "Old",
                blocks = listOf(NoteBlock.Task("call dentist", completed = false)),
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val vm = NoteEditorViewModel(repo, "n1", FixedClock(99L), scope)
        scope.advanceUntilIdle()

        vm.onTitleChange("New title")
        vm.toggleTask(0)
        assertTrue(vm.uiState.value.dirty)
        vm.save()
        scope.advanceUntilIdle()

        val stored = repo.getNote("n1")!!
        assertEquals("New title", stored.title)
        assertTrue((stored.blocks[0] as NoteBlock.Task).completed)
        assertEquals(99L, stored.updatedAt)
        assertFalse(vm.uiState.value.dirty)
    }

    @Test
    fun `requestExport produces markdown for the note`() = runTest {
        val repo = FakeNoteRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        repo.create(
            Note(
                id = "n1",
                title = "Doc",
                blocks = listOf(NoteBlock.Heading(1, "Section"), NoteBlock.Paragraph("Body")),
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val vm = NoteEditorViewModel(repo, "n1", FixedClock(1L), scope)
        scope.advanceUntilIdle()

        vm.requestExport()
        val md = vm.uiState.value.exportText!!
        assertTrue(md.contains("# Doc"))
        assertTrue(md.contains("# Section"))
    }
}
