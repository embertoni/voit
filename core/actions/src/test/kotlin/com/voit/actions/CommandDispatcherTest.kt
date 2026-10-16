package com.voit.actions

import com.voit.domain.ActionRequest
import com.voit.domain.ActionType
import com.voit.domain.CommandDefinition
import com.voit.domain.ConfirmationPolicy
import com.voit.domain.ParamValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the Phase-5 command execution path on the pure [CommandDispatcher]: trigger-phrase
 * matching (whole-word, case-insensitive, enabled-only), ConfirmationPolicy + registry
 * gating (HIGH / confirmation-mandatory always gated regardless of policy), multi-match
 * NeedsChoice (never auto-pick), bound/empty/unknown-action rejection, and the bounded
 * WorkflowRunner dispatch (idempotency key passthrough). No Android, network, persistence.
 */
class CommandDispatcherTest {

    private fun newDispatcher(): CommandDispatcher {
        val registry = ActionRegistry()
        return CommandDispatcher(registry, WorkflowRunner(registry))
    }

    private fun speak(text: String) =
        ActionRequest(ActionType.SPEAK_RESPONSE, mapOf("text" to ParamValue.Text(text)))

    private fun command(
        id: String,
        triggers: List<String>,
        actions: List<ActionRequest>,
        policy: ConfirmationPolicy = ConfirmationPolicy.NONE,
        enabled: Boolean = true,
    ) = CommandDefinition(
        id = id,
        name = id,
        enabled = enabled,
        triggerPhrases = triggers,
        intent = id,
        confirmationPolicy = policy,
        actions = actions,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `no command matches yields NoMatch`() = runTest {
        val dispatcher = newDispatcher()
        val commands = listOf(command("c1", listOf("good morning"), listOf(speak("hi"))))
        val result = dispatcher.dispatch(commands, transcript = "totally unrelated text")
        assertEquals(CommandDispatchResult.NoMatch, result)
    }

    @Test
    fun `single match with NONE policy dispatches the workflow to completion`() = runTest {
        val dispatcher = newDispatcher()
        val commands = listOf(
            command("c1", listOf("good morning"), listOf(speak("a"), speak("b"))),
        )
        val result = dispatcher.dispatch(commands, transcript = "hey, good morning everyone")
        val dispatched = result as CommandDispatchResult.Dispatched
        assertEquals("c1", dispatched.commandId)
        val completed = dispatched.outcome as WorkflowOutcome.Completed
        assertEquals(2, completed.steps.size)
        assertTrue(completed.steps.all { it.succeeded })
    }

    @Test
    fun `matching is case-insensitive and on whole-word boundaries`() = runTest {
        val dispatcher = newDispatcher()
        // "note" must not match inside "notebook"; the real phrase still matches regardless of case.
        val commands = listOf(command("c1", listOf("note"), listOf(speak("x"))))

        val fragment = dispatcher.dispatch(commands, transcript = "open my notebook please")
        assertEquals(CommandDispatchResult.NoMatch, fragment)

        val wholeWord = dispatcher.dispatch(commands, transcript = "take a NOTE")
        assertTrue(wholeWord is CommandDispatchResult.Dispatched)
    }

    @Test
    fun `disabled commands are never matched`() = runTest {
        val dispatcher = newDispatcher()
        val commands = listOf(
            command("c1", listOf("good morning"), listOf(speak("a")), enabled = false),
        )
        val result = dispatcher.dispatch(commands, transcript = "good morning")
        assertEquals(CommandDispatchResult.NoMatch, result)
    }

    @Test
    fun `multiple matching commands return NeedsChoice and never auto-pick`() = runTest {
        val dispatcher = newDispatcher()
        val commands = listOf(
            command("c1", listOf("start"), listOf(speak("a"))),
            command("c2", listOf("start"), listOf(speak("b"))),
        )
        val result = dispatcher.dispatch(commands, transcript = "start")
        val choice = result as CommandDispatchResult.NeedsChoice
        assertEquals(listOf("c1", "c2"), choice.commandIds)
    }

    @Test
    fun `ALWAYS policy gates until confirmed then dispatches`() = runTest {
        val dispatcher = newDispatcher()
        val commands = listOf(
            command(
                "c1",
                listOf("do it"),
                listOf(speak("a")),
                policy = ConfirmationPolicy.ALWAYS,
            ),
        )

        val gated = dispatcher.dispatch(commands, transcript = "do it")
        val needs = gated as CommandDispatchResult.NeedsConfirmation
        assertEquals("c1", needs.commandId)

        val confirmed = dispatcher.dispatch(commands, transcript = "do it", confirmed = true)
        assertTrue(confirmed is CommandDispatchResult.Dispatched)
    }

    @Test
    fun `a HIGH-risk action gates the command even under NONE policy`() = runTest {
        val dispatcher = newDispatcher()
        // EXPORT_NOTE is HIGH risk / confirmation-mandatory in the registry; the command's
        // NONE policy must NOT be able to bypass that gate.
        val export = ActionRequest(
            ActionType.EXPORT_NOTE,
            mapOf("noteId" to ParamValue.Text("n1")),
        )
        val commands = listOf(
            command("c1", listOf("export"), listOf(export), policy = ConfirmationPolicy.NONE),
        )

        val gated = dispatcher.dispatch(commands, transcript = "export")
        assertTrue(gated is CommandDispatchResult.NeedsConfirmation)

        val confirmed = dispatcher.dispatch(commands, transcript = "export", confirmed = true)
        assertTrue(confirmed is CommandDispatchResult.Dispatched)
    }

    @Test
    fun `an unknown or invalid action rejects the command`() = runTest {
        val dispatcher = newDispatcher()
        // CREATE_TASK requires a 'title' arg; omitting it makes the registry reject on validate.
        val invalid = ActionRequest(ActionType.CREATE_TASK, emptyMap())
        val commands = listOf(command("c1", listOf("go"), listOf(invalid)))
        val result = dispatcher.dispatch(commands, transcript = "go")
        assertTrue(result is CommandDispatchResult.Rejected)
    }

    @Test
    fun `a command over the step bound is rejected`() = runTest {
        val dispatcher = newDispatcher()
        val tooMany = List(WorkflowRunner.MAX_STEPS + 1) { speak("s$it") }
        val commands = listOf(command("c1", listOf("go"), tooMany))
        val result = dispatcher.dispatch(commands, transcript = "go")
        val rejected = result as CommandDispatchResult.Rejected
        assertTrue(rejected.reason.contains("max"))
    }

    @Test
    fun `a command with no actions is rejected`() = runTest {
        val dispatcher = newDispatcher()
        val commands = listOf(command("c1", listOf("go"), emptyList()))
        val result = dispatcher.dispatch(commands, transcript = "go")
        assertTrue(result is CommandDispatchResult.Rejected)
    }

    @Test
    fun `re-dispatching the same command id is de-duplicated by the runner`() = runTest {
        val dispatcher = newDispatcher()
        val commands = listOf(command("c1", listOf("go"), listOf(speak("a"))))

        val first = dispatcher.dispatch(commands, transcript = "go")
        assertTrue((first as CommandDispatchResult.Dispatched).outcome is WorkflowOutcome.Completed)

        // Same default idempotency key (the command id) -> runner returns AlreadyRun.
        val second = dispatcher.dispatch(commands, transcript = "go")
        val dispatched = second as CommandDispatchResult.Dispatched
        assertTrue(dispatched.outcome is WorkflowOutcome.AlreadyRun)
    }

    @Test
    fun `blank transcript yields NoMatch`() = runTest {
        val dispatcher = newDispatcher()
        val commands = listOf(command("c1", listOf("go"), listOf(speak("a"))))
        assertEquals(CommandDispatchResult.NoMatch, dispatcher.dispatch(commands, transcript = "   "))
    }
}
