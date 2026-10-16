package com.voit.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voit.design.component.VoitButton
import com.voit.design.component.VoitIconButton
import com.voit.design.component.VoitScaffold
import com.voit.design.component.VoitTextField
import com.voit.design.theme.VoitTheme
import com.voit.domain.NoteBlock

/**
 * Edit a note's title + blocks, toggle task checkboxes, edit tags, and export (markdown).
 */
@Composable
fun NoteEditorScreen(
    viewModel: NoteEditorViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = VoitTheme.spacing

    VoitScaffold(
        modifier = modifier,
        title = "Edit note",
        onNavigateBack = onNavigateBack,
        actions = {
            VoitIconButton(
                icon = Icons.Filled.Share,
                contentDescription = "Export note as markdown",
                onClick = viewModel::requestExport,
            )
        },
    ) { padding ->
        val note = state.note
        if (note == null) {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (state.loading) "Loading..." else "Note not found")
            }
            return@VoitScaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            VoitTextField(
                value = note.title.orEmpty(),
                onValueChange = viewModel::onTitleChange,
                label = "Title",
            )

            note.blocks.forEachIndexed { index, block ->
                when (block) {
                    is NoteBlock.Task -> TaskBlockRow(
                        text = block.text,
                        completed = block.completed,
                        onToggle = { viewModel.toggleTask(index) },
                        onTextChange = { viewModel.onBlockTextChange(index, it) },
                    )
                    else -> VoitTextField(
                        value = blockText(block),
                        onValueChange = { viewModel.onBlockTextChange(index, it) },
                        label = blockLabel(block),
                        singleLine = false,
                    )
                }
            }

            VoitTextField(
                value = note.tags.joinToString(", "),
                onValueChange = { viewModel.setTags(it.split(",")) },
                label = "Tags (comma separated)",
            )

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                VoitButton(text = "Add paragraph", onClick = viewModel::addParagraph)
                VoitButton(text = "Save", onClick = viewModel::save)
            }
        }
    }

    state.exportText?.let { md ->
        AlertDialog(
            onDismissRequest = viewModel::clearExport,
            title = { Text("Export (markdown)") },
            text = { Text(md, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = viewModel::clearExport) { Text("Done") } },
        )
    }
}

@Composable
private fun TaskBlockRow(
    text: String,
    completed: Boolean,
    onToggle: () -> Unit,
    onTextChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VoitTheme.spacing.sm),
    ) {
        Checkbox(
            checked = completed,
            onCheckedChange = { onToggle() },
            modifier = Modifier.semantics {
                contentDescription = if (completed) "Completed task, tap to reopen" else "Open task, tap to complete"
            },
        )
        VoitTextField(
            value = text,
            onValueChange = onTextChange,
            label = if (completed) "Task (done)" else "Task",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun blockLabel(block: NoteBlock): String = when (block) {
    is NoteBlock.Heading -> "Heading"
    is NoteBlock.Paragraph -> "Paragraph"
    is NoteBlock.Bullet -> "Bullet"
    is NoteBlock.Task -> "Task"
    is NoteBlock.Code -> "Code"
    is NoteBlock.Quote -> "Quote"
}
