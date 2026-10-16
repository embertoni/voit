package com.voit.feature.commands

import com.voit.actions.ActionRegistry
import com.voit.domain.ActionType
import com.voit.domain.ConfirmationPolicy
import com.voit.domain.FakeCommandRepository
import com.voit.domain.FixedClock
import com.voit.domain.SequentialIdGenerator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CommandsViewModelTest {

    private fun vm(repo: FakeCommandRepository, scope: TestScope) =
        CommandsViewModel(repo, ActionRegistry(), FixedClock(1_000L), SequentialIdGenerator(), scope)

    @Test
    fun `available actions are exactly the registry allow-list`() = runTest {
        val repo = FakeCommandRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = vm(repo, scope)
        scope.advanceUntilIdle()

        assertEquals(
            ActionRegistry().registeredTypes.sortedBy { it.name },
            vm.uiState.value.availableActions,
        )
    }

    @Test
    fun `a command is built only from allow-listed actions and persisted`() = runTest {
        val repo = FakeCommandRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = vm(repo, scope)
        scope.advanceUntilIdle()

        vm.startNewDraft()
        vm.setName("Quick note")
        vm.setIntent("create_note")
        vm.setTriggerPhrases("quick note, jot this")
        vm.setConfirmationPolicy(ConfirmationPolicy.NONE)
        vm.addAction(ActionType.CREATE_NOTE)
        assertTrue(vm.saveDraft())
        scope.advanceUntilIdle()

        val commands = repo.observeCommands().first()
        assertEquals(1, commands.size)
        val cmd = commands.single()
        assertEquals("Quick note", cmd.name)
        assertEquals(listOf(ActionType.CREATE_NOTE), cmd.actions.map { it.type })
        assertEquals(ConfirmationPolicy.NONE, cmd.confirmationPolicy)
    }

    @Test
    fun `the action list is bounded at the max`() = runTest {
        val repo = FakeCommandRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = vm(repo, scope)
        scope.advanceUntilIdle()

        vm.startNewDraft()
        repeat(CommandsViewModel.MAX_ACTIONS + 2) { vm.addAction(ActionType.CREATE_NOTE) }

        assertEquals(CommandsViewModel.MAX_ACTIONS, vm.uiState.value.draft.actions.size)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun `save fails without a name intent or any action`() = runTest {
        val repo = FakeCommandRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = vm(repo, scope)
        scope.advanceUntilIdle()

        vm.startNewDraft()
        assertTrue(!vm.saveDraft())
        assertNotNull(vm.uiState.value.error)
    }
}
