package com.voit.feature.simulator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voit.actions.TranscriptProcessor
import com.voit.design.component.VoitButton
import com.voit.design.component.VoitScaffold
import com.voit.design.component.VoitSectionHeader
import com.voit.design.component.VoitSoundRing
import com.voit.design.component.VoitTextField
import com.voit.design.theme.VoitTheme
import com.voit.domain.Note
import com.voit.domain.NoteBlock
import com.voit.domain.TaskItem

/**
 * The text-command Simulator: Voit's manual stand-in for the microphone. Type a transcript,
 * tap Run, and the parsed structured note/task is created and previewed. High-risk actions
 * show a confirmation dialog and are never run automatically.
 */
@Composable
fun SimulatorScreen(
    viewModel: SimulatorViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = VoitTheme.spacing

    VoitScaffold(modifier = modifier, title = "Simulator") { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            VoitSoundRing(
                modifier = Modifier.padding(top = spacing.md),
                contentDescription = "Voit sound ring",
            )
            Text(
                text = "Type a transcript as if you had spoken it, then Run. " +
                    "This is the Phase 1 microphone stand-in; no audio is captured.",
                style = MaterialTheme.typography.bodyMedium,
            )

            VoitTextField(
                value = state.transcript,
                onValueChange = viewModel::onTranscriptChange,
                label = "Transcript",
                placeholder = "e.g. title: Groceries bullet point milk tag shopping",
                singleLine = false,
                minLines = 4,
            )

            VoitButton(
                text = if (state.running) "Running..." else "Run",
                onClick = viewModel::run,
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth(),
                contentDescription = "Run the transcript through the parser",
            )

            state.message?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.semantics { },
                )
            }

            if (state.reviewRequired) {
                ReviewBanner()
            }

            state.lastNote?.let { note ->
                VoitSectionHeader(text = "Created note")
                NotePreview(note = note)
            }

            state.lastTask?.let { task ->
                VoitSectionHeader(text = "Created task")
                TaskPreview(task = task)
            }
        }
    }

    state.pendingConfirmation?.let { pending ->
        ConfirmationDialog(
            typeName = pending.request.type.name,
            reason = pending.reason,
            onConfirm = viewModel::confirmPending,
            onDismiss = viewModel::dismissPending,
        )
    }
}

@Composable
private fun ReviewBanner() {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(VoitTheme.spacing.md)) {
            Text(
                text = "⚠ Review recommended",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Part of the transcript could not be fully resolved. " +
                    "Nothing was discarded; open the note to review it.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun NotePreview(note: Note) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(VoitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(VoitTheme.spacing.xs),
        ) {
            note.title?.let {
                Text(text = it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            note.blocks.forEach { block ->
                Text(text = renderBlock(block), style = MaterialTheme.typography.bodyMedium)
            }
            if (note.tags.isNotEmpty()) {
                Text(
                    text = note.tags.joinToString(" ") { "#$it" },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun TaskPreview(task: TaskItem) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(VoitTheme.spacing.md)) {
            Text(text = task.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (task.completed) "[x] completed" else "[ ] open",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ConfirmationDialog(
    typeName: String,
    reason: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm high-risk action") },
        text = { Text("$typeName requires explicit confirmation.\n\n$reason") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun renderBlock(block: NoteBlock): String = when (block) {
    is NoteBlock.Heading -> "§ ${block.text}"
    is NoteBlock.Paragraph -> block.text
    is NoteBlock.Bullet -> "• ${block.text}"
    is NoteBlock.Task -> (if (block.completed) "[x] " else "[ ] ") + block.text
    is NoteBlock.Code -> "code: ${block.text}"
    is NoteBlock.Quote -> "\" ${block.text}"
}
