package com.voit.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voit.design.component.VoitButton
import com.voit.design.component.VoitScaffold
import com.voit.design.component.VoitTextField
import com.voit.design.theme.VoitTheme
import com.voit.domain.TaskItem

/**
 * Tasks screen: create, list, complete, archive, and a simple one-day reschedule.
 * Completed state is signalled by the checkbox + a textual label, never by colour alone.
 */
@Composable
fun TasksScreen(
    viewModel: TasksViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = VoitTheme.spacing

    VoitScaffold(modifier = modifier, title = "Tasks") { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                VoitTextField(
                    value = state.newTitle,
                    onValueChange = viewModel::onNewTitleChange,
                    label = "New task",
                    modifier = Modifier.weight(1f),
                )
                VoitButton(text = "Add", onClick = viewModel::createTask, contentDescription = "Add task")
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.tasks.isEmpty()) {
                    item {
                        Text(
                            text = "No tasks yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(spacing.md),
                        )
                    }
                }
                items(state.tasks, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        onToggle = { viewModel.setCompleted(task.id, !task.completed) },
                        onArchive = { viewModel.archive(task.id) },
                        onReschedule = { viewModel.reschedule(task.id, 1) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: TaskItem,
    onToggle: () -> Unit,
    onArchive: () -> Unit,
    onReschedule: () -> Unit,
) {
    val spacing = VoitTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Checkbox(
            checked = task.completed,
            onCheckedChange = { onToggle() },
            modifier = Modifier.semantics {
                contentDescription = if (task.completed) "Completed, tap to reopen" else "Open, tap to complete"
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = task.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (task.completed) "Done" else "Open",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        VoitButton(text = "+1d", onClick = onReschedule, contentDescription = "Reschedule one day later")
        VoitButton(text = "Archive", onClick = onArchive, contentDescription = "Archive task")
    }
}
