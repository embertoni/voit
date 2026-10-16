package com.voit.feature.settings

import com.voit.domain.AppSettings
import com.voit.domain.FakeCommandRepository
import com.voit.domain.FakeNoteRepository
import com.voit.domain.FakeSettingsRepository
import com.voit.domain.FakeTaskRepository
import com.voit.domain.Note
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private fun vm(
        settings: FakeSettingsRepository,
        notes: FakeNoteRepository,
        scope: TestScope,
    ) = SettingsViewModel(settings, notes, FakeTaskRepository(), FakeCommandRepository(), scope)

    @Test
    fun `defaults are privacy-safe and theme defaults to system`() = runTest {
        val settings = FakeSettingsRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = vm(settings, FakeNoteRepository(), scope)
        scope.advanceUntilIdle()

        val s = vm.uiState.value.settings
        assertFalse(s.cloudProcessingEnabled)
        assertEquals(AppSettings.DEFAULT_THEME_MODE, s.themeMode)
        assertEquals("pt-BR", s.recognitionLanguage)
    }

    @Test
    fun `theme mode and language updates persist`() = runTest {
        val settings = FakeSettingsRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = vm(settings, FakeNoteRepository(), scope)
        scope.advanceUntilIdle()

        vm.setThemeMode("DARK")
        vm.setRecognitionLanguage("en-US")
        scope.advanceUntilIdle()

        val s = settings.settings.first()
        assertEquals("DARK", s.themeMode)
        assertEquals("en-US", s.recognitionLanguage)
    }

    @Test
    fun `delete all notes clears the note store`() = runTest {
        val settings = FakeSettingsRepository()
        val notes = FakeNoteRepository()
        notes.create(Note(id = "n1", createdAt = 1L, updatedAt = 1L))
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = vm(settings, notes, scope)
        scope.advanceUntilIdle()

        vm.deleteAllNotes()
        scope.advanceUntilIdle()

        assertTrue(notes.observeNotes().first().isEmpty())
        assertEquals("Deleted all notes.", vm.uiState.value.message)
    }
}
