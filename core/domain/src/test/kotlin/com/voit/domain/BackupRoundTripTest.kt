package com.voit.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupRoundTripTest {

    private fun sampleBackup() = Backup(
        notes = listOf(
            Note(
                id = "n1",
                title = "Note \"one\"",
                blocks = listOf(
                    NoteBlock.Heading(1, "H"),
                    NoteBlock.Paragraph("body,\nwith newline"),
                    NoteBlock.Task("do", completed = true),
                ),
                tags = listOf("a", "b,c"),
                createdAt = 10,
                updatedAt = 20,
                source = NoteSource.SIMULATOR,
                transcriptLanguage = "pt-BR",
                reviewRequired = true,
            ),
        ),
        tasks = listOf(
            TaskItem(
                id = "t1",
                title = "Task one",
                body = "details",
                due = 999,
                reminderConfig = ReminderConfig(remindAtEpochMillis = 500, leadMinutes = 10),
                priority = Priority.HIGH,
                completed = false,
                linkedNoteId = "n1",
                createdAt = 1,
                updatedAt = 2,
            ),
        ),
        commands = listOf(
            CommandDefinition(
                id = "c1",
                name = "Start timer",
                enabled = true,
                triggerPhrases = listOf("start timer", "timer"),
                intent = "start_timer",
                argumentSchema = ArgumentSchema(listOf(ArgumentSpec("minutes", ParamType.DURATION))),
                confirmationPolicy = ConfirmationPolicy.ALWAYS,
                actions = listOf(
                    ActionRequest(ActionType.START_TIMER, mapOf("dur" to ParamValue.Duration(60))),
                ),
                createdAt = 3,
                updatedAt = 4,
            ),
        ),
        settings = AppSettings(
            listeningEnabled = true,
            recognitionLanguage = "en-US",
            cloudProcessingEnabled = false,
            wakePhrase = "hey voit",
        ),
    )

    @Test
    fun backupSerializesAndDeserializesUnchanged() {
        val backup = sampleBackup()
        val bytes = BackupSerializer.serialize(backup)
        val restored = BackupSerializer.deserialize(bytes)
        assertEquals(backup, restored)
    }

    @Test
    fun fullBackupManagerRoundTripIntoFreshStore() = runTest {
        // Populate a source store via the fakes.
        val srcNotes = FakeNoteRepository()
        val srcTasks = FakeTaskRepository()
        val srcCommands = FakeCommandRepository()
        val srcSettings = FakeSettingsRepository()

        val backup = sampleBackup()
        backup.notes.forEach { srcNotes.create(it) }
        backup.tasks.forEach { srcTasks.create(it) }
        backup.commands.forEach { srcCommands.upsert(it) }
        srcSettings.update { backup.settings }

        val srcManager = BackupManager(srcNotes, srcTasks, srcCommands, srcSettings)
        val exported = srcManager.exportTo(backup.settings)

        // Import into a completely fresh store.
        val dstNotes = FakeNoteRepository()
        val dstTasks = FakeTaskRepository()
        val dstCommands = FakeCommandRepository()
        val dstSettings = FakeSettingsRepository()
        val dstManager = BackupManager(dstNotes, dstTasks, dstCommands, dstSettings)
        dstManager.importFrom(exported)

        assertEquals(backup.notes, dstNotes.exportAll())
        assertEquals(backup.tasks, dstTasks.exportAll())
        assertEquals(backup.commands, dstCommands.exportAll())
    }

    @Test
    fun noOpEncryptorIsPassThrough() {
        val backup = sampleBackup()
        val plain = BackupSerializer.serialize(backup, NoOpExportEncryptor)
        // Payload is readable JSON (documents the no-op nature for Phase 1).
        assertTrue(plain.decodeToString().contains("\"notes\""))
    }

    @Test
    fun customEncryptorRoundTripsThroughManager() = runTest {
        // A trivial reversible transform stands in for a real encryptor to prove the
        // encrypt -> decrypt path is honored (no hard-coded secrets involved).
        val xor = object : ExportEncryptor {
            override fun encrypt(plaintext: ByteArray) = plaintext.map { (it + 1).toByte() }.toByteArray()
            override fun decrypt(ciphertext: ByteArray) = ciphertext.map { (it - 1).toByte() }.toByteArray()
        }
        val backup = sampleBackup()
        val bytes = BackupSerializer.serialize(backup, xor)
        assertEquals(backup, BackupSerializer.deserialize(bytes, xor))
    }
}
