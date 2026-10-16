package com.voit.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voit.domain.Clock
import com.voit.domain.IdGenerator
import com.voit.domain.SystemClock
import com.voit.domain.TaskItem
import com.voit.domain.TaskRepository
import com.voit.domain.UuidGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Unidirectional UI state for the tasks screen.
 *
 * @param archived ids the user has archived (hidden) this session. Archive is modeled as a
 *        soft-delete in the repository; we keep the list separate so the toggle reads clearly.
 */
data class TasksUiState(
    val tasks: List<TaskItem> = emptyList(),
    val newTitle: String = "",
)

/**
 * Tasks ViewModel over the [TaskRepository]: list, create, complete, archive, and a simple
 * reschedule (shift the due date by a number of days). [scope] is injectable for tests.
 */
class TasksViewModel(
    private val repository: TaskRepository,
    private val clock: Clock = SystemClock,
    private val ids: IdGenerator = UuidGenerator,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope: CoroutineScope = scope ?: viewModelScope

    private val _uiState = MutableStateFlow(TasksUiState())
    val uiState: StateFlow<TasksUiState> = _uiState.asStateFlow()

    init {
        workScope.launch {
            repository.observeTasks(includeCompleted = true).collect { tasks ->
                _uiState.value = _uiState.value.copy(tasks = tasks)
            }
        }
    }

    fun onNewTitleChange(text: String) {
        _uiState.value = _uiState.value.copy(newTitle = text)
    }

    fun createTask() {
        val title = _uiState.value.newTitle.trim()
        if (title.isEmpty()) return
        val now = clock.nowEpochMillis()
        val task = TaskItem(id = ids.newId(), title = title, createdAt = now, updatedAt = now)
        workScope.launch {
            repository.create(task)
            _uiState.value = _uiState.value.copy(newTitle = "")
        }
    }

    fun setCompleted(id: String, completed: Boolean) {
        workScope.launch { repository.setCompleted(id, completed) }
    }

    /** Archive == soft delete (reversible via import/restore); hidden from the active list. */
    fun archive(id: String) {
        workScope.launch { repository.delete(id) }
    }

    /** Simple reschedule: set/shift the due date by [days] from its current value or now. */
    fun reschedule(id: String, days: Int) {
        workScope.launch {
            val task = repository.getTask(id) ?: return@launch
            val base = task.due ?: clock.nowEpochMillis()
            val newDue = base + days * DAY_MILLIS
            repository.update(task.copy(due = newDue, updatedAt = clock.nowEpochMillis()))
        }
    }

    companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
