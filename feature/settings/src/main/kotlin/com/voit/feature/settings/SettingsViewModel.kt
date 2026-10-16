package com.voit.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voit.domain.AppSettings
import com.voit.domain.CommandRepository
import com.voit.domain.NoteRepository
import com.voit.domain.SettingsRepository
import com.voit.domain.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings.DEFAULT,
    val message: String? = null,
)

/**
 * Settings ViewModel bound to the DataStore-backed [SettingsRepository]. Also exposes the
 * privacy "delete my data" entry points (notes / tasks / commands) by calling into the
 * respective repositories. Mic/listening settings exist but are clearly not-yet-active in
 * Phase 1 (no device capture); the UI labels them as placeholders.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val noteRepository: NoteRepository,
    private val taskRepository: TaskRepository,
    private val commandRepository: CommandRepository,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope: CoroutineScope = scope ?: viewModelScope

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        workScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(settings = settings)
            }
        }
    }

    fun setThemeMode(mode: String) = launchUpdate { settingsRepository.setThemeMode(mode) }

    fun setRecognitionLanguage(language: String) =
        launchUpdate { settingsRepository.setRecognitionLanguage(language) }

    fun setReviewMode(enabled: Boolean) =
        launchUpdate { settingsRepository.update { it.copy(reviewModeEnabled = enabled) } }

    fun setWakePhrase(phrase: String) =
        launchUpdate { settingsRepository.update { it.copy(wakePhrase = phrase) } }

    /** Cloud stays OFF in Phase 1: this is here for completeness but should remain disabled. */
    fun setCloudProcessing(enabled: Boolean) =
        launchUpdate { settingsRepository.setCloudProcessingEnabled(enabled) }

    fun setSpeakerVerification(enabled: Boolean) =
        launchUpdate { settingsRepository.update { it.copy(speakerVerificationEnabled = enabled) } }

    fun setDiagnostics(enabled: Boolean) =
        launchUpdate { settingsRepository.update { it.copy(diagnosticsEnabled = enabled) } }

    // --- Delete-my-data entry points (privacy) ---

    fun deleteAllNotes() = deleteAll("notes") {
        noteRepository.exportAll().forEach { noteRepository.delete(it.id) }
    }

    fun deleteAllTasks() = deleteAll("tasks") {
        taskRepository.exportAll().forEach { taskRepository.delete(it.id) }
    }

    fun deleteAllCommands() = deleteAll("commands") {
        commandRepository.exportAll().forEach { commandRepository.delete(it.id) }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun deleteAll(label: String, block: suspend () -> Unit) {
        workScope.launch {
            block()
            _uiState.value = _uiState.value.copy(message = "Deleted all $label.")
        }
    }

    private fun launchUpdate(block: suspend () -> Unit) {
        workScope.launch { block() }
    }
}
