package com.voit.actions

import com.voit.domain.ActionRequest
import com.voit.domain.ActionType
import com.voit.domain.Clock
import com.voit.domain.IdGenerator
import com.voit.domain.Note
import com.voit.domain.NoteBlock
import com.voit.domain.NoteSource
import com.voit.domain.ParamValue
import com.voit.domain.Priority
import com.voit.domain.SystemClock
import com.voit.domain.TaskItem
import com.voit.domain.UuidGenerator
import com.voit.interpretation.FormattingParser
import com.voit.interpretation.IntentParser
import com.voit.interpretation.SlotParser
import com.voit.interpretation.TriggerDefinition

/**
 * A single, structured result candidate produced by processing a transcript. It is NOT
 * persisted here — the processor is pure and deterministic. The caller (a ViewModel)
 * decides what to persist, after any required confirmation.
 *
 * @param note the structured note parsed from the transcript (always produced).
 * @param task an optional standalone task detected from a task-creation intent.
 * @param pendingConfirmation set when a high-risk action was recognised but must be
 *        explicitly confirmed before it runs (it is NOT auto-executed).
 * @param intentName the matched intent name, if any (for display / traceability).
 * @param reviewRequired true if the note needs user review (unresolved parse) OR the
 *        global review mode asks for it.
 */
data class ProcessedTranscript(
    val note: Note,
    val task: TaskItem? = null,
    val pendingConfirmation: PendingConfirmation? = null,
    val intentName: String? = null,
    val reviewRequired: Boolean = false,
)

/**
 * A high-risk action that was recognised but gated behind an explicit confirmation.
 * The [request] is already validated by the registry; executing it still requires the
 * caller to pass `confirmed = true` back through [TranscriptProcessor.confirm].
 */
data class PendingConfirmation(
    val request: ActionRequest,
    val reason: String,
)

/**
 * Deterministic transcript -> structured result orchestrator.
 *
 * It wires the three pure building blocks together exactly as the live pipeline will:
 * 1. [FormattingParser] turns the transcript into a structured [Note] (blocks + tags).
 * 2. [IntentParser] detects a command intent (create task / export / etc.).
 * 3. [ActionRegistry] is the SOLE authorizer: high-risk intents become a
 *    [PendingConfirmation] (never auto-run); low-risk create intents are authorized.
 *
 * No persistence, no Android, no network — fully unit-testable on the JVM.
 */
class TranscriptProcessor(
    private val registry: ActionRegistry = ActionRegistry(),
    private val formattingParser: FormattingParser = FormattingParser(),
    private val clock: Clock = SystemClock,
    private val ids: IdGenerator = UuidGenerator,
) {

    private val intentParser = IntentParser(DEFAULT_TRIGGERS)

    /**
     * Process [transcript] into a [ProcessedTranscript].
     *
     * @param source how the transcript was captured (SIMULATOR for the text screen).
     * @param language optional BCP-47-ish language tag for the note.
     */
    fun process(
        transcript: String,
        source: NoteSource = NoteSource.SIMULATOR,
        language: String? = null,
    ): ProcessedTranscript {
        val now = clock.nowEpochMillis()
        val parsed = formattingParser.parse(transcript)

        val title = parsed.documentBlocks
            .filterIsInstance<NoteBlock.Heading>()
            .firstOrNull()
            ?.text

        val note = Note(
            id = ids.newId(),
            title = title,
            blocks = parsed.documentBlocks,
            tags = parsed.tags,
            createdAt = now,
            updatedAt = now,
            source = source,
            transcriptLanguage = language,
            reviewRequired = parsed.reviewRequired,
        )

        val intent = intentParser.parse(transcript)
        var task: TaskItem? = null
        var pending: PendingConfirmation? = null

        if (intent != null) {
            when (intent.name) {
                INTENT_CREATE_TASK -> {
                    val slots = SlotParser(now).extract(transcript)
                    val taskTitle = (slots["title"] as? ParamValue.Text)?.value
                        ?: title
                        ?: firstLine(parsed.normalizedTranscript)
                    val due = (slots["date"] as? ParamValue.Timestamp)?.epochMillis
                    val request = ActionRequest(
                        type = ActionType.CREATE_TASK,
                        args = buildMap {
                            put("title", ParamValue.Text(taskTitle))
                            if (due != null) put("due", ParamValue.Timestamp(due))
                        },
                    )
                    // Authorize through the registry; CREATE_TASK is LOW risk.
                    when (val decision = registry.authorize(request, matchConfidence = intent.confidence)) {
                        is AuthorizationDecision.Authorized -> {
                            task = TaskItem(
                                id = ids.newId(),
                                title = taskTitle,
                                due = due,
                                priority = Priority.NORMAL,
                                createdAt = now,
                                updatedAt = now,
                                linkedNoteId = note.id,
                            )
                        }
                        is AuthorizationDecision.NeedsConfirmation ->
                            pending = PendingConfirmation(request, decision.reason)
                        else -> Unit
                    }
                }
                INTENT_EXPORT_NOTE -> {
                    val request = ActionRequest(
                        type = ActionType.EXPORT_NOTE,
                        args = mapOf("noteId" to ParamValue.Text(note.id)),
                    )
                    // EXPORT_NOTE is HIGH risk / confirmation-mandatory -> always gated.
                    when (val decision = registry.authorize(request, matchConfidence = intent.confidence)) {
                        is AuthorizationDecision.NeedsConfirmation ->
                            pending = PendingConfirmation(request, decision.reason)
                        is AuthorizationDecision.Authorized -> Unit // never reached unconfirmed
                        else -> Unit
                    }
                }
            }
        }

        return ProcessedTranscript(
            note = note,
            task = task,
            pendingConfirmation = pending,
            intentName = intent?.name,
            reviewRequired = note.reviewRequired,
        )
    }

    /**
     * Re-authorize a previously pending request now that the user has confirmed it.
     * Returns the [AuthorizationDecision] from the registry with `confirmed = true` so the
     * caller can execute. The registry remains the sole authorizer.
     */
    fun confirm(request: ActionRequest): AuthorizationDecision =
        registry.authorize(request, confirmed = true)

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { "Untitled" }

    companion object {
        const val INTENT_CREATE_TASK = "create_task"
        const val INTENT_EXPORT_NOTE = "export_note"

        /**
         * Phrase triggers for the Phase-1 recognizable command intents. English + a few
         * pt-BR localized phrasings (recognition language default is pt-BR).
         */
        val DEFAULT_TRIGGERS: List<TriggerDefinition> = listOf(
            TriggerDefinition(
                intent = INTENT_CREATE_TASK,
                phrases = listOf("create a task", "add a task", "new task"),
                aliases = listOf("remind me to", "add task", "create task"),
                localized = mapOf("pt-BR" to listOf("criar tarefa", "nova tarefa", "adicionar tarefa")),
            ),
            TriggerDefinition(
                intent = INTENT_EXPORT_NOTE,
                phrases = listOf("export note", "export this note"),
                aliases = listOf("share note", "export the note"),
                localized = mapOf("pt-BR" to listOf("exportar nota", "compartilhar nota")),
            ),
        )
    }
}
