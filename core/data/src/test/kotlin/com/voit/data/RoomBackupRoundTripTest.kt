package com.voit.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voit.data.db.VoitDatabase
import com.voit.domain.ActionRequest
import com.voit.domain.ActionType
import com.voit.domain.AppSettings
import com.voit.domain.ArgumentSchema
import com.voit.domain.ArgumentSpec
import com.voit.domain.Backup
import com.voit.domain.BackupManager
import com.voit.domain.CommandDefinition
import com.voit.domain.ConfirmationPolicy
import com.voit.domain.FakeSettingsRepository
import com.voit.domain.Note
import com.voit.domain.NoteBlock
import com.voit.domain.ParamType
import com.voit.domain.ParamValue
import com.voit.domain.Priority
import com.voit.domain.ReminderConfig
import com.voit.domain.TaskItem
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Exports a populated Room-backed store to a backup payload and imports it into a fresh
 * Room-backed store, asserting the content survives the full round-trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RoomBackupRoundTripTest {

    private lateinit var src: VoitDatabase
    private lateinit var dst: VoitDatabase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        src = Room.inMemoryDatabaseBuilder(ctx, VoitDatabase::class.java).allowMainThreadQueries().build()
        dst = Room.inMemoryDatabaseBuilder(ctx, VoitDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        src.close()
        dst.close()
    }

    @Test
    fun fullBackupRoundTripAcrossRoomStores() = runTest {
        val backup = Backup(
            notes = listOf(
                Note(
                    id = "n1",
                    title = "Note",
                    blocks = listOf(NoteBlock.Heading(1, "H"), NoteBlock.Task("x", true)),
                    tags = listOf("a", "b"),
                    createdAt = 1,
                    updatedAt = 2,
                ),
            ),
            tasks = listOf(
                TaskItem(
                    id = "t1",
                    title = "Task",
                    body = "body",
                    due = 100,
                    reminderConfig = ReminderConfig(remindAtEpochMillis = 50),
                    priority = Priority.HIGH,
                    linkedNoteId = "n1",
                    createdAt = 1,
                    updatedAt = 2,
                ),
            ),
            commands = listOf(
                CommandDefinition(
                    id = "c1",
                    name = "Cmd",
                    triggerPhrases = listOf("do it"),
                    intent = "create_note",
                    argumentSchema = ArgumentSchema(listOf(ArgumentSpec("t", ParamType.TEXT, true))),
                    confirmationPolicy = ConfirmationPolicy.ALWAYS,
                    actions = listOf(ActionRequest(ActionType.CREATE_NOTE, mapOf("t" to ParamValue.Text("hi")))),
                    createdAt = 1,
                    updatedAt = 2,
                ),
            ),
            settings = AppSettings(recognitionLanguage = "en-US", listeningEnabled = true),
        )

        // Populate source store.
        val srcNotes = RoomNoteRepository(src)
        val srcTasks = RoomTaskRepository(src)
        val srcCommands = RoomCommandRepository(src)
        backup.notes.forEach { srcNotes.create(it) }
        backup.tasks.forEach { srcTasks.create(it) }
        backup.commands.forEach { srcCommands.upsert(it) }

        val exported = BackupManager(
            srcNotes, srcTasks, srcCommands, FakeSettingsRepository(backup.settings),
        ).exportTo(backup.settings)

        // Import into fresh store.
        val dstNotes = RoomNoteRepository(dst)
        val dstTasks = RoomTaskRepository(dst)
        val dstCommands = RoomCommandRepository(dst)
        BackupManager(dstNotes, dstTasks, dstCommands, FakeSettingsRepository()).importFrom(exported)

        assertEquals(backup.notes, dstNotes.exportAll())
        assertEquals(backup.tasks, dstTasks.exportAll())
        assertEquals(backup.commands, dstCommands.exportAll())
    }
}
