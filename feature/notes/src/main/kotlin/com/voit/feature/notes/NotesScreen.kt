package com.voit.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voit.design.component.VoitListItem
import com.voit.design.component.VoitScaffold
import com.voit.design.component.VoitSectionHeader
import com.voit.design.component.VoitTextField
import com.voit.design.theme.VoitTheme
import com.voit.domain.Note

/**
 * Notes list: search, create, open, and a review section surfacing notes flagged
 * reviewRequired=true as editable previews.
 */
@Composable
fun NotesScreen(
    viewModel: NotesViewModel,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = VoitTheme.spacing

    VoitScaffold(
        modifier = modifier,
        title = "Notes",
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.createNote(onCreated = onOpenNote) },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Create note")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            VoitTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = "Search notes",
                placeholder = "Search title, tags, or content",
                modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.reviewNotes.isNotEmpty()) {
                    item { VoitSectionHeader(text = "Needs review") }
                    items(state.reviewNotes, key = { "review-" + it.id }) { note ->
                        NoteRow(note = note, needsReview = true, onClick = { onOpenNote(note.id) })
                        HorizontalDivider()
                    }
                }

                item { VoitSectionHeader(text = "All notes") }
                if (state.notes.isEmpty()) {
                    item {
                        Text(
                            text = "No notes yet. Use the Simulator to create one, or tap +.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(spacing.md),
                        )
                    }
                }
                items(state.notes, key = { it.id }) { note ->
                    NoteRow(note = note, needsReview = note.reviewRequired, onClick = { onOpenNote(note.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun NoteRow(note: Note, needsReview: Boolean, onClick: () -> Unit) {
    val summary = note.blocks.firstOrNull()?.let { blockText(it) }?.take(80).orEmpty()
    VoitListItem(
        headline = note.title?.takeIf { it.isNotBlank() } ?: summary.ifBlank { "Untitled note" },
        supporting = summary.takeIf { note.title != null && it.isNotBlank() },
        // Review state is signalled by an explicit label + glyph, never colour alone.
        overline = if (needsReview) "⚠ NEEDS REVIEW" else null,
        trailing = { Text(text = "›", style = MaterialTheme.typography.titleMedium) },
        onClick = onClick,
        rowContentDescription = buildString {
            append(note.title ?: "Untitled note")
            if (needsReview) append(", needs review")
        },
    )
}
