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
import com.voit.design.component.VoitButton
import com.voit.design.component.VoitScaffold
import com.voit.design.component.VoitSectionHeader
import com.voit.design.component.VoitSoundRing
import com.voit.design.component.VoitTextField
import com.voit.design.theme.VoitTheme
import com.voit.domain.Note
import com.voit.domain.NoteBlock
import com.voit.domain.TaskItem
import com.voit.voice.RecordingState

/**
 * The push-to-talk voice screen: Voit's Phase-2 end-to-end path. Press to record, release to
 * transcribe, then REVIEW/EDIT the (faked) transcript before it runs through the same
 * [com.voit.actions.TranscriptProcessor] the Simulator uses, now with `source = VOICE`.
 *
 * There is no real microphone or ASR model wired here (that awaits the Phase-0 hardware
 * benchmark): the default engine is a fake placeholder. The review field is ALWAYS editable,
 * and on the fallback path (empty recognition / timeout / failure) it starts empty with a
 * clearly-labelled message so manual entry is always possible. High-risk actions show a
 * confirmation dialog and are never run automatically.
 *
 * Strictly monochrome; state is conveyed by text labels, never by colour alone.
 */
/**
 * @param onStartRecording invoked when the user presses "Start recording". The app wires
 *   this to route through the runtime voice-permission flow (the pure
 *   `VoicePermissionModel`) BEFORE capture begins; it defaults to calling
 *   [PushToTalkViewModel.startRecording] directly so previews and tests work unchanged.
 */
@Composable
fun PushToTalkScreen(
    viewModel: PushToTalkViewModel,
    modifier: Modifier = Modifier,
    onStartRecording: () -> Unit = viewModel::startRecording,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = VoitTheme.spacing

    val isRecording = state.recording is RecordingState.Recording
    val isTranscribing = state.recording is RecordingState.Transcribing

    VoitScaffold(modifier = modifier, title = "Voice") { padding ->
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
                text = recordingStatusLabel(state.recording),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = "Press and hold to record, release to transcribe. No audio is " +
                    "retained: the capture buffer is discarded immediately after " +
                    "recognition. The default recognizer is a fake placeholder until the " +
                    "Phase 0 on-device model is chosen.",
                style = MaterialTheme.typography.bodyMedium,
            )

            // Press-to-start / release-to-stop control. Two explicit buttons keep the
            // affordance accessible (press-and-hold alone is hard for TalkBack).
            VoitButton(
                text = if (isRecording) "Recording... (tap Stop)" else "Start recording",
                onClick = onStartRecording,
                enabled = !isRecording && !isTranscribing,
                modifier = Modifier.fillMaxWidth(),
                contentDescription = "Start push-to-talk recording",
            )
            VoitButton(
                text = "Stop and transcribe",
                onClick = viewModel::stopRecording,
                enabled = isRecording,
                modifier = Modifier.fillMaxWidth(),
                contentDescription = "Stop recording and transcribe",
            )
            if (isRecording) {
                VoitButton(
                    text = "Cancel",
                    onClick = viewModel::cancelRecording,
                    modifier = Modifier.fillMaxWidth(),
                    contentDescription = "Cancel recording",
                )
            }

            state.message?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.semantics { },
                )
            }

            if (state.reviewing) {
                VoitSectionHeader(text = "Review transcript")
                if (state.fallback) {
                    FallbackBanner(errorCode = state.errorCode)
                }
                VoitTextField(
                    value = state.transcript,
                    onValueChange = viewModel::onTranscriptChange,
                    label = "Transcript (editable)",
                    placeholder = "Type or correct the transcript, then Accept",
                    singleLine = false,
                    minLines = 4,
                )
                VoitButton(
                    text = "Accept",
                    onClick = viewModel::acceptReview,
                    modifier = Modifier.fillMaxWidth(),
                    contentDescription = "Accept the transcript and create a note",
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

private fun recordingStatusLabel(state: RecordingState): String = when (state) {
    is RecordingState.Idle -> "Status: idle"
    is RecordingState.Recording -> "Status: recording"
    is RecordingState.Transcribing -> "Status: transcribing"
    is RecordingState.Review -> if (state.fallback) "Status: review (manual entry)" else "Status: review"
}

@Composable
private fun FallbackBanner(errorCode: String?) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(VoitTheme.spacing.md)) {
            Text(
                text = "⚠ No transcript recognized",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Recognition did not return usable text" +
                    (errorCode?.let { " (code: $it)" } ?: "") +
                    ". Type the transcript below and Accept to continue; nothing was lost.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
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
