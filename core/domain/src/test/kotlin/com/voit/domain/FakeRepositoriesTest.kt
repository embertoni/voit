package com.voit.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeRepositoriesTest {

    private fun note(id: String, title: String, updatedAt: Long = 1L) = Note(
        id = id,
        title = title,
        blocks = listOf(NoteBlock.Paragraph("body of $title")),
        tags = listOf("tag-$id"),
        createdAt = 1L,
        updatedAt = updatedAt,
    )

    @Test
    fun noteCrudAndObserveOrdering() = runTest {
        val repo = FakeNoteRepository()
        repo.create(note("1", "Alpha", updatedAt = 10))
        repo.create(note("2", "Beta", updatedAt = 20))

        assertEquals("Alpha", repo.getNote("1")?.title)
        val observed = repo.observeNotes().first()
        assertEquals(listOf("2", "1"), observed.map { it.id }) // most recent first
    }

    @Test
    fun duplicateNoteCreateIsRejected() = runTest {
        val repo = FakeNoteRepository()
        repo.create(note("1", "Alpha"))
        assertFailsWith<RepositoryConflictException> { repo.create(note("1", "Dup")) }
    }

    @Test
    fun updatingMissingNoteFails() = runTest {
        val repo = FakeNoteRepository()
        assertFailsWith<RepositoryConflictException> { repo.update(note("x", "Nope")) }
    }

    @Test
    fun archiveHidesFromDefaultObserveButExportKeepsIt() = runTest {
        val repo = FakeNoteRepository()
        repo.create(note("1", "Alpha"))
        repo.archive("1")
        assertTrue(repo.observeNotes(includeArchived = false).first().isEmpty())
        assertEquals(1, repo.observeNotes(includeArchived = true).first().size)
        assertEquals(1, repo.exportAll().size)
    }

    @Test
    fun deleteExcludesEverywhere() = runTest {
        val repo = FakeNoteRepository()
        repo.create(note("1", "Alpha"))
        repo.delete("1")
        assertNull(repo.getNote("1"))
        assertTrue(repo.exportAll().isEmpty())
    }

    @Test
    fun searchMatchesTitleTagsAndBlocks() = runTest {
        val repo = FakeNoteRepository()
        repo.create(note("1", "Groceries"))
        repo.create(note("2", "Work"))
        assertEquals(listOf("1"), repo.search("grocer").map { it.id })
        assertEquals(listOf("2"), repo.search("body of Work").map { it.id })
        assertEquals(listOf("1"), repo.search("tag-1").map { it.id })
        assertTrue(repo.search("   ").isEmpty())
    }

    @Test
    fun taskCrudSearchAndCompletion() = runTest {
        val repo = FakeTaskRepository()
        repo.create(TaskItem(id = "t1", title = "Call dentist", createdAt = 1, updatedAt = 10))
        repo.create(TaskItem(id = "t2", title = "Email boss", createdAt = 1, updatedAt = 20))

        assertFailsWith<RepositoryConflictException> {
            repo.create(TaskItem(id = "t1", title = "dup", createdAt = 1, updatedAt = 1))
        }
        assertEquals(listOf("t1"), repo.search("dentist").map { it.id })

        repo.setCompleted("t1", true)
        assertTrue(repo.getTask("t1")!!.completed)
        assertEquals(listOf("t2"), repo.observeTasks(includeCompleted = false).first().map { it.id })
    }
}
