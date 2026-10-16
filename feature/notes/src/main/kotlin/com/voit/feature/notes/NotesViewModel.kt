package com.voit.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voit.domain.Clock
import com.voit.domain.IdGenerator
import com.voit.domain.Note
import com.voit.domain.NoteBlock
import com.voit.domain.NoteExporter
import com.voit.domain.NoteRepository
import com.voit.domain.NoteSource
import com.voit.domain.SystemClock
import com.voit.domain.UuidGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Unidirectional UI state for the notes list.
 *
 * @param notes the currently displayed notes (filtered by [query] when non-blank).
 * @param reviewNotes the subset flagged reviewRequired=true, surfaced as editable previews.
 */
data class NotesUiState(
    val notes: List<Note> = emptyList(),
    val query: String = "",
    val reviewNotes: List<Note> = emptyList(),
)

/**
 * Notes list/review ViewModel over the [NoteRepository] abstraction. Exposes an immutable
 * [NotesUiState] via [StateFlow]. [scope] is injectable for JVM unit tests.
 */
class NotesViewModel(
    private val repository: NoteRepository,
    private val clock: Clock = SystemClock,
    private val ids: IdGenerator = UuidGenerator,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope: CoroutineScope = scope ?: viewModelScope

    private val query = MutableStateFlow("")
    private val _uiState = MutableStateFlow(NotesUiState())
    val uiState: StateFlow<NotesUiState> = _uiState.asStateFlow()

    init {
        workScope.launch {
            combine(repository.observeNotes(includeArchived = false), query) { notes, q ->
                val filtered = if (q.isBlank()) {
                    notes
                } else {
                    notes.filter { it.matches(q.trim().lowercase()) }
                }
                NotesUiState(
                    notes = filtered,
                    query = q,
                    reviewNotes = notes.filter { it.reviewRequired },
                )
            }.collect { _uiState.value = it }
        }
    }

    fun onQueryChange(text: String) {
        query.value = text
    }

    /** Create a new blank note and return its id (so the caller can navigate to it). */
    fun createNote(onCreated: (String) -> Unit = {}) {
        val now = clock.nowEpochMillis()
        val note = Note(
            id = ids.newId(),
            title = null,
            blocks = listOf(NoteBlock.Paragraph("")),
            createdAt = now,
            updatedAt = now,
            source = NoteSource.MANUAL,
        )
        workScope.launch {
            repository.create(note)
            onCreated(note.id)
        }
    }

    fun archive(id: String) {
        workScope.launch { repository.archive(id) }
    }

    fun delete(id: String) {
        workScope.launch { repository.delete(id) }
    }

    /** Markdown export text for a note (sharing is wired by the UI layer). */
    fun exportMarkdown(note: Note): String = NoteExporter.toMarkdown(note)

    private fun Note.matches(q: String): Boolean {
        if (title?.lowercase()?.contains(q) == true) return true
        if (tags.any { it.lowercase().contains(q) }) return true
        return blocks.any { blockText(it).lowercase().contains(q) }
    }
}

internal fun blockText(block: NoteBlock): String = when (block) {
    is NoteBlock.Paragraph -> block.text
    is NoteBlock.Heading -> block.text
    is NoteBlock.Bullet -> block.text
    is NoteBlock.Task -> block.text
    is NoteBlock.Code -> block.text
    is NoteBlock.Quote -> block.text
}
