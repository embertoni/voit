package com.voit.feature.commands

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voit.actions.ActionRegistry
import com.voit.actions.ActionRunRecorder
import com.voit.actions.CommandDispatchResult
import com.voit.actions.CommandDispatcher
import com.voit.domain.ActionRequest
import com.voit.domain.ActionType
import com.voit.domain.Clock
import com.voit.domain.CommandDefinition
import com.voit.domain.CommandRepository
import com.voit.domain.ConfirmationPolicy
import com.voit.domain.IdGenerator
import com.voit.domain.ParamValue
import com.voit.domain.SystemClock
import com.voit.domain.UuidGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * One action inside a [CommandDraft]: its allow-listed [type] plus the raw (string-backed)
 * argument values the user typed, keyed by the handler's [com.voit.actions.ParamSpec.name].
 * Raw strings are parsed into typed [ParamValue]s only at save time (see
 * [CommandsViewModel.buildArgs]) so the editor can hold partially-typed input.
 */
data class DraftAction(
    val type: ActionType,
    val rawArgs: Map<String, String> = emptyMap(),
)

/** The in-progress command the editor is assembling. */
data class CommandDraft(
    val id: String? = null,
    val name: String = "",
    val intent: String = "",
    val enabled: Boolean = true,
    val triggerPhrases: List<String> = emptyList(),
    val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.IF_AMBIGUOUS,
    val actions: List<DraftAction> = emptyList(),
)

data class CommandsUiState(
    val commands: List<CommandDefinition> = emptyList(),
    /** The allow-listed action types the editor may choose from. NEVER free text. */
    val availableActions: List<ActionType> = emptyList(),
    val draft: CommandDraft = CommandDraft(),
    val error: String? = null,
    /** A short status line after running a command (dispatch outcome), cleared on new edits. */
    val runMessage: String? = null,
)

/**
 * Commands ViewModel. A command is assembled ONLY from the typed, allow-listed action
 * registry: [CommandsUiState.availableActions] is exactly [ActionRegistry.registeredTypes].
 * There is no free-text / arbitrary-code path — [addAction] rejects anything not in the
 * registry and the bounded action list is capped at [MAX_ACTIONS].
 *
 * It now also captures PER-ACTION arguments (keyed off each handler's schema) and an
 * enable/disable flag, and can RUN a stored command through the [CommandDispatcher],
 * recording a redacted audit record via the [ActionRunRecorder]. The dispatcher/recorder
 * are optional so the editor-only tests can omit them.
 */
class CommandsViewModel(
    private val repository: CommandRepository,
    private val registry: ActionRegistry,
    private val clock: Clock = SystemClock,
    private val ids: IdGenerator = UuidGenerator,
    private val dispatcher: CommandDispatcher? = null,
    private val recorder: ActionRunRecorder? = null,
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

    /** The declared parameter names for an action [type], in schema order (empty if none). */
    fun paramNames(type: ActionType): List<String> =
        registry.handlerFor(type)?.schema?.map { it.name } ?: emptyList()

    /** Whether a given parameter of an action [type] is required by its schema. */
    fun isParamRequired(type: ActionType, name: String): Boolean =
        registry.handlerFor(type)?.schema?.firstOrNull { it.name == name }?.required ?: false

    // --- Draft editing ---

    fun startNewDraft() {
        _uiState.value = _uiState.value.copy(draft = CommandDraft(), error = null, runMessage = null)
    }

    fun editExisting(command: CommandDefinition) {
        _uiState.value = _uiState.value.copy(
            draft = CommandDraft(
                id = command.id,
                name = command.name,
                intent = command.intent,
                enabled = command.enabled,
                triggerPhrases = command.triggerPhrases,
                confirmationPolicy = command.confirmationPolicy,
                actions = command.actions.map { it.toDraftAction() },
            ),
            error = null,
            runMessage = null,
        )
    }

    fun setName(name: String) = updateDraft { it.copy(name = name) }
    fun setIntent(intent: String) = updateDraft { it.copy(intent = intent) }
    fun setEnabled(enabled: Boolean) = updateDraft { it.copy(enabled = enabled) }
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
        updateDraft { it.copy(actions = it.actions + DraftAction(type)) }
    }

    fun removeActionAt(index: Int) = updateDraft {
        it.copy(actions = it.actions.toMutableList().also { l -> if (index in l.indices) l.removeAt(index) })
    }

    /** Set the raw (string) value of one argument of the action at [index]. */
    fun setActionArg(index: Int, name: String, rawValue: String) = updateDraft { draft ->
        val actions = draft.actions.toMutableList()
        val action = actions.getOrNull(index) ?: return@updateDraft draft
        actions[index] = action.copy(rawArgs = action.rawArgs + (name to rawValue))
        draft.copy(actions = actions)
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
        if (draft.actions.any { !registry.isAllowed(it.type) }) {
            _uiState.value = _uiState.value.copy(error = "A chosen action is not allow-listed")
            return false
        }
        // Build typed args per action; a missing/unparseable REQUIRED arg aborts the save so
        // we never persist a command that would only fail (be Rejected) at dispatch.
        val built = ArrayList<ActionRequest>(draft.actions.size)
        for ((i, action) in draft.actions.withIndex()) {
            when (val r = buildArgs(action)) {
                is ArgResult.Ok -> built.add(ActionRequest(type = action.type, args = r.args))
                is ArgResult.Err -> {
                    _uiState.value = _uiState.value.copy(
                        error = "Action ${i + 1} (${action.type.name}): ${r.reason}",
                    )
                    return false
                }
            }
        }
        val now = clock.nowEpochMillis()
        val command = CommandDefinition(
            id = draft.id ?: ids.newId(),
            name = draft.name.trim(),
            enabled = draft.enabled,
            triggerPhrases = draft.triggerPhrases,
            intent = draft.intent.trim(),
            confirmationPolicy = draft.confirmationPolicy,
            actions = built,
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

    /**
     * Run a stored [command] by dispatching one of its trigger phrases through the
     * [CommandDispatcher], then record a redacted audit entry. A no-op (with a hint) when no
     * dispatcher was injected. [confirmed] re-runs a command that previously needed
     * confirmation. The resulting status is surfaced in [CommandsUiState.runMessage]; this
     * method NEVER puts transcript/command content into diagnostics.
     */
    fun runCommand(command: CommandDefinition, confirmed: Boolean = false) {
        val dispatcher = dispatcher
        if (dispatcher == null) {
            _uiState.value = _uiState.value.copy(runMessage = "Command execution is not available here.")
            return
        }
        val trigger = command.triggerPhrases.firstOrNull { it.isNotBlank() }
        if (trigger == null) {
            _uiState.value = _uiState.value.copy(runMessage = "\"${command.name}\" has no trigger phrase to run.")
            return
        }
        workScope.launch {
            val startedAt = clock.nowEpochMillis()
            // Dispatch against the single command so matching is deterministic for a tapped run.
            val result = dispatcher.dispatch(
                commands = listOf(command),
                transcript = trigger,
                confirmed = confirmed,
            )
            recorder?.record(result, startedAt = startedAt)
            _uiState.value = _uiState.value.copy(runMessage = runMessageFor(command, result))
        }
    }

    private fun runMessageFor(command: CommandDefinition, result: CommandDispatchResult): String =
        when (result) {
            is CommandDispatchResult.Dispatched -> "Ran \"${command.name}\" (${result.outcome::class.simpleName})."
            is CommandDispatchResult.NeedsConfirmation ->
                "\"${command.name}\" needs confirmation before it runs."
            is CommandDispatchResult.NeedsChoice -> "\"${command.name}\" matched more than one command."
            is CommandDispatchResult.Rejected -> "\"${command.name}\" could not run: ${result.reason}"
            CommandDispatchResult.NoMatch -> "\"${command.name}\" did not match its own trigger."
        }

    private fun updateDraft(transform: (CommandDraft) -> CommandDraft) {
        _uiState.value = _uiState.value.copy(draft = transform(_uiState.value.draft), error = null)
    }

    /** A persisted [ActionRequest] back into an editable [DraftAction] (typed args -> raw). */
    private fun ActionRequest.toDraftAction(): DraftAction =
        DraftAction(type = type, rawArgs = args.mapValues { (_, v) -> v.toRaw() })

    private sealed interface ArgResult {
        data class Ok(val args: Map<String, ParamValue>) : ArgResult
        data class Err(val reason: String) : ArgResult
    }

    /**
     * Parse a draft action's raw string args into typed [ParamValue]s against its handler
     * schema. A blank optional arg is omitted; a blank required arg or an unparseable value
     * is an error. Keeps the registry's typed-args invariant: free text is never passed as
     * an untyped value.
     */
    private fun buildArgs(action: DraftAction): ArgResult {
        val handler = registry.handlerFor(action.type)
            ?: return ArgResult.Err("not allow-listed")
        val out = LinkedHashMap<String, ParamValue>()
        for (spec in handler.schema) {
            val raw = action.rawArgs[spec.name]?.trim().orEmpty()
            if (raw.isEmpty()) {
                if (spec.required) return ArgResult.Err("'${spec.name}' is required")
                continue
            }
            val value = parseParam(spec.type, raw)
                ?: return ArgResult.Err("'${spec.name}' is not a valid ${spec.type.name.lowercase()}")
            out[spec.name] = value
        }
        return ArgResult.Ok(out)
    }

    companion object {
        const val MAX_ACTIONS = 5

        /** Parse a raw string into a [ParamValue] of [type], or null if invalid. */
        internal fun parseParam(type: com.voit.domain.ParamType, raw: String): ParamValue? = when (type) {
            com.voit.domain.ParamType.TEXT -> ParamValue.Text(raw)
            com.voit.domain.ParamType.NUMBER -> raw.toDoubleOrNull()?.let { ParamValue.Number(it) }
            com.voit.domain.ParamType.BOOL -> when (raw.lowercase()) {
                "true", "yes", "1" -> ParamValue.Bool(true)
                "false", "no", "0" -> ParamValue.Bool(false)
                else -> null
            }
            com.voit.domain.ParamType.DURATION -> raw.toLongOrNull()?.let { ParamValue.Duration(it) }
            com.voit.domain.ParamType.TIMESTAMP -> raw.toLongOrNull()?.let { ParamValue.Timestamp(it) }
            com.voit.domain.ParamType.TAGS ->
                ParamValue.Tags(raw.split(",").map(String::trim).filter(String::isNotEmpty))
        }

        /** Render a typed [ParamValue] back to the raw string the editor shows. */
        private fun ParamValue.toRaw(): String = when (this) {
            is ParamValue.Text -> value
            is ParamValue.Number -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            is ParamValue.Bool -> value.toString()
            is ParamValue.Duration -> seconds.toString()
            is ParamValue.Timestamp -> epochMillis.toString()
            is ParamValue.Tags -> values.joinToString(", ")
        }
    }
}
