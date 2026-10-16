package com.voit.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voit.data.db.VoitDatabase
import com.voit.domain.FixedClock
import com.voit.domain.Note
import com.voit.domain.NoteBlock
import com.voit.domain.NoteSource
import com.voit.domain.Priority
import com.voit.domain.RepositoryConflictException
import com.voit.domain.TaskItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the Room-backed repositories against a real (in-memory) SQLite database via
 * Robolectric, so CRUD, search, atomic create/update and duplicate prevention are
 * exercised against the actual persistence layer on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RoomRepositoryTest {

    private lateinit var db: VoitDatabase
    private lateinit var notes: RoomNoteRepository
    private lateinit var tasks: RoomTaskRepository
    private lateinit var commands: RoomCommandRepository

    private val clock = FixedClock(1_000L)

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, VoitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        notes = RoomNoteRepository(db, clock = clock)
        tasks = RoomTaskRepository(db, clock = clock)
        commands = RoomCommandRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun note(id: String, title: String, updatedAt: Long = 1L) = Note(
        id = id,
        title = title,
        blocks = listOf(NoteBlock.Paragraph("body of $title"), NoteBlock.Task("todo $id", false)),
        tags = listOf("tag-$id"),
        createdAt = 1L,
        updatedAt = updatedAt,
        source = NoteSource.SIMULATOR,
    )

    @Test
    fun createReadUpdateNoteRoundTripsBlocks() = runTest {
        notes.create(note("1", "Alpha"))
        val loaded = notes.getNote("1")!!
        assertEquals("Alpha", loaded.title)
        assertEquals(2, loaded.blocks.size)
        assertEquals(NoteBlock.Paragraph("body of Alpha"), loaded.blocks[0])

        notes.update(loaded.copy(title = "Alpha2", blocks = listOf(NoteBlock.Heading(1, "H"))))
        val updated = notes.getNote("1")!!
        assertEquals("Alpha2", updated.title)
        assertEquals(listOf(NoteBlock.Heading(1, "H")), updated.blocks)
    }

    @Test
    fun duplicateCreateIsRejectedAtomically() = runTest {
        notes.create(note("1", "Alpha"))
        assertFailsWith<RepositoryConflictException> { notes.create(note("1", "Dup")) }
        // original is intact
        assertEquals("Alpha", notes.getNote("1")!!.title)
    }

    @Test
    fun updatingMissingNoteFails() = runTest {
        assertFailsWith<RepositoryConflictException> { notes.update(note("x", "Nope")) }
    }

    @Test
    fun archivePreservedAcrossContentUpdate() = runTest {
        notes.create(note("1", "Alpha"))
        notes.archive("1")
        assertTrue(notes.observeNotes(includeArchived = false).first().isEmpty())
        assertEquals(1, notes.observeNotes(includeArchived = true).first().size)

        // Updating content must NOT un-archive.
        notes.update(notes.observeNotes(includeArchived = true).first().first().copy(title = "A2"))
        assertTrue(notes.observeNotes(includeArchived = false).first().isEmpty())
    }

    @Test
    fun deleteSoftRemovesFromQueries() = runTest {
        notes.create(note("1", "Alpha"))
        notes.delete("1")
        assertNull(notes.getNote("1"))
        assertTrue(notes.exportAll().isEmpty())
    }

    @Test
    fun searchMatchesTitleTagsAndBlockContent() = runTest {
        notes.create(note("1", "Groceries", updatedAt = 10))
        notes.create(note("2", "Work", updatedAt = 20))
        assertEquals(listOf("1"), notes.search("grocer").map { it.id })
        assertEquals(listOf("2"), notes.search("body of Work").map { it.id })
        assertEquals(listOf("1"), notes.search("tag-1").map { it.id })
        assertTrue(notes.search("  ").isEmpty())
    }

    @Test
    fun taskCrudSearchCompletionAndDuplicatePrevention() = runTest {
        tasks.create(TaskItem(id = "t1", title = "Call dentist", createdAt = 1, updatedAt = 10, priority = Priority.HIGH))
        tasks.create(TaskItem(id = "t2", title = "Email boss", createdAt = 1, updatedAt = 20))

        assertFailsWith<RepositoryConflictException> {
            tasks.create(TaskItem(id = "t1", title = "dup", createdAt = 1, updatedAt = 1))
        }
        assertEquals(Priority.HIGH, tasks.getTask("t1")!!.priority)
        assertEquals(listOf("t1"), tasks.search("dentist").map { it.id })

        tasks.setCompleted("t1", true)
        assertTrue(tasks.getTask("t1")!!.completed)
        assertEquals(listOf("t2"), tasks.observeTasks(includeCompleted = false).first().map { it.id })
    }
}
