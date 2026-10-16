package com.voit.feature.commands

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voit.actions.ActionRegistry
import com.voit.domain.ActionRequest
import com.voit.domain.ActionType
import com.voit.domain.Clock
import com.voit.domain.CommandDefinition
import com.voit.domain.CommandRepository
import com.voit.domain.ConfirmationPolicy
import com.voit.domain.IdGenerator
import com.voit.domain.SystemClock
import com.voit.domain.UuidGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The in-progress command the editor is assembling. */
data class CommandDraft(
    val id: String? = null,
    val name: String = "",
    val intent: String = "",
    val triggerPhrases: List<String> = emptyList(),
    val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.IF_AMBIGUOUS,
    val actions: List<ActionType> = emptyList(),
)

data class CommandsUiState(
    val commands: List<CommandDefinition> = emptyList(),
    /** The allow-listed action types the editor may choose from. NEVER free text. */
    val availableActions: List<ActionType> = emptyList(),
    val draft: CommandDraft = CommandDraft(),
    val error: String? = null,
)

/**
 * Commands ViewModel. A command is assembled ONLY from the typed, allow-listed action
 * registry: [availableActions] is exactly [ActionRegistry.registeredTypes]. There is no
 * free-text / arbitrary-code path — [addAction] rejects anything not in the registry and
 * the bounded action list is capped at [MAX_ACTIONS].
 */
class CommandsViewModel(
    private val repository: CommandRepository,
    private val registry: ActionRegistry,
    private val clock: Clock = SystemClock,
    private val ids: IdGenerator = UuidGenerator,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope: CoroutineScope = scope ?: viewModelScope

    private val _uiState = MutableStateFlow(
        CommandsUiState(availableActions = registry.registeredTypes.toList().sortedBy { it.name }),
    )
    val uiState: StateFlow<CommandsUiState> = _uiState.asStateFlow()

    init {
        workScope.launch {
            repository.observeCommands().collect { commands ->
                _uiState.value = _uiState.value.copy(commands = commands)
            }
        }
    }

    // --- Draft editing ---

    fun startNewDraft() {
        _uiState.value = _uiState.value.copy(draft = CommandDraft(), error = null)
    }

    fun editExisting(command: CommandDefinition) {
        _uiState.value = _uiState.value.copy(
            draft = CommandDraft(
                id = command.id,
                name = command.name,
                intent = command.intent,
                triggerPhrases = command.triggerPhrases,
                confirmationPolicy = command.confirmationPolicy,
                actions = command.actions.map { it.type },
            ),
            error = null,
        )
    }

    fun setName(name: String) = updateDraft { it.copy(name = name) }
    fun setIntent(intent: String) = updateDraft { it.copy(intent = intent) }
    fun setConfirmationPolicy(policy: ConfirmationPolicy) = updateDraft { it.copy(confirmationPolicy = policy) }

    fun setTriggerPhrases(raw: String) = updateDraft {
        it.copy(triggerPhrases = raw.split(",").map(String::trim).filter(String::isNotEmpty))
    }

    /** Add an action to the draft. Only allow-listed, in-registry types are accepted. */
    fun addAction(type: ActionType) {
        if (!registry.isAllowed(type)) {
            _uiState.value = _uiState.value.copy(error = "Action $type is not in the allow-list")
            return
        }
        val draft = _uiState.value.draft
        if (draft.actions.size >= MAX_ACTIONS) {
            _uiState.value = _uiState.value.copy(error = "A command may contain at most $MAX_ACTIONS actions")
            return
        }
        updateDraft { it.copy(actions = it.actions + type) }
    }

    fun removeActionAt(index: Int) = updateDraft {
        it.copy(actions = it.actions.toMutableList().also { l -> if (index in l.indices) l.removeAt(index) })
    }

    /** Validate and persist the draft. Returns false (with an error set) when invalid. */
    fun saveDraft(onSaved: () -> Unit = {}): Boolean {
        val draft = _uiState.value.draft
        if (draft.name.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Name is required")
            return false
        }
        if (draft.intent.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Intent is required")
            return false
        }
        if (draft.actions.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "Add at least one action")
            return false
        }
        // Defensive: re-check every action is still allow-listed before persisting.
        if (draft.actions.any { !registry.isAllowed(it) }) {
            _uiState.value = _uiState.value.copy(error = "A chosen action is not allow-listed")
            return false
        }
        val now = clock.nowEpochMillis()
        val command = CommandDefinition(
            id = draft.id ?: ids.newId(),
            name = draft.name.trim(),
            enabled = true,
            triggerPhrases = draft.triggerPhrases,
            intent = draft.intent.trim(),
            confirmationPolicy = draft.confirmationPolicy,
            actions = draft.actions.map { ActionRequest(type = it) },
            createdAt = now,
            updatedAt = now,
        )
        workScope.launch {
            repository.upsert(command)
            _uiState.value = _uiState.value.copy(draft = CommandDraft(), error = null)
            onSaved()
        }
        return true
    }

    fun deleteCommand(id: String) {
        workScope.launch { repository.delete(id) }
    }

    private fun updateDraft(transform: (CommandDraft) -> CommandDraft) {
        _uiState.value = _uiState.value.copy(draft = transform(_uiState.value.draft), error = null)
    }

    companion object {
        const val MAX_ACTIONS = 5
    }
}
