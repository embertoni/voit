package com.voit.feature.tasks

import com.voit.domain.FakeTaskRepository
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {

    private fun vm(repo: FakeTaskRepository, scope: TestScope) =
        TasksViewModel(repo, FixedClock(1_000L), SequentialIdGenerator(), scope)

    @Test
    fun `create complete and reschedule a task`() = runTest {
        val repo = FakeTaskRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = vm(repo, scope)
        scope.advanceUntilIdle()

        vm.onNewTitleChange("Buy milk")
        vm.createTask()
        scope.advanceUntilIdle()

        val created = vm.uiState.value.tasks
        assertEquals(1, created.size)
        val id = created.single().id

        vm.setCompleted(id, true)
        scope.advanceUntilIdle()
        assertTrue(repo.getTask(id)!!.completed)

        vm.reschedule(id, 1)
        scope.advanceUntilIdle()
        val due = repo.getTask(id)!!.due
        assertEquals(1_000L + TasksViewModel.DAY_MILLIS, due)
    }

    @Test
    fun `archive removes a task from the active list`() = runTest {
        val repo = FakeTaskRepository()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = vm(repo, scope)
        scope.advanceUntilIdle()

        vm.onNewTitleChange("Temp")
        vm.createTask()
        scope.advanceUntilIdle()
        val id = vm.uiState.value.tasks.single().id

        vm.archive(id)
        scope.advanceUntilIdle()

        assertTrue(repo.observeTasks().first().isEmpty())
    }
}
